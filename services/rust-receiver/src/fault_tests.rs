use super::*;
use serde_json::{json, Value};
use std::io::Read;

async fn wire(
    address: SocketAddr,
    method: &str,
    path: &str,
    body: Vec<u8>,
    token: bool,
) -> (u16, Vec<u8>) {
    let method = method.to_string();
    let path = path.to_string();
    tokio::task::spawn_blocking(move || {
        let mut socket=TcpStream::connect(address).unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let auth=if token {"X-Conformance-Token: focused-test-token\r\n"} else {""};
        write!(socket,"{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\n{auth}Content-Length: {}\r\n\r\n",body.len()).unwrap();
        socket.write_all(&body).unwrap();
        let mut raw=Vec::new(); socket.read_to_end(&mut raw).unwrap();
        let split=raw.windows(4).position(|part|part==b"\r\n\r\n").unwrap();
        let status=std::str::from_utf8(&raw[..split]).unwrap().split_ascii_whitespace().nth(1).unwrap().parse().unwrap();
        (status,raw[split+4..].to_vec())
    }).await.unwrap()
}

async fn control(address: SocketAddr, command: Value) -> Value {
    let (status, body) = wire(
        address,
        "POST",
        "/benchmark/conformance",
        serde_json::to_vec(&command).unwrap(),
        true,
    )
    .await;
    assert_eq!(status, 200, "{}", String::from_utf8_lossy(&body));
    serde_json::from_slice(&body).unwrap()
}

async fn observed(address: SocketAddr, predicate: impl Fn(&Value) -> bool) -> Value {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let (status, body) =
                wire(address, "GET", "/benchmark/conformance", Vec::new(), true).await;
            assert_eq!(status, 200);
            let value = serde_json::from_slice(&body).unwrap();
            if predicate(&value) {
                return value;
            }
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .expect("externally observed actual fault milestone")
}

async fn run_faults(mode: DeliveryMode) {
    let controller = conformance::Controller::new(
        mode,
        "configured-real-topic".into(),
        "focused-test-token".into(),
    );
    let pipeline = Pipeline::new(
        Limits {
            http_outcome_timeout_ms: 250,
            drain_timeout_ms: 40,
            ..Limits::default()
        },
        if mode.uses_kafka() {
            Some(controller.clone())
        } else {
            None
        },
    );
    controller.attach(&pipeline);
    let runtime = RuntimeMetadata::for_test(mode);
    if mode.uses_kafka() {
        runtime.enable_validation_transport();
    }
    let state = AppState {
        pipeline: pipeline.clone(),
        delivery_mode: mode,
        topic: "configured-real-topic".into(),
        runtime,
        conformance: Some(controller),
    };
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = HttpServer::new(move || {
        App::new()
            .app_data(web::Data::new(state.clone()))
            .wrap(from_fn(preparation_gate))
            .route("/bid-request", web::post().to(receive_bid))
            .route(
                "/benchmark/conformance",
                web::get().to(benchmark_conformance),
            )
            .route(
                "/benchmark/conformance",
                web::post().to(benchmark_conformance),
            )
            .route("/benchmark/runtime", web::get().to(benchmark_runtime))
    })
    .workers(1)
    .disable_signals()
    .listen(listener)
    .unwrap()
    .run();
    let handle = server.handle();
    let server_task = actix_web::rt::spawn(server);
    let corpus: Value =
        serde_json::from_str(include_str!("../../../tests/conformance/corpus.json")).unwrap();
    let mut cases: Vec<_> = corpus["cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|case| case.get("scenario").is_some())
        .collect();
    assert_eq!(cases.len(), 18);
    cases.sort_by_key(|case| case["scenario"]["fault"] == "drain_deadline_unsettled");
    let mode_name = match mode {
        DeliveryMode::Confirm => "confirm",
        DeliveryMode::Enqueue => "enqueue",
        DeliveryMode::HttpOnly => "http-only",
    };
    for case in cases {
        let fault = case["scenario"]["fault"].as_str().unwrap();
        if fault == "confirm_ack_zero" && mode == DeliveryMode::Confirm {
            continue;
        } // real unsupported startup is a separate process gate
        let configured = control(
            address,
            json!({"action":"configure","case_id":case["id"],"fault":fault}),
        )
        .await;
        let baseline = configured["baseline"].clone();
        let raw = case["request"]["body"]["data"]
            .as_str()
            .unwrap()
            .as_bytes()
            .to_vec();
        let race = fault == "final_slot_race" && mode.uses_kafka();
        let mut tasks = vec![actix_web::rt::spawn(wire(
            address,
            "POST",
            "/bid-request",
            raw.clone(),
            false,
        ))];
        if race {
            let second = String::from_utf8(raw)
                .unwrap()
                .replace("req-1-0", "req-1-1")
                .into_bytes();
            tasks.push(actix_web::rt::spawn(wire(
                address,
                "POST",
                "/bid-request",
                second,
                false,
            )));
        }
        if mode.uses_kafka()
            && matches!(
                fault,
                "held_admission_then_success"
                    | "held_admission_then_rejection"
                    | "admission_deadline_then_late_receipt"
                    | "final_slot_race"
            )
        {
            let count = if race { 2 } else { 1 };
            let state = observed(address, |state| state["held_admission"] == count).await;
            assert!(tasks.iter().all(|task| !task.is_finished()));
            assert_eq!(state["state"]["admission_undecided"], count);
            if fault == "admission_deadline_then_late_receipt" {
                observed(address, |state| state["state"]["admission_undecided"] == 0).await;
                observed(address, |_| tasks[0].is_finished()).await;
                let held = observed(address, |state| state["deadline_elapsed"] == true).await;
                assert_eq!(
                    held["state"]["preparation_count"].as_u64().unwrap()
                        - baseline["preparation_count"].as_u64().unwrap(),
                    1
                );
                assert_eq!(
                    held["state"]["admission_rejected"].as_u64().unwrap()
                        - baseline["admission_rejected"].as_u64().unwrap(),
                    1
                );
                assert_eq!(held["state"]["outstanding"], baseline["outstanding"]);
                let state = control(address, json!({"action":"release_admission"})).await;
                assert_eq!(
                    state["state"]["preparation_count"],
                    baseline["preparation_count"]
                );
                assert_eq!(state["deadline_elapsed"], true);
                assert!(state["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|event| event["name"] == "late_receipt_fenced"));
            } else {
                control(address,json!({"action":if fault=="held_admission_then_rejection"{"reject_admission"}else{"release_admission"}})).await;
            }
        }
        let holds = mode.uses_kafka()
            && matches!(
                fault,
                "held_admission_then_success"
                    | "held_delivery_then_success"
                    | "delivery_failure_after_receipt"
                    | "response_deadline_then_late_success"
                    | "final_slot_race"
                    | "drain_deadline_unsettled"
                    | "duplicate_completion_callback"
                    | "source_mutation_after_receipt"
            );
        if holds {
            let held = observed(address, |state| state["held_delivery"] == 1).await;
            assert_eq!(
                held["state"]["outstanding"].as_i64().unwrap()
                    - baseline["outstanding"].as_i64().unwrap(),
                1
            );
            assert_eq!(held["state"]["client_failed"], baseline["client_failed"]);
            if mode == DeliveryMode::Confirm {
                assert!(tasks.iter().any(|task| !task.is_finished()));
            } else {
                observed(address, |_| tasks.iter().any(|task| task.is_finished())).await;
            }
            if fault == "source_mutation_after_receipt" {
                assert!(held["records"].as_array().unwrap().is_empty());
                let changed = control(address, json!({"action":"mutate_source"})).await;
                assert_eq!(changed["mutation"]["performed"], true);
                assert_ne!(
                    changed["mutation"]["before_value_base64"],
                    changed["mutation"]["after_value_base64"]
                );
            }
            if fault == "response_deadline_then_late_success" {
                observed(address, |state| state["deadline_elapsed"] == true).await;
                control(address, json!({"action":"release_delivery"})).await;
            } else if fault == "drain_deadline_unsettled" {
                let drained = control(address, json!({"action":"drain"})).await;
                assert_eq!(drained["drain"]["before"]["outstanding"], 1);
                assert_eq!(drained["drain"]["after"]["abandoned_unknown"], 1);
                assert_eq!(drained["drain"]["after"]["drain_result"], "timed_out");
                assert_eq!(drained["drain"]["after"]["teardown_confirmed"], true);
                let teardown = drained["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|event| event["name"] == "teardown_confirmed")
                    .unwrap();
                assert_eq!(teardown["state"]["outstanding"], 1);
                assert_eq!(teardown["state"]["abandoned_unknown"], 0);
            } else {
                control(address,json!({"action":if fault=="delivery_failure_after_receipt"{"fail_delivery"}else{"release_delivery"}})).await;
            }
            if fault != "drain_deadline_unsettled" {
                observed(address, |state| {
                    state["state"]["outstanding"] == baseline["outstanding"]
                })
                .await;
            }
            if fault == "duplicate_completion_callback" {
                let duplicate = control(address, json!({"action":"duplicate_completion"})).await;
                let event = duplicate["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|event| event["name"] == "duplicate_completion")
                    .unwrap();
                assert_eq!(event["applied"], false);
                assert_eq!(event["before"], event["after"]);
            }
        }
        let mut statuses = Vec::new();
        for task in tasks {
            let (status, body) = task.await.unwrap();
            statuses.push(status);
            if status == 200 {
                assert_eq!(
                    serde_json::from_slice::<Value>(&body).unwrap(),
                    json!({"status":"accepted"})
                );
            }
        }
        let expected = &case["expectations"][mode_name];
        if race {
            statuses.sort();
            assert_eq!(statuses, vec![200, 503]);
        } else {
            assert_eq!(
                u64::from(statuses[0]),
                expected["status"].as_u64().unwrap(),
                "{} {mode_name}",
                case["id"]
            );
        }
        let state = observed(address, |state| {
            state["state"]["preparation_count"] == baseline["preparation_count"]
        })
        .await;
        for (field, value) in expected["accounting"].as_object().unwrap() {
            let mut delta =
                state["state"][field].as_i64().unwrap() - baseline[field].as_i64().unwrap();
            if race
                && ["admission_candidates", "admitted", "client_succeeded"]
                    .contains(&field.as_str())
            {
                delta -= 1;
            }
            assert_eq!(
                delta,
                value.as_i64().unwrap(),
                "{} {mode_name} {field}",
                case["id"]
            );
        }
        if let Some(expected) = expected.get("record") {
            let records = state["records"].as_array().unwrap();
            assert_eq!(records.len(), 1);
            assert_eq!(
                conformance::base64(expected["key"].as_str().unwrap().as_bytes()),
                records[0]["key_base64"]
            );
            let value = crate::conformance_tests::decode_base64(
                records[0]["value_base64"].as_str().unwrap(),
            );
            assert_eq!(
                serde_json::from_slice::<Value>(&value).unwrap(),
                expected["value"]
            );
            assert_eq!(records[0]["topic"], "configured-real-topic");
        }
        if fault == "backlog_byte_limit" && mode.uses_kafka() {
            assert_eq!(configured["setup"]["bytes"], 16777215);
            assert_eq!(configured["setup"]["records"], 256);
        }
        if race {
            assert_eq!(configured["setup"]["records"], 1023);
            assert_eq!(baseline["outstanding"], 1023);
            assert_eq!(state["records"].as_array().unwrap().len(), 1);
        }
        if fault == "preparation_limit" {
            assert_eq!(configured["setup"]["preparations"], 128);
        }
        if fault != "drain_deadline_unsettled" {
            let cleaned = control(address, json!({"action":"cleanup"})).await;
            assert_eq!(cleaned["state"]["outstanding"], 0);
            assert_eq!(cleaned["state"]["preparation_count"], 0);
        }
    }
    let (status, body) = wire(address, "GET", "/benchmark/runtime", Vec::new(), false).await;
    assert_eq!(status, 200);
    let runtime: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(runtime["producer"]["instances"], 0);
    assert_eq!(
        runtime["producer"]["validation_transport"],
        mode.uses_kafka()
    );
    let before = pipeline.snapshot();
    let after = pipeline.shutdown().await;
    if mode.uses_kafka() {
        assert_eq!(before["drain_result"], after["drain_result"]);
        assert_eq!(after["abandoned_unknown"], 1);
    }
    handle.stop(false).await;
    server_task.await.unwrap().unwrap();
}

#[actix_web::test]
async fn all_fault_http_only() {
    run_faults(DeliveryMode::HttpOnly).await;
}
#[actix_web::test]
async fn all_fault_enqueue() {
    run_faults(DeliveryMode::Enqueue).await;
}
#[actix_web::test]
async fn all_fault_confirm() {
    run_faults(DeliveryMode::Confirm).await;
}

#[actix_web::test]
async fn control_authorization_precedes_parsing_and_disabled_allocates_no_controller() {
    for enabled in [false, true] {
        let pipeline = Pipeline::new(Limits::default(), None);
        let controller = enabled.then(|| {
            conformance::Controller::new(
                DeliveryMode::HttpOnly,
                "bids".into(),
                "focused-test-token".into(),
            )
        });
        if let Some(controller) = &controller {
            controller.attach(&pipeline);
        }
        let state = AppState {
            pipeline: pipeline.clone(),
            delivery_mode: DeliveryMode::HttpOnly,
            topic: "bids".into(),
            runtime: RuntimeMetadata::for_test(DeliveryMode::HttpOnly),
            conformance: controller,
        };
        let app = actix_web::test::init_service(App::new().app_data(web::Data::new(state)).route(
            "/benchmark/conformance",
            web::post().to(benchmark_conformance),
        ))
        .await;
        for authorized in [false, true] {
            let mut request = actix_web::test::TestRequest::post()
                .uri("/benchmark/conformance")
                .set_payload("not json");
            if authorized {
                request = request.insert_header(("x-conformance-token", "focused-test-token"));
            }
            let status = actix_web::test::call_service(&app, request.to_request())
                .await
                .status();
            assert_eq!(status, if enabled && authorized { 409 } else { 404 });
            assert_eq!(pipeline.snapshot()["admission_candidates"], 0);
        }
        if enabled {
            let request = actix_web::test::TestRequest::post()
                .uri("/benchmark/conformance")
                .insert_header(("x-conformance-token", "focused-test-token"))
                .set_payload(vec![b'x'; 8193]);
            assert_eq!(
                actix_web::test::call_service(&app, request.to_request())
                    .await
                    .status(),
                413
            );
        }
        pipeline.shutdown().await;
    }
}

#[actix_web::test]
async fn cancelled_admission_future_releases_observer_and_rejects_real_candidate() {
    let controller = conformance::Controller::new(
        DeliveryMode::Enqueue,
        "bids".into(),
        "focused-test-token".into(),
    );
    let pipeline = Pipeline::new(Limits::default(), Some(controller.clone()));
    controller.attach(&pipeline);
    controller.command(json!({"action":"configure", "case_id":"cancel-test", "fault":"held_admission_then_success"})).await.unwrap();
    let candidate = pipeline.candidate();
    let preparation = pipeline.prepare().unwrap();
    let owner = controller.clone();
    let task = actix_web::rt::spawn(async move {
        owner
            .admit(
                candidate,
                OwnedRecord {
                    topic: "bids".into(),
                    key: b"cancel-test".to_vec(),
                    value: b"{}".to_vec(),
                },
                preparation,
            )
            .await
    });
    while controller.snapshot()["held_admission"] != 1 {
        tokio::task::yield_now().await;
    }
    task.abort();
    assert!(matches!(task.await, Err(error) if error.is_cancelled()));
    let state = controller.snapshot();
    assert_eq!(state["held_admission"], 0);
    assert_eq!(state["state"]["admission_undecided"], 0);
    assert_eq!(state["state"]["preparation_count"], 0);
    assert_eq!(state["state"]["admission_rejected"], 1);
    assert_eq!(state["state"]["admitted"], 0);
    controller
        .command(json!({"action":"cleanup"}))
        .await
        .unwrap();
    pipeline.shutdown().await;
}
