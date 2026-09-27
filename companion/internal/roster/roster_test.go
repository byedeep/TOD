package roster

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func observation(seq int64) Observation {
	return Observation{SchemaVersion: 1, EventID: fmt.Sprintf("00000000-0000-4000-8000-%012d", seq),
		SourceID: "10000000-0000-4000-8000-000000000001", SegmentID: "20000000-0000-4000-8000-000000000001",
		Sequence: seq, ObservedAt: "2026-09-27T12:00:00Z", Type: "roster_snapshot", Phase: "active",
		Players: []Player{{"Example_1", "red"}, {"Example2", "blue"}}}
}
func testStore(t *testing.T) *Store {
	t.Helper()
	s, err := OpenStore(filepath.Join(t.TempDir(), "rosters.json"), "123")
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func post(h http.Handler, body string, auth, origin string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/v1/observations", strings.NewReader(body))
	r.RemoteAddr = "127.0.0.1:12345"
	r.Header.Set("Authorization", auth)
	r.Header.Set("Content-Type", "application/json")
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func batch(o ...Observation) string {
	b, _ := json.Marshal(map[string]any{"observations": o})
	return string(b)
}

func TestIngestAuthenticationValidationAndCommit(t *testing.T) {
	s := testStore(t)
	h := Handler("secret", s)
	o := observation(1)
	for _, tc := range []struct {
		body, auth, origin string
		status             int
	}{
		{batch(o), "", "", 401}, {batch(o), "Bearer wrong", "", 401}, {batch(o), "Bearer secret", "null", 401},
		{`{"observations":[]}`, "Bearer secret", "", 400},
		{batch(o) + `{}`, "Bearer secret", "", 400},
		{strings.Replace(batch(o), `"name":"Example_1"`, `"name":"@everyone"`, 1), "Bearer secret", "", 400},
		{strings.Replace(batch(o), `"schema_version":1`, `"schema_version":2`, 1), "Bearer secret", "", 400},
		{strings.Replace(batch(o), `"team":"red"`, `"team":"red","uuid":"private"`, 1), "Bearer secret", "", 400},
		{`{"observations":[` + strings.Repeat(" ", 256*1024) + `]}`, "Bearer secret", "", 400},
	} {
		if w := post(h, tc.body, tc.auth, tc.origin); w.Code != tc.status {
			t.Fatalf("got %d expected %d: %s", w.Code, tc.status, w.Body)
		}
	}
	if len(s.Pending()) != 0 {
		t.Fatal("rejected requests changed storage")
	}
	w := post(h, batch(o), "Bearer secret", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), o.EventID) {
		t.Fatal(w.Code, w.Body)
	}
	reopened, err := OpenStore(s.path, "123")
	if err != nil || len(reopened.Pending()) != 1 {
		t.Fatal("ack before durable commit", err)
	}
	info, _ := os.Stat(s.path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("state permissions", info.Mode())
	}
	if _, err := OpenStore(s.path, "456"); err == nil {
		t.Fatal("channel change must not reuse bindings")
	}
}

func TestStoreRetriesCorrectionsAndClosure(t *testing.T) {
	s := testStore(t)
	o := observation(1)
	if err := s.Accept([]Observation{o, o}); err != nil {
		t.Fatal(err)
	}
	if len(s.Pending()) != 1 {
		t.Fatal("duplicate creates more work")
	}
	if err := s.Delivered(o, "987"); err != nil {
		t.Fatal(err)
	}
	if err := s.Accept([]Observation{o}); err != nil || len(s.Pending()) != 0 {
		t.Fatal("retry resends acknowledged state", err)
	}
	newer := observation(3)
	newer.Players[0].Team = "green"
	if err := s.Accept([]Observation{newer, observation(2)}); err != nil {
		t.Fatal(err)
	}
	if s.Pending()[0].Observation.Players[0].Team != "green" {
		t.Fatal("older replay rolled back correction")
	}
	// In-flight acknowledgement must retain a newer revision for a subsequent edit.
	if err := s.Delivered(observation(2), "987"); err != nil {
		t.Fatal(err)
	}
	if len(s.Pending()) != 1 || s.Pending()[0].MessageID != "987" {
		t.Fatal("lost concurrent update or binding")
	}
	conflict := newer
	conflict.Phase = "ended"
	if err := s.Accept([]Observation{conflict}); err == nil {
		t.Fatal("same sequence conflicting content accepted")
	}
	end := observation(4)
	end.Phase = "ended"
	if err := s.Accept([]Observation{end}); err != nil {
		t.Fatal(err)
	}
	if err := s.Accept([]Observation{observation(5)}); err == nil {
		t.Fatal("closed segment reopened")
	}
}

func TestAtomicBatchAndStorageFailureNeverAcknowledge(t *testing.T) {
	s := testStore(t)
	bad := observation(2)
	bad.Players[0].Team = "rank"
	if err := s.Accept([]Observation{observation(1), bad}); err == nil || len(s.Pending()) != 0 {
		t.Fatal("partial invalid batch committed")
	}
	s.path = filepath.Join(t.TempDir(), "missing", "state.json")
	w := post(Handler("secret", s), batch(observation(1)), "Bearer secret", "")
	if w.Code != 503 || strings.Contains(w.Body.String(), "acknowledged") || len(s.Pending()) != 0 {
		t.Fatal("failed write acknowledged", w.Code)
	}
}

func TestBoundedStorePreservesPending(t *testing.T) {
	s := testStore(t)
	observations := []Observation{}
	for i := 0; i < MaxSegments; i++ {
		o := observation(1)
		o.SegmentID = fmt.Sprintf("20000000-0000-4000-8000-%012d", i)
		observations = append(observations, o)
	}
	if err := s.Accept(observations); err != nil {
		t.Fatal(err)
	}
	extra := observation(1)
	extra.SegmentID = "30000000-0000-4000-8000-000000000001"
	if err := s.Accept([]Observation{extra}); err == nil {
		t.Fatal("unbounded store")
	}
	if len(s.Pending()) != MaxSegments {
		t.Fatal("pending work discarded")
	}
}

func TestRenderOnlyNamesAndTeamColorsWithinLimits(t *testing.T) {
	o := observation(1)
	o.Players = nil
	for i := 0; i < 100; i++ {
		o.Players = append(o.Players, Player{fmt.Sprintf("Player_%09d", i), Teams[i%len(Teams)].ID})
	}
	m := Render(o)
	b, _ := json.Marshal(m)
	if len(m.Embeds) != 9 || len(m.AllowedMentions["parse"]) != 0 {
		t.Fatal("team/mention limits")
	}
	total := 0
	for i, e := range m.Embeds {
		total += len(e.Title) + len(e.Description)
		if e.Color != Teams[i].Color || len(e.Description) > 4096 {
			t.Fatal("bad embed")
		}
	}
	if total > 6000 {
		t.Fatal("combined embed limit")
	}
	for _, secret := range []string{o.SourceID, o.EventID, o.SegmentID, "uuid", "kills", "stats"} {
		if bytes.Contains(b, []byte(secret)) {
			t.Fatal("unexpected metadata in Discord", secret)
		}
	}
}

func TestDiscordCreateEditRateLimitAndErrors(t *testing.T) {
	var requests []Message
	var methods []string
	status := 200
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bot test-token" {
			t.Error("missing bot auth")
		}
		if !strings.HasPrefix(r.URL.Path, "/channels/123/messages") {
			t.Error(r.URL.Path)
		}
		var m Message
		_ = json.NewDecoder(r.Body).Decode(&m)
		requests = append(requests, m)
		methods = append(methods, r.Method)
		if status == 429 {
			w.Header().Set("Retry-After", "4.5")
			w.WriteHeader(429)
			fmt.Fprint(w, `{"retry_after":3.5}`)
			return
		}
		w.Header().Set("X-RateLimit-Remaining", "0")
		w.Header().Set("X-RateLimit-Reset-After", "1.25")
		w.WriteHeader(status)
		fmt.Fprint(w, `{"id":"987"}`)
	}))
	defer api.Close()
	d := Discord{Token: "test-token", ChannelID: "123", BaseURL: api.URL, Client: api.Client()}
	e := Entry{Observation: observation(1)}
	id, wait, err := d.Send(context.Background(), e)
	if err != nil || id != "987" || wait != 1250*time.Millisecond {
		t.Fatal(id, wait, err)
	}
	_, _, _ = d.Send(context.Background(), e)
	if methods[0] != "POST" || !requests[0].EnforceNonce || len(requests[0].Nonce) > 25 || requests[0].Nonce != requests[1].Nonce {
		t.Fatal("create retries must reuse nonce")
	}
	e.MessageID = id
	_, _, err = d.Send(context.Background(), e)
	if err != nil || methods[2] != "PATCH" || requests[2].Nonce != "" {
		t.Fatal("update should edit bound message", err)
	}
	status = 429
	_, wait, err = d.Send(context.Background(), e)
	if err == nil || wait != 4500*time.Millisecond {
		t.Fatal("rate limit not honored", wait, err)
	}
	status = 403
	_, _, err = d.Send(context.Background(), e)
	if err == nil || strings.Contains(err.Error(), "test-token") {
		t.Fatal("missing or unsafe failure", err)
	}
}

func TestEndToEndIngestDeliveryAndRestart(t *testing.T) {
	s := testStore(t)
	h := Handler("secret", s)
	var mu sync.Mutex
	var methods []string
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		methods = append(methods, r.Method)
		mu.Unlock()
		fmt.Fprint(w, `{"id":"987"}`)
	}))
	defer api.Close()
	d := &Discord{Token: "test", ChannelID: "123", BaseURL: api.URL, Client: api.Client()}
	if w := post(h, batch(observation(1)), "Bearer secret", ""); w.Code != 200 {
		t.Fatal(w.Body)
	}
	deliverOne := func(store *Store) {
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		go func() { Deliver(ctx, store, d, log.New(io.Discard, "", 0)); close(done) }()
		defer func() { cancel(); <-done }()
		deadline := time.Now().Add(6 * time.Second)
		for len(store.Pending()) != 0 && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
		if len(store.Pending()) != 0 {
			t.Fatal("delivery did not drain")
		}
	}
	deliverOne(s)
	reopened, err := OpenStore(s.path, "123")
	if err != nil {
		t.Fatal(err)
	}
	if err := reopened.Accept([]Observation{observation(2)}); err != nil {
		t.Fatal(err)
	}
	deliverOne(reopened)
	mu.Lock()
	defer mu.Unlock()
	if fmt.Sprint(methods) != "[POST PATCH PATCH]" {
		t.Fatal("restart did not reuse message binding", methods)
	}
}

func TestPrivateConfig(t *testing.T) {
	path := filepath.Join(t.TempDir(), "discord.json")
	if err := os.WriteFile(path, []byte(`{"bot_token":"abcdefghijklmnopqrstuvwxyz","channel_id":"123"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadConfig(path); err == nil {
		t.Fatal("world-readable secret accepted")
	}
	if err := os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadConfig(path); err != nil {
		t.Fatal(err)
	}
}

func TestRunCreatesPrivateRendezvousAuthenticatesAndLocksInstance(t *testing.T) {
	dir := t.TempDir()
	config := filepath.Join(dir, "discord.json")
	if err := os.WriteFile(config, []byte(`{"bot_token":"abcdefghijklmnopqrstuvwxyz","channel_id":"123"}`), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Run(ctx, dir, config, log.New(io.Discard, "", 0)) }()
	defer func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(7 * time.Second):
			t.Error("shutdown timed out")
		}
	}()
	bridge := filepath.Join(dir, "bedwars-companion", "bridge.json")
	var c struct {
		Port  int    `json:"port"`
		Token string `json:"token"`
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		if b, err := os.ReadFile(bridge); err == nil {
			if err := json.Unmarshal(b, &c); err != nil {
				t.Fatal(err)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("rendezvous was not created")
		}
		time.Sleep(10 * time.Millisecond)
	}
	info, err := os.Stat(bridge)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("unsafe rendezvous", err)
	}
	if len(c.Token) != 64 || c.Token == "abcdefghijklmnopqrstuvwxyz" {
		t.Fatal("local token must differ from Discord credential")
	}
	client := &http.Client{Timeout: time.Second}
	request, _ := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/v1/health", c.Port), nil)
	res, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 401 {
		t.Fatal("health exposed without auth")
	}
	request.Header.Set("Authorization", "Bearer "+c.Token)
	res, err = client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatal("authenticated health failed")
	}
	if err := Run(ctx, dir, config, log.New(io.Discard, "", 0)); err == nil || !strings.Contains(err.Error(), "already") {
		t.Fatal("instance lock failed", err)
	}
}
