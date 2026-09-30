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

// Message is one entry of a conversation. On the wire it is the OpenAI
// chat-completions message entry: {"role", "content"}.
type Message struct {
	Role    string `json:"role"` // "user" | "assistant"
	Content string `json:"content"`
}

// ChatRequest is the Hermes chat request: the current user message plus
// optional earlier conversation turns. dashhome's /api/hermes/chat takes
// this shape; the client forwards it as an OpenAI chat-completions body.
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
	ChatPath  string // e.g. /chat/completions
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

// chatCompletionsRequest is the OpenAI-compatible chat-completions body
// sent to the Hermes endpoint.
type chatCompletionsRequest struct {
	Messages []Message `json:"messages"`
}

// chatCompletionsResponse is the OpenAI-compatible chat-completions
// response envelope; the reply text is at choices[0].message.content.
type chatCompletionsResponse struct {
	Choices []chatCompletionsChoice `json:"choices"`
}

type chatCompletionsChoice struct {
	Message struct {
		Content string `json:"content"`
	} `json:"message"`
}

// Chat POSTs the request to Hermes and returns the reply text.
func (c *Client) Chat(ctx context.Context, req ChatRequest) (string, error) {
	u, err := url.Parse(c.BaseURL + c.ChatPath)
	if err != nil {
		return "", fmt.Errorf("invalid hermes URL %q: %w", c.BaseURL, err)
	}

	// OpenAI chat-completions body: conversation history (oldest first)
	// followed by the current user message as the final turn.
	messages := make([]Message, 0, len(req.Conversation)+1)
	messages = append(messages, req.Conversation...)
	messages = append(messages, Message{Role: "user", Content: req.Message})
	body, err := json.Marshal(chatCompletionsRequest{Messages: messages})
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

	// The OpenAI envelope carries the reply at choices[0].message.content;
	// a 2xx body without that path is an error, not a reply.
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) == 0 {
		return "", fmt.Errorf("hermes returned an empty response")
	}
	var cr chatCompletionsResponse
	if err := json.Unmarshal(trimmed, &cr); err != nil {
		return "", fmt.Errorf("hermes returned an invalid response: %v", err)
	}
	if len(cr.Choices) == 0 {
		return "", fmt.Errorf("hermes returned an empty response")
	}
	if cr.Choices[0].Message.Content == "" {
		return "", fmt.Errorf("hermes returned an empty response")
	}
	return cr.Choices[0].Message.Content, nil
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
