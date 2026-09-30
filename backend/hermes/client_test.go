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

// live400Body is the exact error body the live Hermes endpoint returns for a
// request with a missing or invalid "messages" field.
const live400Body = `{"error": {"message": "Missing or invalid 'messages' field", "type": "invalid_request_error"}}`

// wireRequest is the OpenAI chat-completions body as seen on the wire.
type wireRequest struct {
	Messages []Message `json:"messages"`
}

func TestChatOpenAIEnvelope(t *testing.T) {
	var gotBody []byte
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" {
			t.Errorf("path = %s", r.URL.Path)
		}
		gotAuth = r.Header.Get("Authorization")
		gotBody, _ = io.ReadAll(r.Body)
		// Emulate the live endpoint: the old body shape must 400 with the
		// exact upstream error, the new shape gets the OpenAI envelope.
		var req wireRequest
		if err := json.Unmarshal(gotBody, &req); err != nil || len(req.Messages) == 0 {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(live400Body))
			return
		}
		last := req.Messages[len(req.Messages)-1]
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"id":     "chatcmpl-test",
			"object": "chat.completion",
			"choices": []map[string]any{{
				"index":         0,
				"message":       map[string]string{"role": "assistant", "content": "echo: " + last.Content},
				"finish_reason": "stop",
			}},
		})
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "/chat/completions", "Authorization", "Bearer sk-test")
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
	if reply != "echo: hi" {
		t.Errorf("reply = %q", reply)
	}
	if gotAuth != "Bearer sk-test" {
		t.Errorf("auth header = %q", gotAuth)
	}

	var body map[string]any
	if err := json.Unmarshal(gotBody, &body); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	if _, ok := body["message"]; ok {
		t.Errorf("body still carries legacy \"message\" field: %s", gotBody)
	}
	if _, ok := body["conversation"]; ok {
		t.Errorf("body still carries legacy \"conversation\" field: %s", gotBody)
	}
	var req wireRequest
	if err := json.Unmarshal(gotBody, &req); err != nil {
		t.Fatalf("body not chat-completions JSON: %v", err)
	}
	want := []Message{
		{Role: "user", Content: "earlier"},
		{Role: "assistant", Content: "earlier reply"},
		{Role: "user", Content: "hi"},
	}
	if len(req.Messages) != len(want) {
		t.Fatalf("messages = %d entries, want %d: %s", len(req.Messages), len(want), gotBody)
	}
	for i := range want {
		if req.Messages[i] != want[i] {
			t.Errorf("messages[%d] = %+v, want %+v (history first, current user message last)", i, req.Messages[i], want[i])
		}
	}
}

func TestChatCustomAuthHeader(t *testing.T) {
	var gotKey string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotKey = r.Header.Get("X-Hermes-Key")
		w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"ok"}}]}`))
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

func TestChatBadRequestPassthrough(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(live400Body))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, "/chat/completions", "", "")
	_, err := c.Chat(context.Background(), ChatRequest{Message: "m"})
	if err == nil {
		t.Fatal("expected error for status 400")
	}
	if want := "hermes returned status 400: " + live400Body; err.Error() != want {
		t.Errorf("err = %q, want %q", err.Error(), want)
	}
}

func TestChatMissingOrEmptyChoices(t *testing.T) {
	cases := map[string]string{
		"no choices key":  `{"id":"x","object":"chat.completion"}`,
		"empty choices":   `{"choices":[]}`,
		"empty content":   `{"choices":[{"message":{"role":"assistant","content":""}}]}`,
		"missing content": `{"choices":[{"message":{"role":"assistant"}}]}`,
		"plain text body": `just some text`,
	}
	for name, body := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(body))
		}))
		if _, err := NewClient(srv.URL, "/chat/completions", "", "").Chat(context.Background(), ChatRequest{Message: "m"}); err == nil {
			t.Errorf("%s: expected error for 200 body %s", name, body)
		} else if strings.Contains(body, `{"choices"`) && !strings.Contains(err.Error(), "hermes returned an empty response") {
			t.Errorf("%s: err = %v, want the empty-response error style", name, err)
		}
		srv.Close()
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
