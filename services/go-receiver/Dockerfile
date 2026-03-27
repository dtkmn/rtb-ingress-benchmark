# --- Stage 1: Build ---
# Use the official Golang Alpine image as our builder
FROM golang:1.26-alpine AS builder

# Set the working directory inside the container
WORKDIR /src

# Copy the go.mod and go.sum files first
# This lets Docker cache our dependencies
COPY go.mod go.sum ./
RUN go mod download

# Copy the rest of the source code
COPY cmd/ ./cmd
COPY internal/ ./internal

# Build the application, creating a static binary
# CGO_ENABLED=0 is critical for creating a static binary that
# can run in a minimal 'alpine' or 'scratch' image.
RUN CGO_ENABLED=0 go build -o /app/receiver ./cmd/receiver/main.go

# --- Stage 2: Final Image ---
# Use a minimal Alpine image for the final, tiny container
FROM alpine:latest

# Install wget for health checks
RUN apk add --no-cache wget

# Only copy the compiled binary from the 'builder' stage
COPY --from=builder /app/receiver /receiver

# Expose port 8080 (which 'main.go' listens on)
EXPOSE 8080

# The command to run when the container starts
ENTRYPOINT ["/receiver"]
