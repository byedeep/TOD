package roster

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"
)

var snowflake = regexp.MustCompile(`^[0-9]{1,20}$`)

type Config struct {
	BotToken  string `json:"bot_token"`
	ChannelID string `json:"channel_id"`
}

func ReadConfig(path string) (Config, error) {
	var c Config
	info, err := os.Stat(path)
	if err != nil {
		return c, err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return c, errors.New("Discord config must be a private regular file (chmod 600)")
	}
	f, err := os.Open(path)
	if err != nil {
		return c, err
	}
	defer f.Close()
	d := json.NewDecoder(io.LimitReader(f, 16384))
	d.DisallowUnknownFields()
	if d.Decode(&c) != nil || d.Decode(new(any)) != io.EOF {
		return c, errors.New("invalid Discord config JSON")
	}
	if len(c.BotToken) < 20 || strings.ContainsAny(c.BotToken, " \r\n\t") || !snowflake.MatchString(c.ChannelID) {
		return c, errors.New("set bot_token and channel_id in the private Discord config")
	}
	return c, nil
}

func Run(ctx context.Context, gameDir, configPath string, logger *log.Logger) error {
	cfg, err := ReadConfig(configPath)
	if err != nil {
		return err
	}
	info, err := os.Stat(gameDir)
	if err != nil || !info.IsDir() {
		return errors.New("--game-dir must name an existing Minecraft instance directory")
	}
	dir := filepath.Join(gameDir, "bedwars-companion")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return err
	}
	lock, err := os.OpenFile(filepath.Join(dir, "companion.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return errors.New("a companion is already using this game directory")
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	store, err := OpenStore(filepath.Join(dir, "rosters.json"), cfg.ChannelID)
	if err != nil {
		return err
	}
	secretFile := filepath.Join(dir, "local-token")
	secret, err := os.ReadFile(secretFile)
	if os.IsNotExist(err) {
		b := make([]byte, 32)
		if _, err = rand.Read(b); err != nil {
			return err
		}
		secret = []byte(hex.EncodeToString(b))
		err = os.WriteFile(secretFile, secret, 0600)
	}
	if err != nil {
		return err
	}
	_, secretErr := hex.DecodeString(string(secret))
	if len(secret) != 64 || secretErr != nil {
		return errors.New("invalid local authentication secret")
	}
	if err := os.Chmod(secretFile, 0600); err != nil {
		return err
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	bridge := filepath.Join(dir, "bridge.json")
	if err := AtomicWrite(bridge, map[string]any{"schema_version": Schema, "port": listener.Addr().(*net.TCPAddr).Port, "token": string(secret)}); err != nil {
		return err
	}
	// Leave rendezvous in place so the mod can queue during a companion outage.
	server := &http.Server{Handler: Handler(string(secret), store), ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 10 * time.Second, MaxHeaderBytes: 8192}
	deliveryCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		Deliver(deliveryCtx, store, &Discord{Token: cfg.BotToken, ChannelID: cfg.ChannelID}, logger)
	}()
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-deliveryCtx.Done()
		stop, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if server.Shutdown(stop) != nil {
			_ = server.Close()
		}
	}()
	logger.Print("Roster companion listening on ", listener.Addr(), "; waiting for visible in-game teams")
	err = server.Serve(listener)
	cancel()
	<-done
	<-shutdownDone
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
