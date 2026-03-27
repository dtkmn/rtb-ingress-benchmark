# --- Stage 1: Build the Rust application ---
FROM rust:1.94-bullseye AS builder

# Install build dependencies for rdkafka
RUN apt-get update && apt-get install -y build-essential librdkafka-dev pkg-config && rm -rf /var/lib/apt/lists/*

# Create a new empty shell project
WORKDIR /usr/src/app
COPY . .

# Build the application in release mode
RUN cargo build --release

# --- Stage 2: Create the final, smaller image ---
FROM debian:bullseye-slim

# Install runtime dependencies for rdkafka and wget for health checks
RUN apt-get update && apt-get install -y librdkafka1 wget --no-install-recommends && rm -rf /var/lib/apt/lists/*

# Copy the compiled binary from the builder stage
COPY --from=builder /usr/src/app/target/release/rust-receiver /usr/local/bin/rust-receiver

# Set the entrypoint for the container
CMD ["rust-receiver"]
