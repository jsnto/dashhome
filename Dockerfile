# dashhome — single-container dashboard.
#
# Three stages:
#   frontend — Node builds the React frontend into frontend/dist
#   go       — Go builds the static binary
#   final    — small image carrying both (the binary serves frontend/dist
#              from its working directory)

# --- frontend: build the React frontend ------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

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
COPY --from=frontend /src/frontend/dist ./frontend/dist
RUN addgroup -S dashhome && adduser -S -G dashhome dashhome \
    && mkdir -p /app/data \
    && chown -R dashhome:dashhome /app
USER dashhome
ENV PORT=3000
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 || exit 1
CMD ["/app/dashhome"]
