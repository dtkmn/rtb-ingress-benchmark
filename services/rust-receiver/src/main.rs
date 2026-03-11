use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use lazy_static::lazy_static;
use prometheus::{Encoder, Histogram, HistogramOpts, IntCounter, Registry, TextEncoder};
use rdkafka::config::ClientConfig;
use rdkafka::producer::{FutureProducer, FutureRecord};
use serde::{Deserialize, Serialize};
use std::env;
use std::num::NonZeroUsize;
use std::time::Duration;

// Prometheus metrics
lazy_static! {
    static ref REGISTRY: Registry = Registry::new();
    static ref REQUESTS_TOTAL: IntCounter = IntCounter::new(
        "rust_receiver_requests_total",
        "Total number of requests received"
    )
    .expect("metric can be created");
    static ref REQUESTS_ACCEPTED: IntCounter = IntCounter::new(
        "rust_receiver_requests_accepted_total",
        "Total number of accepted requests"
    )
    .expect("metric can be created");
    static ref REQUESTS_REJECTED: IntCounter = IntCounter::new(
        "rust_receiver_requests_rejected_total",
        "Total number of rejected requests"
    )
    .expect("metric can be created");
    static ref REQUEST_DURATION: Histogram = Histogram::with_opts(HistogramOpts::new(
        "rust_receiver_request_duration_seconds",
        "Request duration in seconds"
    ))
    .expect("metric can be created");
}

fn register_metrics() {
    REGISTRY
        .register(Box::new(REQUESTS_TOTAL.clone()))
        .expect("collector can be registered");
    REGISTRY
        .register(Box::new(REQUESTS_ACCEPTED.clone()))
        .expect("collector can be registered");
    REGISTRY
        .register(Box::new(REQUESTS_REJECTED.clone()))
        .expect("collector can be registered");
    REGISTRY
        .register(Box::new(REQUEST_DURATION.clone()))
        .expect("collector can be registered");
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DeliveryMode {
    Confirm,
    Enqueue,
    HttpOnly,
}

impl DeliveryMode {
    fn from_env() -> Self {
        Self::from_raw(
            &env::var("BENCHMARK_DELIVERY_MODE").unwrap_or_else(|_| "confirm".to_string()),
        )
    }

    fn from_raw(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "http-only" => DeliveryMode::HttpOnly,
            "enqueue" => DeliveryMode::Enqueue,
            "confirm" => DeliveryMode::Confirm,
            other => {
                eprintln!("Unknown BENCHMARK_DELIVERY_MODE={other:?}; defaulting to \"confirm\"");
                DeliveryMode::Confirm
            }
        }
    }

    fn uses_kafka(self) -> bool {
        self != DeliveryMode::HttpOnly
    }
}

#[derive(Clone)]
struct AppState {
    producer: Option<FutureProducer>,
    delivery_mode: DeliveryMode,
}

// --- Data Models (mirroring the Go service) ---
#[derive(Serialize, Deserialize, Debug)]
struct BidRequest {
    id: String,
    site: Option<BidSite>,
    app: Option<BidApp>,
    device: Option<BidDevice>,
    user: Option<BidUser>,
}

#[derive(Serialize, Deserialize, Debug)]
struct BidSite {
    id: Option<String>,
    domain: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct BidApp {
    bundle: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct BidDevice {
    ip: Option<String>,
    os: Option<String>,
    lmt: i32,
    ua: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
struct BidUser {
    id: String,
}

fn is_valid_bid_request(bid_request: &BidRequest) -> bool {
    !bid_request.id.is_empty()
        && bid_request.device.is_some()
        && (bid_request.site.is_some() || bid_request.app.is_some())
}

fn kafka_acks_from_env() -> String {
    match env::var("BENCHMARK_KAFKA_ACKS")
        .unwrap_or_else(|_| "1".to_string())
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "" | "1" | "leader" => "1".to_string(),
        "0" | "none" => "0".to_string(),
        "-1" | "all" => "all".to_string(),
        other => {
            eprintln!(
                "Unknown BENCHMARK_KAFKA_ACKS={other:?}; defaulting to leader acknowledgements"
            );
            "1".to_string()
        }
    }
}

fn parse_positive_u32_env(env_name: &str, fallback: u32) -> u32 {
    if let Ok(raw) = env::var(env_name) {
        if let Ok(parsed) = raw.trim().parse::<u32>() {
            if parsed > 0 {
                return parsed;
            }
        }
        eprintln!("Ignoring invalid {env_name}={raw:?}");
    }

    fallback
}

fn http_workers_from_env() -> usize {
    if let Ok(raw) = env::var("HTTP_SERVER_WORKERS") {
        if let Ok(parsed) = raw.trim().parse::<NonZeroUsize>() {
            return parsed.get();
        }
        eprintln!("Ignoring invalid HTTP_SERVER_WORKERS={raw:?}");
    }

    std::thread::available_parallelism()
        .map(NonZeroUsize::get)
        .unwrap_or(1)
}

// --- Actix Web Handler for POST /bid-request ---
async fn receive_bid(
    bid_request: web::Json<BidRequest>,
    state: web::Data<AppState>,
) -> impl Responder {
    let timer = REQUEST_DURATION.start_timer();
    REQUESTS_TOTAL.inc();

    // --- STAGE 1: FAST VALIDATION ---
    if !is_valid_bid_request(&bid_request) {
        REQUESTS_REJECTED.inc();
        timer.observe_duration();
        return HttpResponse::BadRequest().json(serde_json::json!({"status": "bad request"}));
    }

    // --- STAGE 2: SIMPLE BUSINESS FILTERING ---
    if let Some(device) = &bid_request.device {
        if device.lmt == 1 {
            REQUESTS_REJECTED.inc();
            timer.observe_duration();
            return HttpResponse::NoContent().finish();
        }
        if let Some(ip) = &device.ip {
            if ip.starts_with("10.10.") {
                REQUESTS_REJECTED.inc();
                timer.observe_duration();
                return HttpResponse::NoContent().finish();
            }
        }
    }

    if state.delivery_mode == DeliveryMode::HttpOnly {
        REQUESTS_ACCEPTED.inc();
        timer.observe_duration();
        return HttpResponse::Ok().json(serde_json::json!({"status": "accepted"}));
    }

    // --- STAGE 3: PUSH TO KAFKA & ACKNOWLEDGE ---
    let payload = match serde_json::to_string(&bid_request.into_inner()) {
        Ok(p) => p,
        Err(_) => {
            REQUESTS_REJECTED.inc();
            timer.observe_duration();
            return HttpResponse::InternalServerError()
                .json(serde_json::json!({"status": "serialization error"}));
        }
    };

    let record: FutureRecord<String, String> = FutureRecord::to("bids").payload(&payload);
    let Some(producer) = state.producer.as_ref() else {
        REQUESTS_REJECTED.inc();
        timer.observe_duration();
        return HttpResponse::ServiceUnavailable()
            .json(serde_json::json!({"status": "kafka unavailable"}));
    };

    if state.delivery_mode == DeliveryMode::Enqueue {
        match producer.send_result(record) {
            Ok(_) => {
                REQUESTS_ACCEPTED.inc();
                timer.observe_duration();
                return HttpResponse::Ok().json(serde_json::json!({"status": "accepted"}));
            }
            Err((e, _)) => {
                eprintln!("Kafka enqueue error: {:?}", e);
                REQUESTS_REJECTED.inc();
                timer.observe_duration();
                return HttpResponse::ServiceUnavailable()
                    .json(serde_json::json!({"status": "kafka unavailable"}));
            }
        }
    }

    match producer.send(record, Duration::from_secs(0)).await {
        Ok(_) => {
            REQUESTS_ACCEPTED.inc();
            timer.observe_duration();
            HttpResponse::Ok().json(serde_json::json!({"status": "accepted"}))
        }
        Err(e) => {
            eprintln!("Kafka write error: {:?}", e);
            REQUESTS_REJECTED.inc();
            timer.observe_duration();
            HttpResponse::ServiceUnavailable()
                .json(serde_json::json!({"status": "kafka buffer full"}))
        }
    }
}

// --- Health check handler ---
async fn health_check() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({"status": "healthy"}))
}

// --- Metrics endpoint handler ---
async fn metrics() -> impl Responder {
    let mut buffer = vec![];
    let encoder = TextEncoder::new();
    let metric_families = REGISTRY.gather();
    encoder.encode(&metric_families, &mut buffer).unwrap();

    HttpResponse::Ok()
        .content_type("text/plain; version=0.0.4")
        .body(buffer)
}

// --- Main Application Entry Point ---
#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Get Kafka URL from environment or use a default
    let kafka_url =
        env::var("KAFKA_BOOTSTRAP_SERVERS").unwrap_or_else(|_| "localhost:9092".to_string());
    let delivery_mode = DeliveryMode::from_env();
    let kafka_acks = kafka_acks_from_env();
    let workers = http_workers_from_env();
    let kafka_linger_ms = parse_positive_u32_env("BENCHMARK_KAFKA_LINGER_MS", 10);
    let kafka_batch_bytes = parse_positive_u32_env("BENCHMARK_KAFKA_BATCH_BYTES", 131072);
    let kafka_request_timeout_ms = parse_positive_u32_env("BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", 5000);
    println!(
        "Connecting to Kafka at {} (delivery_mode={:?}, acks={}, workers={}, linger_ms={}, batch_bytes={}, request_timeout_ms={})",
        kafka_url, delivery_mode, kafka_acks, workers, kafka_linger_ms, kafka_batch_bytes, kafka_request_timeout_ms
    );

    let producer = if delivery_mode.uses_kafka() {
        Some(
            ClientConfig::new()
                .set("bootstrap.servers", &kafka_url)
                .set("linger.ms", kafka_linger_ms.to_string())
                .set("batch.size", kafka_batch_bytes.to_string())
                .set("request.timeout.ms", kafka_request_timeout_ms.to_string())
                .set(
                    "message.timeout.ms",
                    (kafka_request_timeout_ms + kafka_linger_ms.max(1000)).to_string(),
                )
                .set("acks", &kafka_acks)
                .set("queue.buffering.max.messages", "1000000")
                .create()
                .expect("Producer creation error"),
        )
    } else {
        println!("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
        None
    };

    let state = AppState {
        producer,
        delivery_mode,
    };

    // Register Prometheus metrics
    register_metrics();

    println!("Starting Rust AdTech Receiver on port 8080...");

    // Start the Actix web server
    HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(state.clone()))
            .route("/bid-request", web::post().to(receive_bid))
            .route("/health", web::get().to(health_check))
            .route("/metrics", web::get().to(metrics))
    })
    .workers(workers)
    .bind("0.0.0.0:8080")?
    .run()
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_with_site_or_app_is_valid() {
        let app_request = BidRequest {
            id: "bid-1".to_string(),
            site: None,
            app: Some(BidApp {
                bundle: "com.example.app".to_string(),
            }),
            device: Some(BidDevice {
                ip: None,
                os: None,
                lmt: 0,
                ua: None,
            }),
            user: None,
        };

        assert!(is_valid_bid_request(&app_request));
    }

    #[test]
    fn request_without_site_or_app_is_invalid() {
        let invalid_request = BidRequest {
            id: "bid-2".to_string(),
            site: None,
            app: None,
            device: Some(BidDevice {
                ip: None,
                os: None,
                lmt: 0,
                ua: None,
            }),
            user: None,
        };

        assert!(!is_valid_bid_request(&invalid_request));
    }

    #[test]
    fn http_only_delivery_mode_is_supported() {
        assert_eq!(DeliveryMode::from_raw("http-only"), DeliveryMode::HttpOnly);
        assert!(!DeliveryMode::HttpOnly.uses_kafka());
    }
}
