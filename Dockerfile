# dashhome backend — the Go API.
#
# Two stages:
#   go       — Go builds the static binary
#   final    — small image carrying just the binary
#
# The frontend is a separate image (frontend/Dockerfile: nginx serves the
# built React app and proxies /api/ to this service). The binary still
# serves frontend/dist when it happens to sit next to it (local dev via
# `make run`); without it the API keeps working and non-API paths answer
# a JSON 404 (see api.ServeStatic).

# --- go: build the static binary -------------------------------------------
FROM golang:1.26-alpine AS go
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/dashhome ./cmd/dashhome

# --- final: runtime ---------------------------------------------------------
FROM alpine:3.22
WORKDIR /app
COPY --from=go /out/dashhome ./dashhome
RUN addgroup -S dashhome && adduser -S -G dashhome dashhome \
    && mkdir -p /app/data \
    && chown -R dashhome:dashhome /app
USER dashhome
ENV PORT=3000
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 || exit 1
CMD ["/app/dashhome"]
