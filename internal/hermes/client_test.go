package hermes

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestChatJSONReply(t *testing.T) {
	var gotBody []byte
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s", r.Method)
		}
		if r.URL.Path != "/v1/chat" {
			t.Errorf("path = %s", r.URL.Path)
		}
		gotAuth = r.Header.Get("Authorization")
		gotBody, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"reply":"hello from hermes"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "/v1/chat", "Authorization", "Bearer sk-test")
	reply, err := c.Chat(context.Background(), ChatRequest{
		Message: "hi",
		Conversation: []Message{
			{Role: "user", Content: "earlier"},
			{Role: "assistant", Content: "earlier reply"},
		},
	})
	if err != nil {
		t.Fatalf("Chat: %v", err)
	}
	if reply != "hello from hermes" {
		t.Errorf("reply = %q", reply)
	}
	if gotAuth != "Bearer sk-test" {
		t.Errorf("auth header = %q", gotAuth)
	}
	var req ChatRequest
	if err := json.Unmarshal(gotBody, &req); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	if req.Message != "hi" || len(req.Conversation) != 2 || req.Conversation[0].Role != "user" {
		t.Errorf("body = %+v", req)
	}
}

func TestChatCustomAuthHeader(t *testing.T) {
	var gotKey string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotKey = r.Header.Get("X-Hermes-Key")
		w.Write([]byte(`{"message":"ok"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "chat", "X-Hermes-Key", "k123") // path without leading slash
	reply, err := c.Chat(context.Background(), ChatRequest{Message: "m"})
	if err != nil {
		t.Fatalf("Chat: %v", err)
	}
	if reply != "ok" {
		t.Errorf("reply = %q", reply)
	}
	if gotKey != "k123" {
		t.Errorf("X-Hermes-Key = %q", gotKey)
	}
}

func TestChatPlainTextReply(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.Write([]byte("plain reply"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "/chat", "", "")
	reply, err := c.Chat(context.Background(), ChatRequest{Message: "m"})
	if err != nil {
		t.Fatalf("Chat: %v", err)
	}
	if reply != "plain reply" {
		t.Errorf("reply = %q", reply)
	}
}

func TestChatUpstreamError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		w.Write([]byte("upstream down"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "/chat", "", "")
	_, err := c.Chat(context.Background(), ChatRequest{Message: "m"})
	if err == nil || !strings.Contains(err.Error(), "502") {
		t.Errorf("err = %v, want status 502 mentioned", err)
	}
}

func TestChatEmptyResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer srv.Close()

	c := NewClient(srv.URL, "/chat", "", "")
	if _, err := c.Chat(context.Background(), ChatRequest{Message: "m"}); err == nil {
		t.Error("expected error for empty response")
	}
}
