// Package roster implements the small, roster-only preview protocol.
package roster

import (
	"encoding/json"
	"errors"
	"regexp"
	"sort"
	"strings"
	"time"
)

const Schema = 1
const MaxSegments = 128

type Player struct {
	Name string `json:"name"`
	Team string `json:"team"`
}

type Observation struct {
	SchemaVersion int      `json:"schema_version"`
	EventID       string   `json:"event_id"`
	SourceID      string   `json:"source_id"`
	SegmentID     string   `json:"segment_id"`
	Sequence      int64    `json:"sequence"`
	ObservedAt    string   `json:"observed_at"`
	Type          string   `json:"type"`
	Phase         string   `json:"phase"`
	Players       []Player `json:"players"`
}

type Team struct {
	ID, Name string
	Color    int
}

var Teams = []Team{
	{"red", "Red", 0xFF5555}, {"blue", "Blue", 0x5555FF},
	{"green", "Green", 0x55FF55}, {"yellow", "Yellow", 0xFFFF55},
	{"aqua", "Aqua", 0x55FFFF}, {"white", "White", 0xFFFFFF},
	{"pink", "Pink", 0xFF55FF}, {"gray", "Gray", 0xAAAAAA},
	{"pending", "Team pending", 0x666666},
}

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var namePattern = regexp.MustCompile(`^[A-Za-z0-9_]{1,16}$`)

func (o Observation) key() string { return o.SourceID + "/" + o.SegmentID }

func (o Observation) validate() error {
	if o.SchemaVersion != Schema || o.Type != "roster_snapshot" || !uuidPattern.MatchString(o.EventID) ||
		!uuidPattern.MatchString(o.SourceID) || !uuidPattern.MatchString(o.SegmentID) || o.Sequence < 1 ||
		o.Sequence > 9007199254740991 || (o.Phase != "active" && o.Phase != "ended") || len(o.Players) > 100 || o.Players == nil {
		return errors.New("invalid roster envelope")
	}
	if _, err := time.Parse(time.RFC3339Nano, o.ObservedAt); err != nil {
		return errors.New("invalid observation time")
	}
	seen := map[string]bool{}
	for _, p := range o.Players {
		known := false
		for _, team := range Teams {
			if team.ID == p.Team {
				known = true
			}
		}
		name := strings.ToLower(p.Name)
		if !namePattern.MatchString(p.Name) || !known || seen[name] {
			return errors.New("invalid or duplicate player")
		}
		seen[name] = true
	}
	return nil
}

type Embed struct {
	Title       string `json:"title"`
	Description string `json:"description"`
	Color       int    `json:"color"`
}

type Message struct {
	Content         string              `json:"content"`
	Embeds          []Embed             `json:"embeds"`
	AllowedMentions map[string][]string `json:"allowed_mentions"`
	Nonce           string              `json:"nonce,omitempty"`
	EnforceNonce    bool                `json:"enforce_nonce,omitempty"`
}

func Render(o Observation) Message {
	m := Message{Content: "Bed Wars roster", Embeds: []Embed{}, AllowedMentions: map[string][]string{"parse": {}}}
	if o.Phase == "ended" {
		m.Content += " · capture ended"
	}
	// A sample time avoids claiming that a disconnected/crashed client's roster is still live.
	m.Content += "\nLast observed: " + o.ObservedAt
	for _, team := range Teams {
		var names []string
		for _, p := range o.Players {
			if p.Team == team.ID {
				names = append(names, "`"+p.Name+"`")
			}
		}
		if len(names) == 0 {
			continue
		}
		sort.Strings(names)
		m.Embeds = append(m.Embeds, Embed{team.Name, strings.Join(names, "\n"), team.Color})
	}
	if len(o.Players) == 0 {
		m.Content += "\nWaiting for visible players."
	}
	return m
}

func equal(a, b Observation) bool {
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return string(x) == string(y)
}
