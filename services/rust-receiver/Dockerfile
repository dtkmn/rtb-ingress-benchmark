# --- Stage 1: Build the Rust application ---
FROM rust:1.98-trixie AS builder

WORKDIR /usr/src/app
COPY . .

# rust-rdkafka builds and statically links its bundled librdkafka by default.
RUN cargo build --release --locked

FROM gcr.io/distroless/cc-debian13:nonroot

WORKDIR /app

COPY --from=builder --chown=nonroot:nonroot /usr/src/app/target/release/rust-receiver /app/rust-receiver

EXPOSE 8080

ENTRYPOINT ["/app/rust-receiver"]
