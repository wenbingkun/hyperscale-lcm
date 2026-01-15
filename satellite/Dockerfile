# Build stage
FROM golang:1.21-alpine AS build

WORKDIR /app

# Install dependencies
RUN apk add --no-cache git ca-certificates

# Copy go.mod and go.sum first for caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-w -s" -o satellite ./cmd/satellite

# Runtime stage
FROM alpine:3.19

WORKDIR /app

# Install CA certificates and Docker CLI
RUN apk add --no-cache ca-certificates docker-cli

# Copy binary
COPY --from=build /app/satellite .

# Copy certificates (mount at runtime)
# COPY certs/ /app/certs/

# Expose ports (if needed)
# EXPOSE 8081

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD pgrep satellite || exit 1

# Run the application
ENTRYPOINT ["./satellite"]
