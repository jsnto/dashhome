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

	var cal *calendar.GoogleClient
	if cal, err = calendar.NewGoogle(ctx, cfg.Google); err != nil {
		log.Fatalf("google calendar: %v", err)
	}
	if cal == nil {
		log.Print("google calendar: not configured")
	}

	var haClient ha.StatesClient
	var haDash ha.DashboardsClient
	if cfg.HAConfigured() {
		haClient = ha.NewClient(cfg.HAURL, cfg.HAToken)
		haDash = ha.NewDashClient(cfg.HAURL, cfg.HAToken)
	} else {
		log.Print("home assistant: not configured")
	}

	var hClient hermes.Chatter
	if cfg.HermesConfigured() {
		hClient = hermes.NewClient(cfg.HermesBaseURL, cfg.HermesChatPath, cfg.HermesAuthHeader, cfg.HermesAuthValue)
	} else {
		log.Print("hermes chat: not configured")
	}

	pushes, err := store.NewFileStore(filepath.Join(cfg.DataDir, "hermes_pushes"))
	if err != nil {
		log.Fatalf("push store: %v", err)
	}

	var googleDep api.GoogleAPI
	if cal != nil {
		googleDep = cal
	}

	srv := api.NewServer(cfg, googleDep, haClient, haDash, hClient, pushes)
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
