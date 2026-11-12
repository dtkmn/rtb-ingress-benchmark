use actix_web::body::{EitherBody, MessageBody};
use actix_web::dev::{ServiceRequest, ServiceResponse};
use actix_web::middleware::{from_fn, Next};
use actix_web::{web, App, HttpMessage, HttpRequest, HttpResponse, HttpServer, Responder};
use lazy_static::lazy_static;
use prometheus::{Encoder, IntCounter, Registry, TextEncoder};
use rdkafka::config::{ClientConfig, RDKafkaLogLevel};
use rdkafka::producer::{FutureProducer as SdkFutureProducer, FutureRecord};
use serde::{Deserialize, Serialize};
use std::collections::hash_map::DefaultHasher;
use std::collections::BTreeMap;
use std::env;
use std::hash::{Hash, Hasher};
use std::io::{self, BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpStream};
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

mod conformance;
#[cfg(test)]
mod conformance_tests;
#[cfg(test)]
mod fault_tests;
mod input;
mod pipeline;
mod runtime;
use runtime::{BenchmarkClientContext, DiagnosticBudget, RuntimeMetadata, TelemetryProfile};
type FutureProducer = SdkFutureProducer<BenchmarkClientContext>;
use pipeline::{
    DispatchFuture, Limits, OwnedRecord, Pipeline, PreparationLease, TeardownFuture, Transport,
};

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
}

fn register_metrics(runtime: &RuntimeMetadata) {
    REGISTRY
        .register(Box::new(REQUESTS_TOTAL.clone()))
        .expect("collector can be registered");
    REGISTRY
        .register(Box::new(REQUESTS_ACCEPTED.clone()))
        .expect("collector can be registered");
    REGISTRY
        .register(Box::new(REQUESTS_REJECTED.clone()))
        .expect("collector can be registered");
    if let Some(duration) = &runtime.duration {
        REGISTRY
            .register(Box::new(duration.clone()))
            .expect("collector can be registered");
    }
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
    pipeline: Arc<Pipeline>,
    delivery_mode: DeliveryMode,
    topic: String,
    runtime: Arc<RuntimeMetadata>,
    conformance: Option<Arc<conformance::Controller>>,
}

struct KafkaTransport {
    producers: Mutex<Option<Vec<FutureProducer>>>,
    confirm_queue_timeout: Option<Duration>,
}

impl Transport for KafkaTransport {
    fn dispatch(self: Arc<Self>, record: Arc<OwnedRecord>) -> DispatchFuture {
        Box::pin(async move {
            let producer = {
                let pool = self.producers.lock().unwrap();
                pool.as_ref()
                    .and_then(|producers| {
                        select_producer(producers, std::str::from_utf8(&record.key).unwrap_or(""))
                    })
                    .cloned()
            };
            let Some(producer) = producer else {
                return false;
            };
            if let Some(queue_timeout) = self.confirm_queue_timeout {
                return producer
                    .send(
                        FutureRecord::to(&record.topic)
                            .key(record.key.as_slice())
                            .payload(record.value.as_slice()),
                        queue_timeout,
                    )
                    .await
                    .is_ok();
            }
            let delivery = producer.send_result(
                FutureRecord::to(&record.topic)
                    .key(record.key.as_slice())
                    .payload(record.value.as_slice()),
            );
            // The pool owns the client; delivery futures do not need a clone.
            drop(producer);
            match delivery {
                Ok(delivery) => matches!(delivery.await, Ok(Ok(_))),
                Err(_) => false,
            }
        })
    }

    fn teardown(self: Arc<Self>) -> TeardownFuture {
        Box::pin(async move {
            let pool = self.producers.lock().unwrap().take();
            // FutureProducer's final Drop stops/joins its polling thread and
            // drops BaseProducer (purge + bounded flush). Dispatch clones must
            // already be gone before this can certify callback teardown.
            tokio::task::spawn_blocking(move || drop(pool))
                .await
                .map_err(|error| error.to_string())
        })
    }
}

// --- Data Models (mirroring the Go service) ---
#[derive(Serialize, Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct BidRequest {
    id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    site: Option<BidSite>,
    #[serde(skip_serializing_if = "Option::is_none")]
    app: Option<BidApp>,
    #[serde(skip_serializing_if = "Option::is_none")]
    device: Option<BidDevice>,
    #[serde(skip_serializing_if = "Option::is_none")]
    user: Option<BidUser>,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct BidSite {
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    domain: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct BidApp {
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    bundle: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct BidDevice {
    #[serde(skip_serializing_if = "Option::is_none")]
    ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    os: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ua: Option<String>,
    #[serde(default, deserialize_with = "input::deserialize_lmt")]
    lmt: i32,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct BidUser {
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
}

fn is_valid_bid_request(bid_request: &BidRequest) -> bool {
    let optional_string = |value: &Option<String>| {
        value
            .as_ref()
            .is_none_or(|value| value.chars().count() <= 4096)
    };
    bid_request.id.chars().count() <= 256
        && bid_request
            .id
            .chars()
            .any(|character| !matches!(character, '\u{0009}'..='\u{000d}' | ' '))
        && bid_request.device.is_some()
        && (bid_request.site.is_some() || bid_request.app.is_some())
        && bid_request
            .site
            .as_ref()
            .is_none_or(|site| optional_string(&site.id) && optional_string(&site.domain))
        && bid_request
            .app
            .as_ref()
            .is_none_or(|app| optional_string(&app.id) && optional_string(&app.bundle))
        && bid_request.device.as_ref().is_none_or(|device| {
            optional_string(&device.ip)
                && optional_string(&device.os)
                && optional_string(&device.ua)
                && matches!(device.lmt, 0 | 1)
        })
        && bid_request
            .user
            .as_ref()
            .is_none_or(|user| optional_string(&user.id))
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
        "-1" | "all" => "-1".to_string(),
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
    let timeout = u64::from(request_timeout_ms) * (u64::from(retries) + 1)
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

#[cfg(test)]
fn build_record<'a>(
    topic: &'a str,
    key: &'a str,
    payload: &'a [u8],
) -> FutureRecord<'a, str, [u8]> {
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
    context: BenchmarkClientContext,
) -> (FutureProducer, BTreeMap<String, serde_json::Value>) {
    let (config, readback) = producer_configuration(
        kafka_url,
        kafka_acks,
        kafka_linger_ms,
        kafka_batch_bytes,
        kafka_request_timeout_ms,
        kafka_retries,
        kafka_retry_backoff_ms,
        producer_index,
        context.profile,
    );
    (
        config
            .create_with_context(context)
            .expect("Producer creation error"),
        readback,
    )
}

// The same configuration object returned here is passed to SDK construction.
fn producer_configuration(
    kafka_url: &str,
    kafka_acks: &str,
    kafka_linger_ms: u32,
    kafka_batch_bytes: u32,
    kafka_request_timeout_ms: u32,
    kafka_retries: u32,
    kafka_retry_backoff_ms: u32,
    producer_index: usize,
    profile: TelemetryProfile,
) -> (ClientConfig, BTreeMap<String, serde_json::Value>) {
    let client_id = format!("rust-receiver-{}", producer_index + 1);

    let mut config = ClientConfig::new();
    config
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
        .set("queue.buffering.max.messages", "1000000");
    if profile == TelemetryProfile::Controlled {
        config
            .set("statistics.interval.ms", "0")
            .set_log_level(RDKafkaLogLevel::Warning);
    }
    let native = config
        .create_native_config()
        .expect("SDK configuration validation");
    let mut readback: BTreeMap<String, serde_json::Value> = [
        ("acks", "acks"),
        ("linger_ms", "linger.ms"),
        ("batch_bytes", "batch.size"),
        ("request_timeout_ms", "request.timeout.ms"),
        ("retries", "retries"),
        ("retry_backoff_ms", "retry.backoff.ms"),
        ("message_timeout_ms", "message.timeout.ms"),
        ("queue_max_messages", "queue.buffering.max.messages"),
    ]
    .into_iter()
    .map(|(name, key)| {
        let value = native.get(key).ok().and_then(|value| {
            value.parse::<i64>().ok().or_else(|| {
                value
                    .parse::<f64>()
                    .ok()
                    .filter(|value| value.is_finite() && value.fract() == 0.0)
                    .map(|value| value as i64)
            })
        });
        (name.to_string(), serde_json::json!(value))
    })
    .collect();
    // A fresh ClientConfig has debug disabled by SDK default. Explicit empty
    // debug values are rejected by librdkafka; validate the default via its getter.
    readback.insert(
        "_sdk_debug".into(),
        serde_json::json!(native.get("debug").ok()),
    );
    readback.insert(
        "_sdk_statistics_interval_ms".into(),
        serde_json::json!(native
            .get("statistics.interval.ms")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())),
    );
    (config, readback)
}

// Gate before any body extractor can buffer or parse request input.
async fn preparation_gate<B: MessageBody + 'static>(
    req: ServiceRequest,
    next: Next<B>,
) -> Result<ServiceResponse<EitherBody<B>>, actix_web::Error> {
    if req.path() != "/bid-request" || req.method() != actix_web::http::Method::POST {
        return Ok(next.call(req).await?.map_into_left_body());
    }
    let state = req.app_data::<web::Data<AppState>>().unwrap().clone();
    if !input::valid_headers(req.headers()) {
        state.pipeline.record_http_status(415);
        return Ok(req
            .into_response(HttpResponse::UnsupportedMediaType().finish())
            .map_into_right_body());
    }
    let Some(lease) = state.pipeline.prepare() else {
        state.pipeline.record_http_status(503);
        return Ok(req
            .into_response(
                HttpResponse::ServiceUnavailable()
                    .json(serde_json::json!({"status": "intake unavailable"})),
            )
            .map_into_right_body());
    };
    req.extensions_mut().insert(lease.clone());
    let response = next.call(req).await;
    lease.release_from_handler();
    response.map(|response| {
        state
            .pipeline
            .record_http_status(response.status().as_u16());
        response.map_into_left_body()
    })
}

async fn receive_bid(
    req: HttpRequest,
    payload: web::Payload,
    state: web::Data<AppState>,
) -> HttpResponse {
    let timer = state.runtime.start_timer();
    REQUESTS_TOTAL.inc();
    let raw = match payload.to_bytes_limited(65536).await {
        Ok(Ok(body)) => body,
        Err(_) => return HttpResponse::PayloadTooLarge().finish(),
        Ok(Err(_)) => return HttpResponse::BadRequest().finish(),
    };
    if std::str::from_utf8(&raw).map_or(true, |text| text.starts_with('\u{feff}')) {
        return HttpResponse::BadRequest().finish();
    }
    let bid_request = match serde_json::from_slice::<BidRequest>(&raw) {
        Ok(request) => request,
        Err(_) => return HttpResponse::BadRequest().finish(),
    };
    drop(raw);
    if !is_valid_bid_request(&bid_request) {
        REQUESTS_REJECTED.inc();
        return HttpResponse::BadRequest().json(serde_json::json!({"status": "bad request"}));
    }
    if let Some(device) = &bid_request.device {
        if device.lmt == 1
            || device
                .ip
                .as_ref()
                .is_some_and(|ip| ip.starts_with("10.10."))
        {
            REQUESTS_REJECTED.inc();
            return HttpResponse::NoContent().finish();
        }
    }
    if state.delivery_mode == DeliveryMode::HttpOnly {
        REQUESTS_ACCEPTED.inc();
        return HttpResponse::Ok().json(serde_json::json!({"status": "accepted"}));
    }
    let candidate = state.pipeline.candidate();
    if state
        .conformance
        .as_ref()
        .is_some_and(|controller| controller.serialization_failure())
    {
        state.pipeline.serialization_failed(&candidate);
        return HttpResponse::InternalServerError()
            .json(serde_json::json!({"status":"serialization error"}));
    }
    let value = match serde_json::to_vec(&bid_request) {
        Ok(value) => value,
        Err(_) => {
            state.pipeline.serialization_failed(&candidate);
            return HttpResponse::InternalServerError()
                .json(serde_json::json!({"status": "serialization error"}));
        }
    };
    let record = OwnedRecord {
        topic: state.topic.clone(),
        key: bid_request.id.as_bytes().to_vec(),
        value,
    };
    drop(bid_request);
    let admitted = if let Some(controller) = &state.conformance {
        let preparation = req.extensions().get::<PreparationLease>().unwrap().clone();
        controller.admit(candidate, record, preparation).await
    } else {
        state.pipeline.commit(candidate, record)
    };
    let receipt = match admitted {
        Ok(receipt) => receipt,
        Err(_) => {
            return HttpResponse::ServiceUnavailable()
                .json(serde_json::json!({"status": "kafka unavailable"}))
        }
    };
    if let Some(lease) = req.extensions().get::<PreparationLease>() {
        lease.release();
    }
    let accepted =
        state.delivery_mode == DeliveryMode::Enqueue || state.pipeline.confirm(receipt).await;
    if let Some(timer) = timer {
        timer.observe_duration();
    }
    if accepted {
        REQUESTS_ACCEPTED.inc();
        HttpResponse::Ok().json(serde_json::json!({"status": "accepted"}))
    } else {
        REQUESTS_REJECTED.inc();
        HttpResponse::ServiceUnavailable().json(serde_json::json!({"status": "kafka buffer full"}))
    }
}

async fn health_check(state: web::Data<AppState>) -> HttpResponse {
    if state.pipeline.is_closing() {
        HttpResponse::ServiceUnavailable().json(serde_json::json!({"status": "draining"}))
    } else {
        HttpResponse::Ok().json(serde_json::json!({"status": "healthy"}))
    }
}

async fn benchmark_state(state: web::Data<AppState>) -> HttpResponse {
    HttpResponse::Ok().json(state.pipeline.snapshot())
}

async fn benchmark_runtime(state: web::Data<AppState>) -> HttpResponse {
    HttpResponse::Ok().json(state.runtime.snapshot(&state.pipeline))
}

async fn benchmark_conformance(
    req: HttpRequest,
    payload: web::Payload,
    state: web::Data<AppState>,
) -> HttpResponse {
    let Some(controller) = &state.conformance else {
        return HttpResponse::NotFound().finish();
    };
    let tokens: Vec<_> = req.headers().get_all("x-conformance-token").collect();
    if tokens.len() != 1
        || tokens[0]
            .to_str()
            .map_or(true, |token| !controller.authorized(token))
    {
        return HttpResponse::NotFound().finish();
    }
    if req.method() == actix_web::http::Method::GET {
        return HttpResponse::Ok().json(controller.snapshot());
    }
    let raw = match payload.to_bytes_limited(8192).await {
        Ok(Ok(raw)) => raw,
        Err(_) => return HttpResponse::PayloadTooLarge().finish(),
        Ok(Err(_)) => return HttpResponse::BadRequest().finish(),
    };
    let command = match serde_json::from_slice::<serde_json::Value>(&raw) {
        Ok(value) if value.is_object() => value,
        _ => {
            return HttpResponse::Conflict()
                .json(serde_json::json!({"error":"invalid command object"}))
        }
    };
    match controller.command(command).await {
        Ok(value) => HttpResponse::Ok().json(value),
        Err(error) => HttpResponse::Conflict().json(serde_json::json!({"error":error})),
    }
}

async fn stop_after_drain(
    pipeline: Arc<Pipeline>,
    handle: actix_web::dev::ServerHandle,
    failure: Arc<AtomicBool>,
) {
    let end = pipeline.shutdown().await;
    failure.store(
        end["drain_result"] != "settled" || end["teardown_confirmed"] != true,
        Ordering::Release,
    );
    // The receiver has already used its single drain/teardown budget. Force
    // close remaining HTTP tasks rather than start another Actix grace period.
    handle.stop(false).await;
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

fn is_healthy_status_line(status_line: &str) -> bool {
    status_line
        .split_ascii_whitespace()
        .nth(1)
        .and_then(|status| status.parse::<u16>().ok())
        .is_some_and(|status| (200..300).contains(&status))
}

fn check_health(address: SocketAddr) -> io::Result<()> {
    let timeout = Duration::from_secs(3);
    let mut stream = TcpStream::connect_timeout(&address, timeout)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    stream.write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")?;

    let mut status_line = String::new();
    BufReader::new(stream).read_line(&mut status_line)?;
    if is_healthy_status_line(&status_line) {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "unexpected HTTP status: {}",
            status_line.trim()
        )))
    }
}

// --- Main Application Entry Point ---
fn main() -> io::Result<()> {
    if env::args().nth(1).as_deref() == Some("healthcheck") {
        return check_health(
            "127.0.0.1:8080"
                .parse()
                .expect("health-check address is valid"),
        );
    }

    run_server()
}

#[actix_web::main]
async fn run_server() -> io::Result<()> {
    // Get Kafka URL from environment or use a default
    let kafka_url =
        env::var("KAFKA_BOOTSTRAP_SERVERS").unwrap_or_else(|_| "localhost:9092".to_string());
    let kafka_topic = kafka_topic_from_env();
    let delivery_mode = DeliveryMode::from_env();
    let telemetry_profile =
        TelemetryProfile::parse(&env::var("BENCHMARK_TELEMETRY_PROFILE").unwrap_or_default())?;
    let kafka_acks = kafka_acks_from_env();
    if delivery_mode == DeliveryMode::Confirm && kafka_acks == "0" {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "confirm does not support acknowledgements=0",
        ));
    }
    let workers = http_workers_from_env();
    let conformance =
        conformance::Controller::from_env(delivery_mode, workers, kafka_topic.clone())?;
    let kafka_linger_ms = parse_non_negative_u32_env("BENCHMARK_KAFKA_LINGER_MS", 10);
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

    let diagnostic_budget = DiagnosticBudget::new();
    let built: Vec<_> = if delivery_mode.uses_kafka() && conformance.is_none() {
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
                    BenchmarkClientContext {
                        profile: telemetry_profile,
                        budget: diagnostic_budget.clone(),
                    },
                )
            })
            .collect()
    } else {
        if delivery_mode.uses_kafka() {
            println!("Conformance controlled transport enabled; no real Kafka SDK initialized");
        } else {
            println!("HTTP-only benchmark mode enabled; skipping Kafka producer initialization");
        }
        Vec::new()
    };

    let constructor_readback = built
        .first()
        .map(|(_, settings)| settings.clone())
        .unwrap_or_default();
    let producers: Vec<_> = built.into_iter().map(|(producer, _)| producer).collect();
    let requested = BTreeMap::from([
        (
            "acks".into(),
            serde_json::json!(kafka_acks.parse::<i32>().unwrap()),
        ),
        ("linger_ms".into(), serde_json::json!(kafka_linger_ms)),
        ("batch_bytes".into(), serde_json::json!(kafka_batch_bytes)),
        (
            "request_timeout_ms".into(),
            serde_json::json!(kafka_request_timeout_ms),
        ),
        ("retries".into(), serde_json::json!(kafka_retries)),
        (
            "retry_backoff_ms".into(),
            serde_json::json!(kafka_retry_backoff_ms),
        ),
        (
            "producer_pool_size".into(),
            serde_json::json!(kafka_producer_pool_size),
        ),
        (
            "message_timeout_ms".into(),
            serde_json::json!(compute_message_timeout_ms(
                kafka_request_timeout_ms,
                kafka_linger_ms,
                kafka_retries,
                kafka_retry_backoff_ms
            )),
        ),
        ("queue_max_messages".into(), serde_json::json!(1000000)),
    ]);
    let runtime = RuntimeMetadata::new(
        telemetry_profile,
        delivery_mode,
        workers,
        producers.len(),
        requested,
        constructor_readback,
    );
    if delivery_mode.uses_kafka() && conformance.is_some() {
        runtime.enable_validation_transport();
    }

    let limits = Limits {
        max_records: parse_positive_u32_env("BENCHMARK_PIPELINE_MAX_RECORDS", 1024) as usize,
        max_bytes: parse_positive_u32_env("BENCHMARK_PIPELINE_MAX_BYTES", 16777216) as usize,
        max_preparations: parse_positive_u32_env("BENCHMARK_PREPARATION_MAX_REQUESTS", 128)
            as usize,
        http_outcome_timeout_ms: u64::from(parse_positive_u32_env(
            "BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS",
            30000,
        )),
        drain_timeout_ms: u64::from(parse_positive_u32_env("BENCHMARK_DRAIN_TIMEOUT_MS", 30000)),
    };
    let transport: Option<Arc<dyn Transport>> = if delivery_mode.uses_kafka() {
        if let Some(controller) = &conformance {
            Some(controller.clone())
        } else {
            Some(Arc::new(KafkaTransport {
                producers: Mutex::new(Some(producers)),
                confirm_queue_timeout: (delivery_mode == DeliveryMode::Confirm)
                    .then_some(Duration::from_millis(u64::from(kafka_request_timeout_ms))),
            }))
        }
    } else {
        None
    };
    let pipeline = Pipeline::new(limits, transport);
    if let Some(controller) = &conformance {
        controller.attach(&pipeline);
    }
    let state = AppState {
        pipeline: pipeline.clone(),
        delivery_mode,
        topic: kafka_topic,
        runtime: runtime.clone(),
        conformance,
    };

    // Register Prometheus metrics
    register_metrics(&runtime);

    let port = u16::try_from(parse_positive_u32_env("PORT", 8080))
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "PORT must fit a TCP port"))?;
    println!("Starting Rust AdTech Receiver on port {port}...");

    // Start the Actix web server
    let server = HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(state.runtime.observe_worker()))
            .app_data(web::Data::new(state.clone()))
            .app_data(web::PayloadConfig::new(65536))
            .app_data(web::JsonConfig::default().limit(65536))
            .wrap(from_fn(preparation_gate))
            .route("/bid-request", web::post().to(receive_bid))
            .route("/health", web::get().to(health_check))
            .route("/benchmark/state", web::get().to(benchmark_state))
            .route("/benchmark/runtime", web::get().to(benchmark_runtime))
            .route(
                "/benchmark/conformance",
                web::get().to(benchmark_conformance),
            )
            .route(
                "/benchmark/conformance",
                web::post().to(benchmark_conformance),
            )
            .route("/metrics", web::get().to(metrics))
    })
    .workers(workers)
    .disable_signals()
    .shutdown_timeout(5)
    .bind(("0.0.0.0", port))?
    .run();
    let handle = server.handle();
    let shutdown_failed = Arc::new(AtomicBool::new(true));
    let failure = shutdown_failed.clone();
    tokio::spawn(async move {
        #[cfg(unix)]
        {
            let mut termination =
                tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
            tokio::select! { _ = termination.recv() => {}, _ = tokio::signal::ctrl_c() => {} }
        }
        #[cfg(not(unix))]
        {
            let _ = tokio::signal::ctrl_c().await;
        }
        pipeline.close_intake();
        stop_after_drain(pipeline, handle, failure).await;
    });
    server.await?;
    if shutdown_failed.load(Ordering::Acquire) {
        Err(io::Error::other(
            "receiver drain failed or teardown was not confirmed",
        ))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestTransport {
        release: tokio::sync::Semaphore,
        success: bool,
    }

    impl Transport for TestTransport {
        fn dispatch(self: Arc<Self>, _record: Arc<OwnedRecord>) -> DispatchFuture {
            Box::pin(async move {
                self.release.acquire().await.unwrap().forget();
                self.success
            })
        }
        fn teardown(self: Arc<Self>) -> TeardownFuture {
            Box::pin(async { Ok(()) })
        }
    }

    #[actix_web::test]
    async fn http_body_limit_and_preparse_gate_apply_in_http_only() {
        let pipeline = Pipeline::new(
            Limits {
                max_preparations: 1,
                ..Limits::default()
            },
            None,
        );
        let state = AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::HttpOnly,
            runtime: RuntimeMetadata::for_test(DeliveryMode::HttpOnly),
            conformance: None,
            topic: "bids".into(),
        };
        let app = actix_web::test::init_service(
            App::new()
                .app_data(web::Data::new(state))
                .app_data(web::PayloadConfig::new(65536))
                .app_data(web::JsonConfig::default().limit(65536))
                .wrap(from_fn(preparation_gate))
                .route("/bid-request", web::post().to(receive_bid)),
        )
        .await;
        let oversized = actix_web::test::TestRequest::post()
            .uri("/bid-request")
            .insert_header(("content-type", "application/json"))
            .set_payload(vec![b' '; 65537])
            .to_request();
        assert_eq!(
            actix_web::test::call_service(&app, oversized)
                .await
                .status(),
            413
        );
        let lease = pipeline.prepare().unwrap();
        let invalid = actix_web::test::TestRequest::post()
            .uri("/bid-request")
            .insert_header(("content-type", "application/json"))
            .set_payload("invalid")
            .to_request();
        assert_eq!(
            actix_web::test::call_service(&app, invalid).await.status(),
            503
        );
        lease.release();
        let valid = actix_web::test::TestRequest::post()
            .uri("/bid-request")
            .insert_header(("content-type", "application/json"))
            .set_payload(r#"{"id":"id","site":{},"device":{}}"#)
            .to_request();
        assert_eq!(
            actix_web::test::call_service(&app, valid).await.status(),
            200
        );
        let state = pipeline.snapshot();
        assert_eq!(state["admission_candidates"], 0);
        assert_eq!(state["preparation_count"], 0);
        assert_eq!(state["preparation_rejected"], 1);
        assert_eq!(state["http_status_counts"]["413"], 1);
    }

    #[actix_web::test]
    async fn enqueue_http_acceptance_survives_immediate_postcommit_sdk_failure() {
        let transport = Arc::new(TestTransport {
            release: tokio::sync::Semaphore::new(1),
            success: false,
        });
        let pipeline = Pipeline::new(Limits::default(), Some(transport));
        let state = AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::Enqueue,
            runtime: RuntimeMetadata::for_test(DeliveryMode::Enqueue),
            conformance: None,
            topic: "bids".into(),
        };
        let app = actix_web::test::init_service(
            App::new()
                .app_data(web::Data::new(state))
                .app_data(web::PayloadConfig::new(65536))
                .app_data(web::JsonConfig::default().limit(65536))
                .wrap(from_fn(preparation_gate))
                .route("/bid-request", web::post().to(receive_bid)),
        )
        .await;
        let request = actix_web::test::TestRequest::post()
            .uri("/bid-request")
            .insert_header(("content-type", "application/json"))
            .set_payload(r#"{"id":"id","site":{},"device":{}}"#)
            .to_request();
        assert_eq!(
            actix_web::test::call_service(&app, request).await.status(),
            200
        );
        tokio::task::yield_now().await;
        assert_eq!(pipeline.snapshot()["admitted"], 1);
        assert_eq!(pipeline.snapshot()["client_failed"], 1);
        assert_eq!(pipeline.snapshot()["admission_rejected"], 0);
        pipeline.shutdown().await;
    }

    #[actix_web::test]
    async fn confirm_http_timeout_retains_record_for_late_success() {
        let transport = Arc::new(TestTransport {
            release: tokio::sync::Semaphore::new(0),
            success: true,
        });
        let pipeline = Pipeline::new(
            Limits {
                http_outcome_timeout_ms: 1,
                ..Limits::default()
            },
            Some(transport.clone()),
        );
        let state = AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::Confirm,
            runtime: RuntimeMetadata::for_test(DeliveryMode::Confirm),
            conformance: None,
            topic: "bids".into(),
        };
        let app = actix_web::test::init_service(
            App::new()
                .app_data(web::Data::new(state))
                .app_data(web::PayloadConfig::new(65536))
                .app_data(web::JsonConfig::default().limit(65536))
                .wrap(from_fn(preparation_gate))
                .route("/bid-request", web::post().to(receive_bid)),
        )
        .await;
        let request = actix_web::test::TestRequest::post()
            .uri("/bid-request")
            .insert_header(("content-type", "application/json"))
            .set_payload(r#"{"id":"id","site":{},"device":{}}"#)
            .to_request();
        assert_eq!(
            actix_web::test::call_service(&app, request).await.status(),
            503
        );
        assert_eq!(pipeline.snapshot()["preparation_count"], 0);
        assert_eq!(pipeline.snapshot()["outstanding"], 1);
        assert!(pipeline.snapshot()["outstanding_bytes"].as_u64().unwrap() > 0);
        assert_eq!(pipeline.snapshot()["client_failed"], 0);
        transport.release.add_permits(1);
        tokio::task::yield_now().await;
        assert_eq!(pipeline.snapshot()["client_succeeded"], 1);
        assert_eq!(pipeline.snapshot()["outstanding"], 0);
        pipeline.shutdown().await;
    }

    #[actix_web::test]
    async fn lifecycle_does_not_add_actix_grace_after_own_drain_deadline() {
        let pipeline = Pipeline::new(
            Limits {
                drain_timeout_ms: 10,
                ..Limits::default()
            },
            None,
        );
        let state = AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::HttpOnly,
            runtime: RuntimeMetadata::for_test(DeliveryMode::HttpOnly),
            conformance: None,
            topic: "bids".into(),
        };
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = HttpServer::new(move || {
            App::new()
                .app_data(web::Data::new(state.clone()))
                .app_data(web::JsonConfig::default().limit(65536))
                .wrap(from_fn(preparation_gate))
                .route("/bid-request", web::post().to(receive_bid))
        })
        .workers(1)
        .disable_signals()
        .shutdown_timeout(5)
        .listen(listener)
        .unwrap()
        .run();
        let handle = server.handle();
        let server_task = actix_web::rt::spawn(server);
        let mut socket = TcpStream::connect(address).unwrap();
        // Leave a body incomplete so a real HTTP task holds its preparation
        // lease. A second graceful server wait would consume another 5 seconds.
        socket.write_all(b"POST /bid-request HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{").unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while pipeline.snapshot()["preparation_count"] != 1 {
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .expect("real request enters bounded preparation");
        let failure = Arc::new(AtomicBool::new(false));
        tokio::time::timeout(
            Duration::from_secs(1),
            stop_after_drain(pipeline.clone(), handle, failure.clone()),
        )
        .await
        .expect("no second Actix grace after the 10-ms receiver drain");
        server_task.await.unwrap().unwrap();
        assert!(failure.load(Ordering::Acquire));
        assert_eq!(pipeline.snapshot()["drain_result"], "timed_out");
        drop(socket);
    }

    #[test]
    fn health_status_accepts_only_success_responses() {
        assert!(is_healthy_status_line("HTTP/1.1 200 OK\r\n"));
        assert!(is_healthy_status_line("HTTP/1.1 204 No Content\r\n"));
        assert!(!is_healthy_status_line(
            "HTTP/1.1 503 Service Unavailable\r\n"
        ));
        assert!(!is_healthy_status_line("not-http"));
    }

    #[test]
    fn request_with_site_or_app_is_valid() {
        let app_request = BidRequest {
            id: "bid-1".to_string(),
            site: None,
            app: Some(BidApp {
                id: None,
                bundle: Some("com.example.app".to_string()),
            }),
            device: Some(BidDevice {
                ip: None,
                os: None,
                ua: None,
                lmt: 0,
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
                ua: None,
                lmt: 0,
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
        let payload = br#"{"id":"bid-1"}"#;
        let record = build_record("bids", "bid-1", payload);

        assert_eq!(record.topic, "bids");
        assert_eq!(record.key, Some("bid-1"));
        assert_eq!(record.payload, Some(payload.as_slice()));
    }

    #[test]
    fn serialized_payload_omits_absent_fields_like_python() {
        let request = BidRequest {
            id: "bid-1".to_string(),
            site: Some(BidSite {
                id: Some("site-1".to_string()),
                domain: Some("example.com".to_string()),
            }),
            app: None,
            device: Some(BidDevice {
                ip: Some("1.2.3.4".to_string()),
                os: None,
                ua: Some("test-agent".to_string()),
                lmt: 0,
            }),
            user: None,
        };

        let payload = serde_json::to_vec(&request).expect("request can be serialized");

        assert_eq!(
            payload,
            br#"{"id":"bid-1","site":{"id":"site-1","domain":"example.com"},"device":{"ip":"1.2.3.4","ua":"test-agent","lmt":0}}"#
        );
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
