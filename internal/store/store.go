// Package store persists Hermes markdown pushes as durable, dated
// JSON records on the local filesystem.
package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Push is one markdown payload pushed by Hermes.
type Push struct {
	ID         string    `json:"id"`
	ReceivedAt time.Time `json:"received_at"`
	Source     string    `json:"source,omitempty"` // optional label
	Markdown   string    `json:"markdown"`
}

// PushStore is the persistence interface for pushes.
type PushStore interface {
	// Save persists p and returns it with ID/ReceivedAt filled in.
	Save(p *Push) (*Push, error)
	// Latest returns the most recent push, or an error if there are none.
	Latest() (*Push, error)
	// List returns up to limit pushes, newest first (limit <= 0 means 100).
	List(limit int) ([]Push, error)
	// Get fetches a push by ID.
	Get(id string) (*Push, error)
}

// ErrNotFound is returned when a push does not exist.
var ErrNotFound = fmt.Errorf("push not found")

var idRe = regexp.MustCompile(`^[a-f0-9]+-[a-f0-9]+$`)

// FileStore is the PushStore backed by one JSON file per push in dir.
type FileStore struct {
	dir string
}

// NewFileStore opens (creating if needed) a push store rooted at dir.
func NewFileStore(dir string) (*FileStore, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create push store dir %s: %w", dir, err)
	}
	return &FileStore{dir: dir}, nil
}

// Save persists a push.
func (s *FileStore) Save(p *Push) (*Push, error) {
	if strings.TrimSpace(p.Markdown) == "" {
		return nil, fmt.Errorf("markdown payload is empty")
	}
	if p.ID == "" {
		p.ID = newID()
	}
	if p.ReceivedAt.IsZero() {
		p.ReceivedAt = time.Now().UTC()
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return nil, err
	}
	tmp, err := os.CreateTemp(s.dir, ".push-*.tmp")
	if err != nil {
		return nil, err
	}
	name := p.ID + ".json"
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after rename succeeds
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return nil, err
	}
	if err := tmp.Close(); err != nil {
		return nil, err
	}
	final := filepath.Join(s.dir, name)
	if err := os.Rename(tmpName, final); err != nil {
		return nil, err
	}
	return p, nil
}

// Latest returns the newest push.
func (s *FileStore) Latest() (*Push, error) {
	all, err := s.all()
	if err != nil {
		return nil, err
	}
	if len(all) == 0 {
		return nil, ErrNotFound
	}
	return &all[0], nil
}

// List returns up to limit pushes, newest first.
func (s *FileStore) List(limit int) ([]Push, error) {
	if limit <= 0 {
		limit = 100
	}
	all, err := s.all()
	if err != nil {
		return nil, err
	}
	if len(all) > limit {
		all = all[:limit]
	}
	return all, nil
}

// Get fetches a push by ID.
func (s *FileStore) Get(id string) (*Push, error) {
	if !idRe.MatchString(id) {
		return nil, ErrNotFound
	}
	data, err := os.ReadFile(filepath.Join(s.dir, id+".json"))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	var p Push
	if err := json.Unmarshal(data, &p); err != nil {
		return nil, fmt.Errorf("corrupt push record %s: %w", id, err)
	}
	return &p, nil
}

// all reads every push record, newest first.
func (s *FileStore) all() ([]Push, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, err
	}
	var out []Push
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(s.dir, e.Name()))
		if err != nil {
			continue
		}
		var p Push
		if err := json.Unmarshal(data, &p); err != nil {
			continue // skip corrupt records
		}
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].ReceivedAt.Equal(out[j].ReceivedAt) {
			return out[i].ReceivedAt.After(out[j].ReceivedAt)
		}
		return out[i].ID > out[j].ID
	})
	return out, nil
}

// newID is a time-ordered ID: unix-nanos + 8 random hex chars.
func newID() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%d-%s", time.Now().UTC().UnixNano(), hex.EncodeToString(b))
}
