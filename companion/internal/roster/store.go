package roster

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type Entry struct {
	Observation Observation `json:"observation"`
	MessageID   string      `json:"message_id"`
	Delivered   int64       `json:"delivered_sequence"`
	UpdatedAt   time.Time   `json:"updated_at"`
}
type diskState struct {
	SchemaVersion int              `json:"schema_version"`
	ChannelID     string           `json:"channel_id"`
	Entries       map[string]Entry `json:"entries"`
}
type Store struct {
	mu    sync.Mutex
	path  string
	state diskState
}

func OpenStore(path, channel string) (*Store, error) {
	s := &Store{path: path, state: diskState{Schema, channel, map[string]Entry{}}}
	b, err := os.ReadFile(path)
	if err == nil {
		if err = json.Unmarshal(b, &s.state); err != nil {
			return nil, errors.New("invalid roster state; preserve it before recovery")
		}
		if s.state.SchemaVersion != Schema || s.state.Entries == nil || len(s.state.Entries) > MaxSegments {
			return nil, errors.New("unsupported roster state")
		}
		if s.state.ChannelID != channel {
			return nil, errors.New("channel differs from stored bindings; use a new state directory")
		}
		for key, e := range s.state.Entries {
			if e.Observation.validate() != nil || key != e.Observation.key() {
				return nil, errors.New("invalid stored roster")
			}
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return s, nil
}

// AtomicWrite only replaces the destination after flushing the complete new file.
func AtomicWrite(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".roster-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if err = os.Rename(f.Name(), path); err != nil {
		return err
	}
	d, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

func (s *Store) commit(entries map[string]Entry) error {
	next := diskState{Schema, s.state.ChannelID, entries}
	if err := AtomicWrite(s.path, next); err != nil {
		return err
	}
	s.state = next
	return nil
}
func (s *Store) copy() map[string]Entry {
	entries := make(map[string]Entry, len(s.state.Entries))
	for k, v := range s.state.Entries {
		entries[k] = v
	}
	return entries
}

func (s *Store) Accept(batch []Observation) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries := s.copy()
	for _, o := range batch {
		if err := o.validate(); err != nil {
			return err
		}
		old, exists := entries[o.key()]
		if exists && o.Sequence <= old.Observation.Sequence {
			if o.Sequence == old.Observation.Sequence && !equal(o, old.Observation) {
				return errors.New("conflicting sequence")
			}
			continue // Superseded snapshots are safely acknowledged, never applied backwards.
		}
		if exists && (old.Observation.Phase == "ended" || o.EventID == old.Observation.EventID) {
			return errors.New("closed segment or reused event ID")
		}
		if !exists && len(entries) >= MaxSegments {
			// Keep pending deliveries. Only retire the oldest fully delivered binding.
			oldest := ""
			for k, e := range entries {
				if e.Delivered != e.Observation.Sequence || time.Since(e.UpdatedAt) < 7*24*time.Hour {
					continue
				}
				if oldest == "" || e.UpdatedAt.Before(entries[oldest].UpdatedAt) {
					oldest = k
				}
			}
			if oldest == "" {
				return errors.New("roster store full; pending/recent rosters retained")
			}
			delete(entries, oldest)
		}
		old.Observation = o
		old.UpdatedAt = time.Now().UTC()
		entries[o.key()] = old
	}
	return s.commit(entries)
}

func (s *Store) Pending() []Entry {
	s.mu.Lock()
	defer s.mu.Unlock()
	var result []Entry
	for _, e := range s.state.Entries {
		if e.Delivered < e.Observation.Sequence {
			result = append(result, e)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].UpdatedAt.Before(result[j].UpdatedAt) })
	return result
}
func (s *Store) Delivered(o Observation, messageID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries := s.copy()
	e, ok := entries[o.key()]
	if !ok {
		return errors.New("missing segment")
	}
	e.MessageID = messageID
	e.Delivered = o.Sequence
	entries[o.key()] = e
	return s.commit(entries)
}

// Bind a create response before acknowledging a revision. Discord may have returned
// an earlier create with the same nonce after a lost response. A subsequent PATCH
// therefore establishes the exact latest content before marking it delivered.
func (s *Store) Bind(o Observation, messageID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries := s.copy()
	e, ok := entries[o.key()]
	if !ok {
		return errors.New("missing segment")
	}
	e.MessageID = messageID
	entries[o.key()] = e
	return s.commit(entries)
}
func (s *Store) ForgetMessage(o Observation) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries := s.copy()
	e := entries[o.key()]
	e.MessageID = ""
	entries[o.key()] = e
	return s.commit(entries)
}
