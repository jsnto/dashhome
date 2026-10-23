package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// The shared board-layout golden vectors
// (frontend/test-fixtures/board-layout/): the same
// input/expected-output pairs the web (frontend/src/boardVectors.test.ts)
// and Android (BoardVectorTest.kt) suites assert against their ports of
// the board-layout domain. The Go server is the source of truth for the
// board-file rules: every expected output is what this package's
// normalization produces (see the directory's README for the per-file
// contract and which suite asserts which group).

// boardVectorsDir locates the shared fixture directory: go test runs
// from the package directory, so walk up to the repository root.
func boardVectorsDir(t *testing.T) string {
	t.Helper()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		dir := filepath.Join(wd, "frontend", "test-fixtures", "board-layout")
		if st, err := os.Stat(filepath.Join(dir, "grid.json")); err == nil && !st.IsDir() {
			return dir
		}
		parent := filepath.Dir(wd)
		if parent == wd {
			t.Fatal("the board-layout fixtures were not found (run from the repository checkout)")
		}
		wd = parent
	}
}

func loadVector(t *testing.T, name string, into any) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(boardVectorsDir(t), name))
	if err != nil {
		t.Fatalf("read %s: %v", name, err)
	}
	if err := json.Unmarshal(data, into); err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}
}

// TestBoardVectorGrid pins normalizeGrid / BoardGrid.effective: a stored
// grid repaired to valid dimensions (per-axis fallback to the default).
func TestBoardVectorGrid(t *testing.T) {
	var f struct {
		Vectors []struct {
			Name  string `json:"name"`
			Input *struct {
				Cols int `json:"cols"`
				Rows int `json:"rows"`
			} `json:"input"`
			Expected struct {
				Cols int `json:"cols"`
				Rows int `json:"rows"`
			} `json:"expected"`
		} `json:"vectors"`
	}
	loadVector(t, "grid.json", &f)
	if len(f.Vectors) == 0 {
		t.Fatal("grid.json: no vectors")
	}
	for _, v := range f.Vectors {
		t.Run(v.Name, func(t *testing.T) {
			var g *BoardGrid
			if v.Input != nil {
				g = &BoardGrid{Cols: v.Input.Cols, Rows: v.Input.Rows}
			}
			cols, rows := g.effective()
			if cols != v.Expected.Cols || rows != v.Expected.Rows {
				t.Errorf("grid %v = %dx%d, want %dx%d", v.Input, cols, rows, v.Expected.Cols, v.Expected.Rows)
			}
		})
	}
}

// TestBoardVectorGeometry pins normalizeGeometry: stored placements
// repaired to in-bounds, overlap-free entries (board order wins an
// overlap; unknown IDs and unknown units are dropped).
func TestBoardVectorGeometry(t *testing.T) {
	var f struct {
		Vectors []struct {
			Name     string                     `json:"name"`
			Cols     int                        `json:"cols"`
			Rows     int                        `json:"rows"`
			Order    []string                   `json:"order"`
			IDs      []string                   `json:"ids"`
			Raw      map[string]json.RawMessage `json:"raw"`
			Expected GeometryMap                `json:"expected"`
		} `json:"vectors"`
	}
	loadVector(t, "geometry.json", &f)
	if len(f.Vectors) == 0 {
		t.Fatal("geometry.json: no vectors")
	}
	for _, v := range f.Vectors {
		t.Run(v.Name, func(t *testing.T) {
			// The raw map goes through the board file's own tolerant
			// decode (unknown units dropped entry-wise), the same
			// boundary the web/Android suites apply to the same JSON.
			rawData, err := json.Marshal(v.Raw)
			if err != nil {
				t.Fatalf("encode raw: %v", err)
			}
			raw := GeometryMap{}
			if err := json.Unmarshal(rawData, &raw); err != nil {
				t.Fatalf("decode raw: %v", err)
			}
			known := make(map[string]bool, len(v.IDs))
			for _, id := range v.IDs {
				known[id] = true
			}
			got := normalizeGeometry(raw, v.Order, known, v.Cols, v.Rows)
			// Normalize to the named map type: DeepEqual needs identical
			// types (the named GeometryMap and its underlying map type are
			// not identical to reflect).
			if !reflect.DeepEqual(GeometryMap(got), v.Expected) {
				t.Errorf("normalizeGeometry = %v, want %v", got, v.Expected)
			}
		})
	}
}

// TestBoardVectorViews pins normalizeViews over a raw board file: the
// freehand-editable surface repaired to the effective layout the server
// answers with (boardJSON -> ViewConfig).
func TestBoardVectorViews(t *testing.T) {
	var f struct {
		Vectors []struct {
			Name     string `json:"name"`
			Input    Board  `json:"input"`
			Expected struct {
				Grid  BoardGrid `json:"grid"`
				Views Views     `json:"views"`
			} `json:"expected"`
		} `json:"vectors"`
	}
	loadVector(t, "views.json", &f)
	if len(f.Vectors) == 0 {
		t.Fatal("views.json: no vectors")
	}
	for _, v := range f.Vectors {
		t.Run(v.Name, func(t *testing.T) {
			cols, rows := v.Input.Grid.effective()
			if cols != v.Expected.Grid.Cols || rows != v.Expected.Grid.Rows {
				t.Errorf("grid = %dx%d, want %dx%d", cols, rows, v.Expected.Grid.Cols, v.Expected.Grid.Rows)
			}
			got := normalizeViews(v.Input.Views, cols, rows)
			if !reflect.DeepEqual(got, v.Expected.Views) {
				t.Errorf("normalizeViews = %+v, want %+v", got, v.Expected.Views)
			}
		})
	}
}

// TestBoardVectorHATileIDShape pins isHATileID: "ha-" plus 1-16 lowercase
// alphanumeric characters.
func TestBoardVectorHATileIDShape(t *testing.T) {
	var f struct {
		IDShape []struct {
			ID    string `json:"id"`
			Valid bool   `json:"valid"`
		} `json:"id_shape"`
	}
	loadVector(t, "ha-tiles.json", &f)
	for _, v := range f.IDShape {
		if got := isHATileID(v.ID); got != v.Valid {
			t.Errorf("isHATileID(%q) = %v, want %v", v.ID, got, v.Valid)
		}
	}
}

// TestBoardVectorHATilesNormalize pins normalizeHATiles: valid entries
// only (id shape, non-empty entity, one of the two kinds), duplicate IDs
// deduped (first wins).
func TestBoardVectorHATilesNormalize(t *testing.T) {
	var f struct {
		Normalize []struct {
			Name     string        `json:"name"`
			Input    []HABoardTile `json:"input"`
			Expected []HABoardTile `json:"expected"`
		} `json:"normalize"`
	}
	loadVector(t, "ha-tiles.json", &f)
	for _, v := range f.Normalize {
		t.Run(v.Name, func(t *testing.T) {
			got := normalizeHATiles(v.Input)
			if !reflect.DeepEqual(got, v.Expected) {
				t.Errorf("normalizeHATiles = %+v, want %+v", got, v.Expected)
			}
		})
	}
}

// TestBoardVectorNextHATileID pins nextHATileID: the next free "ha-" + n
// ID (the first-free rule the clients use when adding a tile).
func TestBoardVectorNextHATileID(t *testing.T) {
	var f struct {
		NextID []struct {
			Name     string        `json:"name"`
			Tiles    []HABoardTile `json:"tiles"`
			Expected string        `json:"expected"`
		} `json:"next_id"`
	}
	loadVector(t, "ha-tiles.json", &f)
	for _, v := range f.NextID {
		t.Run(v.Name, func(t *testing.T) {
			if got := nextHATileID(v.Tiles); got != v.Expected {
				t.Errorf("nextHATileID = %q, want %q", got, v.Expected)
			}
		})
	}
}

// TestBoardVectorServerLabelFallback pins the server's label rule
// (HAEntityLabel): the stored label as-is (no trim - a blank label is
// kept, not trimmed away), else the entity's own label, else the entity
// id. This is the source of truth the client tileName copies converge on
// (the display_names group, asserted by the web/Android suites).
func TestBoardVectorServerLabelFallback(t *testing.T) {
	var f struct {
		ServerLabelFallback []struct {
			EntityID string `json:"entity_id"`
			Label    string `json:"label"`
			Friendly string `json:"friendly"`
			Expected string `json:"expected"`
		} `json:"server_label_fallback"`
	}
	loadVector(t, "ha-tiles.json", &f)
	for _, v := range f.ServerLabelFallback {
		t.Run(v.EntityID, func(t *testing.T) {
			cfg := &Config{Board: Board{HALabels: map[string]string{v.EntityID: v.Label}}}
			if got := cfg.HAEntityLabel(v.EntityID, v.Friendly); got != v.Expected {
				t.Errorf("HAEntityLabel = %q, want %q", got, v.Expected)
			}
		})
	}
}

// TestBoardVectorStepVocabulary pins the server's side of the size-step
// vocabulary: the accepted step set is exactly the fixture's steps (the
// web/Android suites assert the labels, fractions, and the stepWidth
// widths from the same file).
func TestBoardVectorStepVocabulary(t *testing.T) {
	var f struct {
		Steps []struct {
			Step  string `json:"step"`
			Label string `json:"label"`
		} `json:"steps"`
		Rejects []string `json:"rejects"`
	}
	loadVector(t, "step-vocabulary.json", &f)
	if len(f.Steps) != len(viewSizes) {
		t.Fatalf("fixture lists %d steps, the server knows %d", len(f.Steps), len(viewSizes))
	}
	for _, v := range f.Steps {
		if !isViewSize(v.Step) {
			t.Errorf("the server rejects step %q, want accepted", v.Step)
		}
	}
	for _, r := range f.Rejects {
		if isViewSize(r) {
			t.Errorf("the server accepts %q, want rejected (outside the vocabulary)", r)
		}
	}
}
