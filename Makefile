# homdash build & run helpers.

.PHONY: all web run run-web test build clean

all: web build

## Build the React frontend into web/dist (served by the Go binary).
web:
	cd web && npm install && npm run build

## Build the Go binary.
build:
	go build -o bin/homdash ./cmd/homdash

## Run the backend (serves the built frontend from web/dist if present).
run: build
	./bin/homdash

## Start the Vite dev server on :5173 (proxies /api to :3000).
run-web:
	cd web && npm run dev

## Run all tests (Go unit tests + frontend typecheck/build).
test:
	go test ./...
	cd web && npm run build

clean:
	rm -rf bin web/dist
