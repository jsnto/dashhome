# dashhome build & run helpers.

.PHONY: all web run run-web test build clean

all: web build

## Build the React frontend into frontend/dist (served by the Go binary).
web:
	cd frontend && npm install && npm run build

## Build the Go binary.
build:
	go build -o bin/dashhome ./cmd/dashhome

## Run the backend (serves the built frontend from frontend/dist if present).
run: build
	./bin/dashhome

## Start the Vite dev server on :5173 (proxies /api to :3000).
run-web:
	cd frontend && npm run dev

## Run all tests (Go unit tests + frontend typecheck/build + frontend unit tests).
test:
	go test ./...
	cd frontend && npm run build
	cd frontend && npm test

clean:
	rm -rf bin frontend/dist
