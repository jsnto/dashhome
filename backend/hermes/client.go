// Package hermes is the Hermes chat API client. The request/response shape
// is isolated here so it is easy to adapt to the real Hermes service; see
// the README for the documented contract.
package hermes

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Message is one entry of a conversation.
type Message struct {
	Role    string `json:"role"` // "user" | "assistant"
	Content string `json:"content"`
}

// ChatRequest is the body sent to the Hermes chat endpoint.
type ChatRequest struct {
	// Message is the newest user message.
	Message string `json:"message"`
	// Conversation carries earlier turns, oldest first, so Hermes can
	// respond with context. Optional.
	Conversation []Message `json:"conversation,omitempty"`
}

// Chatter sends a chat message and returns Hermes's reply.
type Chatter interface {
	Chat(ctx context.Context, req ChatRequest) (string, error)
}

// Client is the HTTP implementation of Chatter.
type Client struct {
	BaseURL   string // e.g. https://hermes.example.com
	ChatPath  string // e.g. /v1/chat
	AuthName  string // request header name to send ("" = none)
	AuthValue string // full header value, e.g. "Bearer sk-..."
	HTTP      *http.Client
}

// NewClient builds a Hermes chat client.
func NewClient(baseURL, chatPath, authName, authValue string) *Client {
	if chatPath == "" {
		chatPath = "/v1/chat"
	}
	if !strings.HasPrefix(chatPath, "/") {
		chatPath = "/" + chatPath
	}
	return &Client{
		BaseURL:   strings.TrimRight(baseURL, "/"),
		ChatPath:  chatPath,
		AuthName:  authName,
		AuthValue: authValue,
		HTTP:      &http.Client{Timeout: 60 * time.Second},
	}
}

// chatResponse is the expected JSON response; fields are probed in order
// so lightly different Hermes shapes still work.
type chatResponse struct {
	Reply    string `json:"reply"`
	Message  string `json:"message"`
	Response string `json:"response"`
	Text     string `json:"text"`
}

// Chat POSTs the request to Hermes and returns the reply text.
func (c *Client) Chat(ctx context.Context, req ChatRequest) (string, error) {
	u, err := url.Parse(c.BaseURL + c.ChatPath)
	if err != nil {
		return "", fmt.Errorf("invalid hermes URL %q: %w", c.BaseURL, err)
	}

	body, err := json.Marshal(req)
	if err != nil {
		return "", err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if c.AuthName != "" && c.AuthValue != "" {
		httpReq.Header.Set(c.AuthName, c.AuthValue)
	}

	resp, err := c.HTTP.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("hermes request: %w", err)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", fmt.Errorf("read hermes response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("hermes returned status %d: %s", resp.StatusCode, snippet(data))
	}

	// Prefer a JSON object with a reply-ish field, fall back to plain text.
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) > 0 && trimmed[0] == '{' {
		var cr chatResponse
		if err := json.Unmarshal(trimmed, &cr); err == nil {
			if cr.Reply != "" {
				return cr.Reply, nil
			}
			if cr.Message != "" {
				return cr.Message, nil
			}
			if cr.Response != "" {
				return cr.Response, nil
			}
			if cr.Text != "" {
				return cr.Text, nil
			}
		}
	}
	if len(trimmed) == 0 {
		return "", fmt.Errorf("hermes returned an empty response")
	}
	return string(trimmed), nil
}

func snippet(data []byte) string {
	s := strings.TrimSpace(string(data))
	if len(s) > 300 {
		s = s[:300] + "..."
	}
	if s == "" {
		return "(empty body)"
	}
	return s
}
