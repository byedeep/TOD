package roster

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"
)

type Discord struct {
	Token, ChannelID, BaseURL string
	Client                    *http.Client
}

type DeliveryError struct {
	Status     int
	RetryAfter time.Duration
}

func (e *DeliveryError) Error() string { return fmt.Sprintf("Discord HTTP %d", e.Status) }

func (d *Discord) Send(ctx context.Context, e Entry) (string, time.Duration, error) {
	base := d.BaseURL
	if base == "" {
		base = "https://discord.com/api/v10"
	}
	method := http.MethodPost
	url := base + "/channels/" + d.ChannelID + "/messages"
	m := Render(e.Observation)
	if e.MessageID != "" {
		method = http.MethodPatch
		url += "/" + e.MessageID
	} else {
		hash := sha256.Sum256([]byte(e.Observation.key()))
		m.Nonce = hex.EncodeToString(hash[:12])
		m.EnforceNonce = true
	}
	body, _ := json.Marshal(m)
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(body))
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Authorization", "Bot "+d.Token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "DiscordBot (https://github.com/discord/discord-api-docs, 0.2.0)")
	client := d.Client
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	res, err := client.Do(req)
	if err != nil {
		return "", 0, fmt.Errorf("Discord request failed (%T)", err)
	} // Never print a credential or response body.
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, 1024*1024))
	if err != nil {
		return "", 0, fmt.Errorf("Discord response read failed")
	}
	wait := time.Duration(0)
	if res.Header.Get("X-RateLimit-Remaining") == "0" {
		wait = seconds(res.Header.Get("X-RateLimit-Reset-After"))
	}
	if res.StatusCode == 429 {
		var rate struct {
			RetryAfter float64 `json:"retry_after"`
		}
		_ = json.Unmarshal(b, &rate)
		wait = max(wait, seconds(res.Header.Get("Retry-After")), time.Duration(rate.RetryAfter*float64(time.Second)), time.Second)
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return "", wait, &DeliveryError{res.StatusCode, wait}
	}
	var message struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(b, &message) != nil || !snowflake.MatchString(message.ID) {
		return "", wait, fmt.Errorf("Discord returned an invalid message ID")
	}
	return message.ID, wait, nil
}
func seconds(s string) time.Duration {
	f, _ := strconv.ParseFloat(s, 64)
	if f <= 0 || f > 86400 {
		return 0
	}
	return time.Duration(f * float64(time.Second))
}

// Routine edits share one three-second lane; Discord outages never block ingest.
func Deliver(ctx context.Context, store *Store, discord *Discord, logger *log.Logger) {
	delay := time.Second
	backoff := 3 * time.Second
	for {
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		delay = time.Second
		pending := store.Pending()
		if len(pending) == 0 {
			continue
		}
		e := pending[0]
		id, wait, err := discord.Send(ctx, e)
		delay = max(3*time.Second, wait)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logger.Printf("Roster delivery pending: %v", err)
			if api, ok := err.(*DeliveryError); ok {
				if api.Status == 404 && e.MessageID != "" {
					if err := store.ForgetMessage(e.Observation); err != nil {
						logger.Printf("Cannot persist replacement binding: %v", err)
					}
				}
				if api.Status == 401 || api.Status == 403 {
					delay = max(delay, time.Minute)
				}
			}
			delay = max(delay, backoff)
			backoff = min(backoff*2, time.Minute)
			continue
		}
		if e.MessageID == "" {
			if err := store.Bind(e.Observation, id); err != nil {
				logger.Printf("Cannot persist Discord binding: %v", err)
			}
			continue
		}
		if err := store.Delivered(e.Observation, id); err != nil {
			logger.Printf("Cannot persist Discord acknowledgement: %v", err)
			continue
		}
		backoff = 3 * time.Second
		logger.Print("Discord roster updated")
	}
}
