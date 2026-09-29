# homdash — single-container dashboard.
#
# Three stages:
#   web  — Node builds the React frontend into web/dist
#   go   — Go builds the static binary
#   final— small image carrying both (the binary serves web/dist from its
#          working directory)

# --- web: build the React frontend ----------------------------------------
FROM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# --- go: build the static binary -------------------------------------------
FROM golang:1.26-alpine AS go
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/homdash ./cmd/homdash

# --- final: runtime ---------------------------------------------------------
FROM alpine:3.22
WORKDIR /app
COPY --from=go /out/homdash ./homdash
COPY --from=web /src/web/dist ./web/dist
RUN addgroup -S homdash && adduser -S -G homdash homdash \
    && mkdir -p /app/data \
    && chown -R homdash:homdash /app
USER homdash
ENV PORT=3000
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 || exit 1
CMD ["/app/homdash"]
