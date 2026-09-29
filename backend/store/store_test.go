package store

import (
	"path/filepath"
	"testing"
	"time"
)

func TestSaveListLatestGet(t *testing.T) {
	s, err := NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if err != nil {
		t.Fatalf("NewFileStore: %v", err)
	}

	first := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	p1, err := s.Save(&Push{Markdown: "# first", ReceivedAt: first})
	if err != nil {
		t.Fatalf("Save: %v", err)
	}
	if p1.ID == "" {
		t.Error("Save should assign an ID")
	}

	p2, err := s.Save(&Push{Markdown: "# second", Source: "hermes", ReceivedAt: first.Add(time.Hour)})
	if err != nil {
		t.Fatalf("Save 2: %v", err)
	}

	lat, err := s.Latest()
	if err != nil {
		t.Fatalf("Latest: %v", err)
	}
	if lat.ID != p2.ID {
		t.Errorf("Latest = %s, want %s", lat.ID, p2.ID)
	}

	all, err := s.List(10)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("List len = %d, want 2", len(all))
	}
	if all[0].ID != p2.ID || all[1].ID != p1.ID {
		t.Errorf("List order wrong: %s then %s", all[0].ID, all[1].ID)
	}

	got, err := s.Get(p1.ID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got.Markdown != "# first" {
		t.Errorf("Get markdown = %q", got.Markdown)
	}
	if got.ReceivedAt.Equal(first) != true {
		t.Errorf("Get received_at = %v, want %v", got.ReceivedAt, first)
	}

	if _, err := s.Get("does-not-exist"); err != ErrNotFound {
		t.Errorf("Get missing = %v, want ErrNotFound", err)
	}
}

func TestSaveRejectsEmpty(t *testing.T) {
	s, _ := NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if _, err := s.Save(&Push{Markdown: "   "}); err == nil {
		t.Error("expected error saving empty markdown")
	}
}

func TestListLimit(t *testing.T) {
	s, _ := NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	base := time.Now().UTC()
	for i := 0; i < 5; i++ {
		if _, err := s.Save(&Push{Markdown: "x", ReceivedAt: base.Add(time.Duration(i) * time.Second)}); err != nil {
			t.Fatal(err)
		}
	}
	all, err := s.List(3)
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 3 {
		t.Fatalf("List(3) len = %d", len(all))
	}
	if !all[0].ReceivedAt.After(all[1].ReceivedAt) {
		t.Error("not newest first")
	}
}

func TestLatestEmpty(t *testing.T) {
	s, _ := NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if _, err := s.Latest(); err != ErrNotFound {
		t.Errorf("Latest on empty store = %v, want ErrNotFound", err)
	}
}

func TestGetRejectsTraversal(t *testing.T) {
	s, _ := NewFileStore(filepath.Join(t.TempDir(), "pushes"))
	if _, err := s.Get("../secrets"); err != ErrNotFound {
		t.Errorf("Get traversal = %v, want ErrNotFound", err)
	}
	if _, err := s.Get("a/b.json"); err != ErrNotFound {
		t.Errorf("Get path = %v, want ErrNotFound", err)
	}
}

func TestDurableAcrossReopen(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "pushes")
	s1, _ := NewFileStore(dir)
	p, err := s1.Save(&Push{Markdown: "# durable"})
	if err != nil {
		t.Fatal(err)
	}

	s2, err := NewFileStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	got, err := s2.Get(p.ID)
	if err != nil {
		t.Fatalf("Get after reopen: %v", err)
	}
	if got.Markdown != "# durable" {
		t.Errorf("markdown = %q", got.Markdown)
	}
}
