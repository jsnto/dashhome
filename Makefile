# dashhome build & run helpers.

# Pinned golangci-lint release, run inside the golang:1.26-alpine image
# (the asset filename drops the leading "v" of the release tag).
GOLANGCI_LINT_VERSION ?= 2.15.0

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

## Run all tests (Go unit tests + golangci-lint + frontend typecheck/build +
## frontend unit tests + frontend lint).
test:
	go test ./...
	docker run --rm -v "$PWD":/src -w /src golang:1.26-alpine sh -c "wget -qO /tmp/gcl.tgz https://github.com/golangci/golangci-lint/releases/download/v$(GOLANGCI_LINT_VERSION)/golangci-lint-$(GOLANGCI_LINT_VERSION)-linux-amd64.tar.gz && tar -xzf /tmp/gcl.tgz -C /tmp && /tmp/golangci-lint-$(GOLANGCI_LINT_VERSION)-linux-amd64/golangci-lint run"
	cd frontend && npm run build
	cd frontend && npm test
	cd frontend && npm run lint

clean:
	rm -rf bin frontend/dist
