//! Management-only runtime facts and optional telemetry; no SDK config is inferred.
use crate::{DeliveryMode, Pipeline};
use prometheus::{Histogram, HistogramOpts, HistogramTimer};
use rdkafka::client::{ClientContext, DefaultClientContext};
use rdkafka::config::RDKafkaLogLevel;
use rdkafka::error::KafkaError;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TelemetryProfile {
    Controlled,
    Operational,
}

impl TelemetryProfile {
    pub fn parse(raw: &str) -> io::Result<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "" | "controlled" => Ok(Self::Controlled),
            "operational" => Ok(Self::Operational),
            _ => Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "BENCHMARK_TELEMETRY_PROFILE must be controlled or operational",
            )),
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Controlled => "controlled",
            Self::Operational => "operational",
        }
    }
}

/// One shared output budget across all producers in this process.
pub struct DiagnosticBudget(Mutex<(Instant, usize)>);

impl DiagnosticBudget {
    pub fn new() -> Arc<Self> {
        Arc::new(Self(Mutex::new((Instant::now(), 0))))
    }
    fn allow(&self) -> bool {
        let mut state = self.0.lock().unwrap();
        if state.0.elapsed() >= Duration::from_secs(60) {
            *state = (Instant::now(), 0);
        }
        if state.1 >= 10 {
            return false;
        }
        state.1 += 1;
        true
    }
}

#[derive(Clone)]
pub struct BenchmarkClientContext {
    pub profile: TelemetryProfile,
    pub budget: Arc<DiagnosticBudget>,
}

impl ClientContext for BenchmarkClientContext {
    fn log(&self, level: RDKafkaLogLevel, facility: &str, message: &str) {
        if self.profile == TelemetryProfile::Operational {
            DefaultClientContext.log(level, facility, message);
        } else if matches!(
            level,
            RDKafkaLogLevel::Emerg
                | RDKafkaLogLevel::Alert
                | RDKafkaLogLevel::Critical
                | RDKafkaLogLevel::Error
                | RDKafkaLogLevel::Warning
        ) && self.budget.allow()
        {
            eprintln!(
                "Kafka {level:?} {} {}",
                facility.chars().take(128).collect::<String>(),
                message.chars().take(2048).collect::<String>()
            );
        }
    }

    fn error(&self, error: KafkaError, reason: &str) {
        if self.profile == TelemetryProfile::Operational {
            DefaultClientContext.error(error, reason);
        } else if self.budget.allow() {
            eprintln!(
                "Kafka error {error}: {}",
                reason.chars().take(2048).collect::<String>()
            );
        }
    }
}

pub struct RuntimeMetadata {
    pub profile: TelemetryProfile,
    pub duration: Option<Histogram>,
    requested_workers: usize,
    observed_workers: Arc<AtomicUsize>,
    mode: DeliveryMode,
    instances: usize,
    requested: BTreeMap<String, Value>,
    constructor_readback: BTreeMap<String, Value>,
    validation_transport: AtomicBool,
}

/// One instance per live Actix application factory; held until that worker stops.
pub struct WorkerObservation(Arc<AtomicUsize>);
impl Drop for WorkerObservation {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

impl RuntimeMetadata {
    pub fn new(
        profile: TelemetryProfile,
        mode: DeliveryMode,
        workers: usize,
        instances: usize,
        requested: BTreeMap<String, Value>,
        constructor_readback: BTreeMap<String, Value>,
    ) -> Arc<Self> {
        Arc::new(Self {
            profile,
            mode,
            requested_workers: workers,
            instances,
            requested,
            constructor_readback,
            validation_transport: AtomicBool::new(false),
            observed_workers: Arc::new(AtomicUsize::new(0)),
            // Controlled never constructs or registers a histogram or samples a clock.
            duration: (profile == TelemetryProfile::Operational).then(|| {
                Histogram::with_opts(HistogramOpts::new(
                    "rust_receiver_request_duration_seconds",
                    "Request duration in seconds",
                ))
                .expect("metric can be created")
            }),
        })
    }

    pub fn observe_worker(&self) -> WorkerObservation {
        self.observed_workers.fetch_add(1, Ordering::AcqRel);
        WorkerObservation(self.observed_workers.clone())
    }

    pub fn enable_validation_transport(&self) {
        self.validation_transport.store(true, Ordering::Release);
    }

    pub fn start_timer(&self) -> Option<HistogramTimer> {
        self.duration.as_ref().map(Histogram::start_timer)
    }

    #[cfg(test)]
    pub fn for_test(mode: DeliveryMode) -> Arc<Self> {
        Self::new(
            TelemetryProfile::Controlled,
            mode,
            1,
            0,
            BTreeMap::new(),
            BTreeMap::new(),
        )
    }

    pub fn snapshot(&self, pipeline: &Pipeline) -> Value {
        let validation_transport = self.validation_transport.load(Ordering::Acquire);
        let kafka = self.mode.uses_kafka() && !validation_transport;
        let mut controls = Map::new();
        for (name, requested) in &self.requested {
            let setting = match name.as_str() {
                "acks" => "acks",
                "linger_ms" => "linger.ms",
                "batch_bytes" => "batch.size",
                "retries" => "retries",
                "retry_backoff_ms" => "retry.backoff.ms",
                "request_timeout_ms" => "request.timeout.ms",
                "message_timeout_ms" => "message.timeout.ms",
                "queue_max_messages" => "queue.buffering.max.messages",
                _ => "application producer construction loop",
            };
            controls.insert(name.clone(), json!({
                "status": if kafka { "applied" } else { "not_applicable" },
                "requested": requested,
                "applied": if kafka { requested.clone() } else { Value::Null },
                // NativeClientConfig is a public preconstruction SDK getter, not a running-client getter.
                "effective": if kafka && name == "producer_pool_size" { json!(self.instances) } else { Value::Null },
                "sdk_constructor_readback": if kafka { self.constructor_readback.get(name).cloned().unwrap_or(Value::Null) } else { Value::Null },
                "evidence": if validation_transport { "opt-in controlled validation transport; no SDK settings applied or Kafka delivery claimed".to_string() }
                    else if !kafka { "no producer constructed in HTTP-only mode".to_string() }
                    else if name == "producer_pool_size" { "actual producer-construction loop; effective is observed Vec length".to_string() }
                    else { format!("ClientConfig {setting} passed to create_with_context; public NativeClientConfig.get before construction (separate field); running-client config getter unavailable") },
            }));
        }
        let state = pipeline.snapshot();
        json!({
            "schema_version": 1, "lane": "rust", "scope": "process",
            "delivery_mode": match self.mode { DeliveryMode::Confirm => "confirm", DeliveryMode::Enqueue => "enqueue", DeliveryMode::HttpOnly => "http-only" },
            "telemetry": {
                "profile": self.profile.name(), "access_log": false, "tracing": false,
                "trace_export": false, "body_size_metrics": false, "request_latency_histogram": self.duration.is_some(),
                "evidence": {
                    "optional_work": "no access Logger, tracing/export or body-size instruments constructed; histogram constructed and sampled only in operational profile",
                    "sdk_debug": "controlled: fresh ClientConfig debug disabled by SDK default, statistics.interval.ms=0, SDK log level warning, shared 10 messages/minute budget with bounded text; operational: existing default SDK logger behavior",
                    "sdk_constructor_readback": {"debug": self.constructor_readback.get("_sdk_debug"), "statistics_interval_ms": self.constructor_readback.get("_sdk_statistics_interval_ms")},
                    "aggregate_accounting": "F2 aggregate admission/client/lifecycle counters remain enabled in both profiles"
                }
            },
            "runtime": {
                "pid": std::process::id(), "kind": "native-rust", "version": null,
                "version_evidence": "no separate VM and compiler version not embedded; application version is Cargo package metadata",
                "application_version": env!("CARGO_PKG_VERSION"),
                "http_parallelism": {"control": "HTTP_SERVER_WORKERS", "requested": self.requested_workers,
                    "effective": self.observed_workers.load(Ordering::Acquire),
                    "evidence": "live Actix application-factory guards; one listener; process census is external"},
                "heap": {"max_bytes": null, "gc": "none", "evidence": "native Rust allocation; no application hard heap maximum or tracing GC; cgroup memory limit is separate"},
                "cgroup": cgroup_readback()
            },
            "producer": {"client": if validation_transport {"controlled-pipeline-transport"} else {"rust-rdkafka/librdkafka"}, "version": if validation_transport {"adapter-v1".into()} else {rdkafka::util::get_rdkafka_version().1},
                "validation_transport":validation_transport,"validation_transport_evidence":if validation_transport {Some("owned pipeline proof only; healthy SDK integration is a separate disabled-adapter gate")} else {None},
                "instances": self.instances, "controls": controls},
            "pipeline": {"effective_bounds": state["effective_bounds"], "configured_bounds": state["configured_bounds"],
                "http_outcome_timeout_ms": state["effective_bounds"]["http_outcome_timeout_ms"], "drain_timeout_ms": state["effective_bounds"]["drain_timeout_ms"]}
        })
    }
}

fn unescape_mount(raw: &str) -> String {
    raw.replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
}

fn group_path(group: &str, root: &str, mount: &str) -> PathBuf {
    let relative = if group == "/" {
        Path::new("")
    } else {
        Path::new(group)
            .strip_prefix(root)
            .unwrap_or_else(|_| Path::new(group.trim_start_matches('/')))
    };
    Path::new(mount).join(relative)
}

fn cgroup_from(read: impl Fn(&Path) -> io::Result<String>, linux: bool) -> Value {
    let mut out = json!({"version": null, "memory_max_bytes": null, "cpu_quota_us": null, "cpu_period_us": null,
        "cpuset_cpus_effective": null, "raw": {"memory.max": null, "cpu.max": null, "cpuset.cpus.effective": null},
        "evidence": {"scope": "current kernel cgroup files; ancestor memory/CPU constraints are not inferred"}});
    if !linux {
        out["evidence"]["unavailable"] = json!("cgroup readback is available only on Linux");
        return out;
    }
    let parsed = (|| -> io::Result<()> {
        let group_text = read(Path::new("/proc/self/cgroup"))?;
        let mount_text = read(Path::new("/proc/self/mountinfo"))?;
        let groups: Vec<_> = group_text
            .lines()
            .filter_map(|line| {
                let mut parts = line.splitn(3, ':');
                Some((parts.next()?, parts.next()?, parts.next()?))
            })
            .collect();
        let mut paths = BTreeMap::new();
        for line in mount_text.lines() {
            let Some((before, after)) = line.split_once(" - ") else {
                continue;
            };
            let fields: Vec<_> = before.split_whitespace().collect();
            let fs: Vec<_> = after.split_whitespace().collect();
            if fields.len() < 5 || fs.len() < 3 {
                continue;
            }
            let root = unescape_mount(fields[3]);
            let mount = unescape_mount(fields[4]);
            if fs[0] == "cgroup2" {
                if let Some((_, _, group)) =
                    groups.iter().find(|(_, controls, _)| controls.is_empty())
                {
                    let base = group_path(group, &root, &mount);
                    for name in ["memory.max", "cpu.max", "cpuset.cpus.effective"] {
                        paths.insert(name, base.join(name));
                    }
                    out["version"] = json!(2);
                    break;
                }
            } else if fs[0] == "cgroup" {
                for (controller, name, filename) in [
                    ("memory", "memory.max", "memory.limit_in_bytes"),
                    ("cpu", "cpu.max", "cpu.cfs_quota_us"),
                    ("cpuset", "cpuset.cpus.effective", "cpuset.effective_cpus"),
                ] {
                    if !fs[2].split(',').any(|name| name == controller) {
                        continue;
                    }
                    if let Some((_, _, group)) = groups
                        .iter()
                        .find(|(_, names, _)| names.split(',').any(|name| name == controller))
                    {
                        paths.insert(name, group_path(group, &root, &mount).join(filename));
                        out["version"] = json!(1);
                    }
                }
            }
        }
        for (name, mut path) in paths {
            let value = match read(&path) {
                Ok(value) => Some(value),
                Err(_) if name == "cpuset.cpus.effective" && out["version"] == 1 => {
                    path.set_file_name("cpuset.cpus");
                    read(&path).ok()
                }
                Err(_) => None,
            };
            if let Some(value) = value {
                let mut value = value.trim().to_string();
                if name == "cpu.max" && out["version"] == 1 {
                    value.push(' ');
                    value.push_str(read(&path.with_file_name("cpu.cfs_period_us"))?.trim());
                }
                out["raw"][name] = json!(value);
                out["evidence"][name] = json!(path.to_string_lossy());
            }
        }
        if let Some(memory) = out["raw"]["memory.max"]
            .as_str()
            .and_then(|value| value.parse::<u64>().ok())
        {
            // v1's page-rounded signed-long sentinel represents an unlimited cap.
            if out["version"] == 2 || memory < 1 << 60 {
                out["memory_max_bytes"] = json!(memory);
            }
        }
        if let Some(cpu) = out["raw"]["cpu.max"].as_str() {
            let fields: Vec<_> = cpu.split_whitespace().collect();
            if fields.len() == 2 {
                let quota = fields[0].parse::<u64>().ok();
                let period = fields[1].parse::<u64>().ok();
                out["cpu_quota_us"] = json!(quota);
                out["cpu_period_us"] = json!(period);
            }
        }
        out["cpuset_cpus_effective"] = out["raw"]["cpuset.cpus.effective"].clone();
        Ok(())
    })();
    if let Err(error) = parsed {
        out["evidence"]["unavailable"] = json!(error.to_string());
    }
    out
}

pub fn cgroup_readback() -> Value {
    cgroup_from(|path| fs::read_to_string(path), cfg!(target_os = "linux"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn optional_histogram_is_never_constructed_or_sampled_in_controlled() {
        let controlled = RuntimeMetadata::for_test(DeliveryMode::HttpOnly);
        assert!(controlled.duration.is_none());
        assert!(controlled.start_timer().is_none());
        let operational = RuntimeMetadata::new(
            TelemetryProfile::Operational,
            DeliveryMode::HttpOnly,
            1,
            0,
            BTreeMap::new(),
            BTreeMap::new(),
        );
        operational.start_timer().unwrap().observe_duration();
        assert_eq!(operational.duration.as_ref().unwrap().get_sample_count(), 1);
        assert_eq!(
            TelemetryProfile::parse("  CONTROLLED ").unwrap(),
            TelemetryProfile::Controlled
        );
        assert!(TelemetryProfile::parse("typo").is_err());
    }

    #[test]
    fn worker_count_is_observed_and_released() {
        let runtime = RuntimeMetadata::for_test(DeliveryMode::HttpOnly);
        let first = runtime.observe_worker();
        let second = runtime.observe_worker();
        assert_eq!(runtime.observed_workers.load(Ordering::Acquire), 2);
        drop(first);
        assert_eq!(runtime.observed_workers.load(Ordering::Acquire), 1);
        drop(second);
        assert_eq!(runtime.observed_workers.load(Ordering::Acquire), 0);
    }

    #[test]
    fn diagnostics_budget_is_shared_and_bounded() {
        let budget = DiagnosticBudget::new();
        let another = budget.clone();
        assert_eq!(
            (0..100)
                .filter(|index| if index % 2 == 0 {
                    budget.allow()
                } else {
                    another.allow()
                })
                .count(),
            10
        );
        *budget.0.lock().unwrap() = (Instant::now() - Duration::from_secs(60), 10);
        assert!(budget.allow());
    }

    #[test]
    fn linux_v2_current_group_mount_and_unlimited_readback() {
        let files = BTreeMap::from([
            ("/proc/self/cgroup", "0::/tenant/worker\n"),
            (
                "/proc/self/mountinfo",
                "29 23 0:26 /tenant /sys/cg\\040mounted rw - cgroup2 cgroup rw\n",
            ),
            ("/sys/cg mounted/worker/memory.max", "536870912\n"),
            ("/sys/cg mounted/worker/cpu.max", "200000 100000\n"),
            ("/sys/cg mounted/worker/cpuset.cpus.effective", "1-2\n"),
        ]);
        let read = |path: &Path| {
            files
                .get(path.to_str().unwrap())
                .map(|value| value.to_string())
                .ok_or(io::Error::from(io::ErrorKind::NotFound))
        };
        let result = cgroup_from(read, true);
        assert_eq!(result["version"], 2);
        assert_eq!(result["memory_max_bytes"], 536870912u64);
        assert_eq!(result["cpu_quota_us"], 200000);
        assert_eq!(result["cpu_period_us"], 100000);
        assert_eq!(result["cpuset_cpus_effective"], "1-2");
        let unlimited = cgroup_from(
            |path| {
                Ok(match path.to_str().unwrap() {
                    "/proc/self/cgroup" => "0::/".into(),
                    "/proc/self/mountinfo" => "1 2 0:1 / /sys/cg rw - cgroup2 cg rw".into(),
                    "/sys/cg/memory.max" => "max".into(),
                    "/sys/cg/cpu.max" => "max 100000".into(),
                    "/sys/cg/cpuset.cpus.effective" => "0-7".into(),
                    _ => panic!("unexpected path"),
                })
            },
            true,
        );
        assert!(unlimited["memory_max_bytes"].is_null());
        assert!(unlimited["cpu_quota_us"].is_null());
        assert_eq!(unlimited["cpu_period_us"], 100000);
    }

    #[test]
    fn linux_v1_controllers_and_non_linux_evidence() {
        let files = BTreeMap::from([
            ("/proc/self/cgroup", "1:memory:/app\n2:cpu,cpuacct:/app\n3:cpuset:/app\n"),
            ("/proc/self/mountinfo", "1 2 0:1 / /sys/m rw - cgroup cg rw,memory\n2 2 0:2 / /sys/c rw - cgroup cg rw,cpu,cpuacct\n3 2 0:3 / /sys/s rw - cgroup cg rw,cpuset\n"),
            ("/sys/m/app/memory.limit_in_bytes", "9223372036854771712"),
            ("/sys/c/app/cpu.cfs_quota_us", "-1"),
            ("/sys/c/app/cpu.cfs_period_us", "100000"),
            ("/sys/s/app/cpuset.cpus", "2,4"),
        ]);
        let result = cgroup_from(
            |path| {
                files
                    .get(path.to_str().unwrap())
                    .map(|value| value.to_string())
                    .ok_or(io::Error::from(io::ErrorKind::NotFound))
            },
            true,
        );
        assert_eq!(result["version"], 1);
        assert!(result["memory_max_bytes"].is_null());
        assert!(result["cpu_quota_us"].is_null());
        assert_eq!(result["cpuset_cpus_effective"], "2,4");
        let unavailable = cgroup_from(|_| panic!("no file read on non-Linux"), false);
        assert!(unavailable["version"].is_null());
        assert!(unavailable["evidence"]["unavailable"].is_string());
    }

    #[actix_web::test]
    async fn mode_metadata_preserves_requests_without_fabricated_client_getters() {
        let requests = BTreeMap::from([
            ("acks".into(), json!(-1)),
            ("linger_ms".into(), json!(0)),
            ("producer_pool_size".into(), json!(2)),
        ]);
        let pipeline = Pipeline::new(crate::Limits::default(), None);
        for mode in [
            DeliveryMode::HttpOnly,
            DeliveryMode::Enqueue,
            DeliveryMode::Confirm,
        ] {
            let runtime = RuntimeMetadata::new(
                TelemetryProfile::Controlled,
                mode,
                2,
                if mode.uses_kafka() { 2 } else { 0 },
                requests.clone(),
                BTreeMap::from([("acks".into(), json!(-1))]),
            );
            let first = runtime.observe_worker();
            let second = runtime.observe_worker();
            let snapshot = runtime.snapshot(&pipeline);
            assert_eq!(snapshot["runtime"]["http_parallelism"]["effective"], 2);
            assert_eq!(snapshot["producer"]["controls"]["acks"]["requested"], -1);
            assert_eq!(
                snapshot["producer"]["controls"]["linger_ms"]["requested"],
                0
            );
            assert!(snapshot["producer"]["controls"]["acks"]["effective"].is_null());
            assert_eq!(
                snapshot["producer"]["controls"]["acks"]["status"],
                if mode.uses_kafka() {
                    "applied"
                } else {
                    "not_applicable"
                }
            );
            if !mode.uses_kafka() {
                assert_eq!(snapshot["producer"]["instances"], 0);
                assert!(snapshot["producer"]["controls"]["acks"]["applied"].is_null());
                assert!(
                    snapshot["producer"]["controls"]["acks"]["sdk_constructor_readback"].is_null()
                );
            }
            assert_eq!(snapshot["pipeline"]["http_outcome_timeout_ms"], 30000);
            assert_eq!(snapshot["telemetry"]["request_latency_histogram"], false);
            drop((first, second));
        }
        pipeline.shutdown().await;
    }

    #[test]
    fn sdk_validates_zero_linger_and_constructor_settings_without_network_or_clients() {
        for acks in ["0", "1", "-1"] {
            let (config, readback) = crate::producer_configuration(
                "localhost:9092",
                acks,
                0,
                16384,
                5000,
                0,
                100,
                0,
                TelemetryProfile::Controlled,
            );
            assert_eq!(readback["acks"], acks.parse::<i64>().unwrap());
            assert_eq!(readback["linger_ms"], 0);
            assert_eq!(readback["batch_bytes"], 16384);
            assert_eq!(readback["retries"], 0);
            assert_eq!(readback["retry_backoff_ms"], 100);
            assert_eq!(readback["request_timeout_ms"], 5000);
            assert_eq!(readback["message_timeout_ms"], 6000);
            assert_eq!(readback["queue_max_messages"], 1000000);
            let native = config.create_native_config().unwrap();
            assert_eq!(native.get("statistics.interval.ms").unwrap(), "0");
            assert!(native.get("debug").unwrap().is_empty());
        }
        // Preserve existing clamping while avoiding retries+1 overflow.
        assert_eq!(
            crate::compute_message_timeout_ms(5000, 0, u32::MAX, 100),
            u32::MAX
        );
    }

    #[actix_web::test]
    async fn real_actix_runtime_endpoint_observes_two_workers_without_producers() {
        use std::io::{Read, Write};
        let pipeline = Pipeline::new(crate::Limits::default(), None);
        let runtime = RuntimeMetadata::new(
            TelemetryProfile::Controlled,
            DeliveryMode::HttpOnly,
            2,
            0,
            BTreeMap::from([("acks".into(), json!(1)), ("linger_ms".into(), json!(0))]),
            BTreeMap::new(),
        );
        let state = crate::AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::HttpOnly,
            topic: "bids".into(),
            runtime: runtime.clone(),
            conformance: None,
        };
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = actix_web::HttpServer::new(move || {
            actix_web::App::new()
                .app_data(actix_web::web::Data::new(state.runtime.observe_worker()))
                .app_data(actix_web::web::Data::new(state.clone()))
                .route(
                    "/benchmark/runtime",
                    actix_web::web::get().to(crate::benchmark_runtime),
                )
        })
        .workers(2)
        .disable_signals()
        .listen(listener)
        .unwrap()
        .run();
        let handle = server.handle();
        let task = actix_web::rt::spawn(server);
        tokio::time::timeout(Duration::from_secs(5), async {
            while runtime.observed_workers.load(Ordering::Acquire) != 2 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("both real Actix workers observed");
        let snapshot: Value = tokio::task::spawn_blocking(move || {
            let mut socket = std::net::TcpStream::connect(address).unwrap();
            socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            socket.write_all(b"GET /benchmark/runtime HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").unwrap();
            let mut raw = String::new();
            socket.read_to_string(&mut raw).unwrap();
            assert!(raw.starts_with("HTTP/1.1 200"));
            serde_json::from_str(raw.split_once("\r\n\r\n").unwrap().1).unwrap()
        }).await.unwrap();
        assert_eq!(snapshot["runtime"]["pid"], std::process::id());
        assert_eq!(snapshot["runtime"]["http_parallelism"]["requested"], 2);
        assert_eq!(snapshot["runtime"]["http_parallelism"]["effective"], 2);
        assert_eq!(snapshot["producer"]["instances"], 0);
        assert_eq!(
            snapshot["producer"]["controls"]["acks"]["status"],
            "not_applicable"
        );
        assert_eq!(snapshot["telemetry"]["request_latency_histogram"], false);
        pipeline.shutdown().await;
        handle.stop(false).await;
        task.await.unwrap().unwrap();
        // Server completion may precede destruction of the worker App data.
        tokio::time::timeout(Duration::from_secs(5), async {
            while runtime.observed_workers.load(Ordering::Acquire) != 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("stopped worker application guards are released");
        assert_eq!(runtime.observed_workers.load(Ordering::Acquire), 0);
    }
}
