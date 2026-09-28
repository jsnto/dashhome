package api

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

// ServeStatic serves the built React app (dist) with an SPA fallback when
// it exists. The API keeps working without it.
func ServeStatic(r *gin.Engine, dist string) {
	index := filepath.Join(dist, "index.html")
	if st, err := os.Stat(index); err != nil || st.IsDir() {
		r.NoRoute(func(c *gin.Context) {
			c.JSON(http.StatusNotFound, gin.H{
				"error":  "not_found",
				"detail": "no frontend build found; run `make web` (or: cd web && npm install && npm run build), or use the Vite dev server on :5173",
			})
		})
		return
	}

	r.NoRoute(func(c *gin.Context) {
		p := c.Request.URL.Path
		if strings.HasPrefix(p, "/api/") {
			c.JSON(http.StatusNotFound, gin.H{"error": "not_found"})
			return
		}
		clean := filepath.Clean(strings.TrimPrefix(p, "/"))
		if clean != "." && clean != "" && clean != ".." && !strings.HasPrefix(clean, "../") {
			if f, err := os.Stat(filepath.Join(dist, clean)); err == nil && !f.IsDir() {
				c.File(filepath.Join(dist, clean))
				return
			}
		}
		// SPA fallback: any other path gets index.html.
		c.File(index)
	})
}
