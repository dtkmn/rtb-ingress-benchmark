use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use lazy_static::lazy_static;
use prometheus::{Encoder, Histogram, HistogramOpts, IntCounter, Registry, TextEncoder};
use rdkafka::config::ClientConfig;
use rdkafka::producer::{FutureProducer, FutureRecord};
use serde::{Deserialize, Serialize};
use std::collections::hash_map::DefaultHasher;
use std::env;
use std::hash::{Hash, Hasher};
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
    producers: Vec<FutureProducer>,
    delivery_mode: DeliveryMode,
    topic: String,
    confirm_queue_timeout_ms: u32,
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

fn kafka_topic_from_env() -> String {
    match env::var("BENCHMARK_KAFKA_TOPIC") {
        Ok(raw) if !raw.trim().is_empty() => raw.trim().to_string(),
        _ => "bids".to_string(),
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

fn parse_non_negative_u32_env(env_name: &str, fallback: u32) -> u32 {
    if let Ok(raw) = env::var(env_name) {
        if let Ok(parsed) = raw.trim().parse::<u32>() {
            return parsed;
        }
        eprintln!("Ignoring invalid {env_name}={raw:?}");
    }

    fallback
}

fn compute_message_timeout_ms(
    request_timeout_ms: u32,
    linger_ms: u32,
    retries: u32,
    retry_backoff_ms: u32,
) -> u32 {
    let timeout = u64::from(request_timeout_ms) * u64::from(retries + 1)
        + u64::from(retry_backoff_ms) * u64::from(retries)
        + u64::from(linger_ms.max(1000));
    timeout.min(u64::from(u32::MAX)) as u32
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

fn build_record<'a>(topic: &'a str, key: &'a str, payload: &'a str) -> FutureRecord<'a, str, str> {
    FutureRecord::to(topic).payload(payload).key(key)
}

fn producer_index_for_key(key: &str, pool_size: usize) -> usize {
    if pool_size <= 1 {
        return 0;
    }

    let mut hasher = DefaultHasher::new();
    key.hash(&mut hasher);
    (hasher.finish() as usize) % pool_size
}

fn select_producer<'a>(producers: &'a [FutureProducer], key: &str) -> Option<&'a FutureProducer> {
    if producers.is_empty() {
        return None;
    }

    Some(&producers[producer_index_for_key(key, producers.len())])
}

fn build_producer(
    kafka_url: &str,
    kafka_acks: &str,
    kafka_linger_ms: u32,
    kafka_batch_bytes: u32,
    kafka_request_timeout_ms: u32,
    kafka_retries: u32,
    kafka_retry_backoff_ms: u32,
    producer_index: usize,
) -> FutureProducer {
    let client_id = format!("rust-receiver-{}", producer_index + 1);

    ClientConfig::new()
        .set("bootstrap.servers", kafka_url)
        .set("client.id", &client_id)
        .set("linger.ms", kafka_linger_ms.to_string())
        .set("batch.size", kafka_batch_bytes.to_string())
        .set("request.timeout.ms", kafka_request_timeout_ms.to_string())
        .set("retries", kafka_retries.to_string())
        .set("retry.backoff.ms", kafka_retry_backoff_ms.to_string())
        .set(
            "message.timeout.ms",
            compute_message_timeout_ms(
                kafka_request_timeout_ms,
                kafka_linger_ms,
                kafka_retries,
                kafka_retry_backoff_ms,
            )
            .to_string(),
        )
        .set("acks", kafka_acks)
        .set("queue.buffering.max.messages", "1000000")
        .create()
        .expect("Producer creation error")
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
    let bid_request = bid_request.into_inner();
    let request_key = bid_request.id.clone();
    let payload = match serde_json::to_string(&bid_request) {
        Ok(p) => p,
        Err(_) => {
            REQUESTS_REJECTED.inc();
            timer.observe_duration();
            return HttpResponse::InternalServerError()
                .json(serde_json::json!({"status": "serialization error"}));
        }
    };

    let record = build_record(&state.topic, &request_key, &payload);
    let Some(producer) = select_producer(&state.producers, &request_key) else {
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

    match producer
        .send(
            record,
            Duration::from_millis(u64::from(state.confirm_queue_timeout_ms)),
        )
        .await
    {
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
    let kafka_topic = kafka_topic_from_env();
    let delivery_mode = DeliveryMode::from_env();
    let kafka_acks = kafka_acks_from_env();
    let workers = http_workers_from_env();
    let kafka_linger_ms = parse_positive_u32_env("BENCHMARK_KAFKA_LINGER_MS", 10);
    let kafka_batch_bytes = parse_positive_u32_env("BENCHMARK_KAFKA_BATCH_BYTES", 131072);
    let kafka_request_timeout_ms =
        parse_positive_u32_env("BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", 5000);
    let kafka_retries = parse_non_negative_u32_env("BENCHMARK_KAFKA_RETRIES", 5);
    let kafka_retry_backoff_ms =
        parse_non_negative_u32_env("BENCHMARK_KAFKA_RETRY_BACKOFF_MS", 100);
    let kafka_producer_pool_size =
        parse_positive_u32_env("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", 1) as usize;
    println!(
        "Connecting to Kafka at {} topic {} (delivery_mode={:?}, acks={}, workers={}, producer_pool_size={}, linger_ms={}, batch_bytes={}, request_timeout_ms={}, retries={}, retry_backoff_ms={})",
        kafka_url, kafka_topic, delivery_mode, kafka_acks, workers, kafka_producer_pool_size, kafka_linger_ms, kafka_batch_bytes, kafka_request_timeout_ms, kafka_retries, kafka_retry_backoff_ms
    );

    let producers = if delivery_mode.uses_kafka() {
        (0..kafka_producer_pool_size)
            .map(|producer_index| {
                build_producer(
                    &kafka_url,
                    &kafka_acks,
                    kafka_linger_ms,
                    kafka_batch_bytes,
                    kafka_request_timeout_ms,
                    kafka_retries,
                    kafka_retry_backoff_ms,
                    producer_index,
                )
            })
            .collect()
    } else {
        println!("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
        Vec::new()
    };

    let state = AppState {
        producers,
        delivery_mode,
        topic: kafka_topic,
        confirm_queue_timeout_ms: kafka_request_timeout_ms,
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

    #[test]
    fn compute_message_timeout_respects_retry_budget() {
        assert_eq!(compute_message_timeout_ms(5000, 10, 5, 100), 31500);
    }

    #[test]
    fn future_record_uses_request_id_as_key() {
        let payload = "{\"id\":\"bid-1\"}";
        let record = build_record("bids", "bid-1", payload);

        assert_eq!(record.topic, "bids");
        assert_eq!(record.key, Some("bid-1"));
        assert_eq!(record.payload, Some(payload));
    }

    #[test]
    fn producer_index_is_stable_for_same_key() {
        let left = producer_index_for_key("bid-1", 4);
        let right = producer_index_for_key("bid-1", 4);

        assert_eq!(left, right);
        assert!(left < 4);
    }

    #[test]
    fn producer_index_defaults_to_zero_for_single_producer() {
        assert_eq!(producer_index_for_key("bid-1", 1), 0);
    }
}
