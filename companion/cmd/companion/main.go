package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"bedwarscompanion/companion/internal/roster"
)

const version = "0.2.0-roster-preview"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("Bed Wars Companion " + version)
	case "doctor":
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{
			"version": version, "stage": "roster-preview", "ingestion_available": true,
			"discord_available": true, "message": "Roster-only service available via serve; this command does not check credentials or connectivity.",
		})
	case "serve":
		flags := flag.NewFlagSet("serve", flag.ExitOnError)
		game := flags.String("game-dir", "", "Minecraft instance directory (contains mods/)")
		configDir, err := os.UserConfigDir()
		if err != nil {
			log.Fatal(err)
		}
		config := flags.String("config", filepath.Join(configDir, "bedwars-companion", "discord.json"), "private Discord configuration")
		_ = flags.Parse(os.Args[2:])
		if *game == "" || flags.NArg() != 0 {
			flags.Usage()
			os.Exit(2)
		}
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		if err := roster.Run(ctx, *game, *config, log.Default()); err != nil {
			log.Fatal(err)
		}
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "Usage: companion version|doctor|serve --game-dir PATH [--config PATH]")
}
