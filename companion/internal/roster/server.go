package roster

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net"
	"net/http"
)

func Handler(token string, store *Store) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || host != "127.0.0.1" || len(r.Header.Values("Origin")) != 0 ||
			r.Header.Get("Sec-Fetch-Site") != "" || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/v1/health" && r.Method == http.MethodGet {
			_ = json.NewEncoder(w).Encode(map[string]any{"schema_version": Schema, "service": "roster-preview", "pending_discord": len(store.Pending())})
			return
		}
		if r.URL.Path != "/v1/observations" {
			http.NotFound(w, r)
			return
		}
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			http.Error(w, "method not allowed", 405)
			return
		}
		if r.Header.Get("Content-Type") != "application/json" {
			http.Error(w, "application/json required", 415)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 256*1024)
		var batch struct {
			Observations []Observation `json:"observations"`
		}
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&batch); err != nil || len(batch.Observations) == 0 || len(batch.Observations) > 32 {
			http.Error(w, "invalid batch", 400)
			return
		}
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			http.Error(w, "trailing data", 400)
			return
		}
		for _, o := range batch.Observations {
			if err := o.validate(); err != nil {
				http.Error(w, err.Error(), 400)
				return
			}
		}
		if err := store.Accept(batch.Observations); err != nil {
			// A non-2xx response must never retire the client's outbox entry.
			http.Error(w, "snapshot not committed; retry or inspect companion state", 503)
			return
		}
		ids := []string{}
		for _, o := range batch.Observations {
			ids = append(ids, o.EventID)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"schema_version": Schema, "acknowledged": ids})
	})
}
