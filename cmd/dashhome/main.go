// dashhome is a home dashboard: Google Calendar, Home Assistant states,
// Hermes chat, and Hermes markdown pushes behind one Go process.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/jsnto/dashhome/backend/api"
	"github.com/jsnto/dashhome/backend/calendar"
	"github.com/jsnto/dashhome/backend/config"
	"github.com/jsnto/dashhome/backend/ha"
	"github.com/jsnto/dashhome/backend/hermes"
	"github.com/jsnto/dashhome/backend/store"
)

func main() {
	gin.SetMode(gin.ReleaseMode)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	ctx, stop := shutdownContext()
	defer stop()

	// All service integrations resolve their settings on use (the settings
	// UI value, then the environment value, then the documented default),
	// so a value changed in the settings UI takes effect without a restart.
	// Startup notes below reflect the settings as loaded.
	googleDep := calendar.NewProvider(cfg.GoogleSettings)
	if !googleDep.Configured() {
		log.Print("google calendar: not configured")
	}

	haClient := ha.NewClientSettings(cfg.HASettings)
	haSvc := haClient
	haDash := ha.NewDashClientSettings(cfg.HASettings)
	if !cfg.HAConfigured() {
		log.Print("home assistant: not configured")
	}

	hClient := hermes.NewClientSettings(cfg.HermesChatSettings)
	if !cfg.HermesConfigured() {
		log.Print("hermes chat: not configured")
	}

	pushes, err := store.NewFileStore(filepath.Join(cfg.DataDir, "hermes_pushes"))
	if err != nil {
		log.Fatalf("push store: %v", err)
	}

	srv := api.NewServer(cfg, googleDep, haClient, haSvc, haDash, hClient, pushes)
	engine := srv.Engine()
	api.ServeStatic(engine, filepath.Join("frontend", "dist"))

	addr := ":" + cfg.Port
	httpServer := &http.Server{Addr: addr, Handler: engine}
	go func() {
		log.Printf("dashhome listening on http://localhost%s (API under /api)", addr)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()
	<-ctx.Done()
	log.Print("shutting down...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		log.Printf("shutdown: %v", err)
	}
}

// shutdownContext returns a context cancelled on SIGINT/SIGTERM.
func shutdownContext() (context.Context, func()) {
	ctx, cancel := context.WithCancel(context.Background())
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		cancel()
	}()
	return ctx, func() { signal.Stop(sig); cancel() }
}
