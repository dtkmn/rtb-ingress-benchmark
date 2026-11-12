use super::*;
use serde_json::Value;

struct RecordingTransport(Mutex<Vec<Arc<OwnedRecord>>>);

impl Transport for RecordingTransport {
    fn dispatch(self: Arc<Self>, record: Arc<OwnedRecord>) -> DispatchFuture {
        Box::pin(async move {
            self.0.lock().unwrap().push(record);
            true
        })
    }

    fn teardown(self: Arc<Self>) -> TeardownFuture {
        Box::pin(async { Ok(()) })
    }
}

pub(super) fn decode_base64(raw: &str) -> Vec<u8> {
    let mut output = Vec::new();
    let mut accumulator = 0u32;
    let mut bits = 0;
    for byte in raw.bytes().take_while(|byte| *byte != b'=') {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => panic!("invalid fixture base64"),
        };
        accumulator = (accumulator << 6) | u32::from(value);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            output.push((accumulator >> bits) as u8);
            accumulator &= (1 << bits) - 1;
        }
    }
    output
}

#[actix_web::test]
async fn all_52_normal_corpus_cases_in_three_modes_and_record_intents() {
    let corpus: Value =
        serde_json::from_str(include_str!("../../../tests/conformance/corpus.json")).unwrap();
    let cases: Vec<_> = corpus["cases"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|case| case["category"] != "fault")
        .collect();
    assert_eq!(cases.len(), 52);
    for mode in [
        DeliveryMode::HttpOnly,
        DeliveryMode::Confirm,
        DeliveryMode::Enqueue,
    ] {
        let mode_name = match mode {
            DeliveryMode::HttpOnly => "http-only",
            DeliveryMode::Confirm => "confirm",
            DeliveryMode::Enqueue => "enqueue",
        };
        for case in &cases {
            let id = case["id"].as_str().unwrap();
            let transport = Arc::new(RecordingTransport(Mutex::new(Vec::new())));
            let pipeline = Pipeline::new(
                Limits::default(),
                if mode.uses_kafka() {
                    Some(transport.clone())
                } else {
                    None
                },
            );
            let state = AppState {
                pipeline: pipeline.clone(),
                delivery_mode: mode,
                runtime: RuntimeMetadata::for_test(mode),
                conformance: None,
                topic: "configured-topic".into(),
            };
            let app = actix_web::test::init_service(
                App::new()
                    .app_data(web::Data::new(state))
                    .wrap(from_fn(preparation_gate))
                    .route("/bid-request", web::post().to(receive_bid)),
            )
            .await;
            let fixture = &case["request"];
            let data = fixture["body"]["data"].as_str().unwrap();
            let body = if fixture["body"]["encoding"] == "base64" {
                decode_base64(data)
            } else {
                data.as_bytes().to_vec()
            };
            let mut request = actix_web::test::TestRequest::post().uri("/bid-request");
            for header in fixture["headers"].as_array().unwrap() {
                request = request.append_header((
                    header["name"].as_str().unwrap(),
                    header["value"].as_str().unwrap(),
                ));
            }
            let response =
                actix_web::test::call_service(&app, request.set_payload(body).to_request()).await;
            let expected = &case["expectations"][mode_name];
            let status = response.status().as_u16();
            assert_eq!(
                u64::from(status),
                expected["status"].as_u64().unwrap(),
                "{id} {mode_name}"
            );
            let body = actix_web::test::read_body(response).await;
            if status == 200 {
                assert_eq!(
                    serde_json::from_slice::<Value>(&body).unwrap(),
                    serde_json::json!({"status":"accepted"}),
                    "{id} {mode_name}"
                );
            }
            if status == 204 {
                assert!(body.is_empty(), "{id} {mode_name}");
            }
            for _ in 0..100 {
                if pipeline.snapshot()["outstanding"] == 0 {
                    break;
                }
                tokio::task::yield_now().await;
            }
            let snapshot = pipeline.snapshot();
            for (field, count) in expected["accounting"].as_object().unwrap() {
                assert_eq!(&snapshot[field], count, "{id} {mode_name} {field}");
            }
            assert_eq!(snapshot["preparation_count"], 0, "{id} {mode_name}");
            assert_eq!(snapshot["outstanding_bytes"], 0, "{id} {mode_name}");
            let records = transport.0.lock().unwrap();
            assert_eq!(
                records.len() as u64,
                expected["accounting"]["admitted"].as_u64().unwrap(),
                "{id} {mode_name}"
            );
            if expected.get("record").is_some() {
                assert_eq!(
                    records[0].topic,
                    expected["record"]["topic"].as_str().unwrap(),
                    "{id}"
                );
                assert_eq!(
                    records[0].key,
                    expected["record"]["key"].as_str().unwrap().as_bytes(),
                    "{id}"
                );
                assert_eq!(
                    serde_json::from_slice::<Value>(&records[0].value).unwrap(),
                    expected["record"]["value"],
                    "{id}"
                );
            }
            drop(records);
            pipeline.shutdown().await;
        }
    }
}

#[test]
fn raw_number_is_not_rounded_by_serde_before_validation() {
    for (token, expected) in [
        (format!("0e{}", "9".repeat(6000)), Some(0)),
        ("1000e-3".into(), Some(1)),
        ("0.001e3".into(), Some(1)),
        ("1.000000000000000001".into(), None),
        (format!("1e{}", "9".repeat(6000)), None),
    ] {
        let text = format!(r#"{{"id":"id","site":{{}},"device":{{"lmt":{token}}}}}"#);
        let result = serde_json::from_str::<BidRequest>(&text);
        match expected {
            Some(value) => assert_eq!(result.unwrap().device.unwrap().lmt, value),
            None => assert!(result.is_err(), "{token}"),
        }
    }
}

#[actix_web::test]
async fn header_errors_precede_occupied_preparation_without_rejection_count() {
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
            .wrap(from_fn(preparation_gate))
            .route("/bid-request", web::post().to(receive_bid)),
    )
    .await;
    let lease = pipeline.prepare().unwrap();
    for headers in [
        vec![("content-type", "text/plain")],
        vec![
            ("content-type", "application/json"),
            ("content-type", "application/json"),
        ],
        vec![
            ("content-type", "application/json"),
            ("content-encoding", "identity"),
            ("content-encoding", "identity"),
        ],
    ] {
        let mut request = actix_web::test::TestRequest::post().uri("/bid-request");
        for (name, value) in headers {
            request = request.append_header((name, value));
        }
        let response = actix_web::test::call_service(
            &app,
            request.set_payload(vec![b' '; 65537]).to_request(),
        )
        .await;
        assert_eq!(response.status(), 415);
        assert_eq!(pipeline.snapshot()["preparation_rejected"], 0);
        assert_eq!(pipeline.snapshot()["preparation_count"], 1);
    }
    let request = actix_web::test::TestRequest::post()
        .uri("/bid-request")
        .append_header(("content-type", "application/json"))
        .set_payload("{")
        .to_request();
    assert_eq!(
        actix_web::test::call_service(&app, request).await.status(),
        503
    );
    assert_eq!(pipeline.snapshot()["preparation_rejected"], 1);
    assert_eq!(pipeline.snapshot()["admission_candidates"], 0);
    lease.release();
    pipeline.shutdown().await;
}

fn raw_status(address: SocketAddr, headers: &[u8], body: &[u8]) -> u16 {
    let mut socket = TcpStream::connect(address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    socket
        .write_all(b"POST /bid-request HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
        .unwrap();
    socket.write_all(headers).unwrap();
    socket.write_all(b"\r\n").unwrap();
    socket.write_all(body).unwrap();
    let mut status = String::new();
    BufReader::new(socket).read_line(&mut status).unwrap();
    status
        .split_ascii_whitespace()
        .nth(1)
        .unwrap()
        .parse()
        .unwrap()
}

#[actix_web::test]
async fn actual_http_duplicate_headers_precedence_and_chunked_size_limit() {
    let pipeline = Pipeline::new(Limits::default(), None);
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
            .wrap(from_fn(preparation_gate))
            .route("/bid-request", web::post().to(receive_bid))
    })
    .workers(1)
    .disable_signals()
    .listen(listener)
    .unwrap()
    .run();
    let handle = server.handle();
    let server_task = actix_web::rt::spawn(server);
    tokio::task::spawn_blocking(move || {
        let body = br#"{"id":"id","site":{},"device":{}}"#;
        let length = format!("Content-Length: {}\r\n", body.len());
        let duplicated_type = format!("Content-Type: application/json\r\nContent-Type: application/json\r\n{length}");
        assert_eq!(raw_status(address, duplicated_type.as_bytes(), body), 415);
        let duplicated_encoding = format!("Content-Type: application/json\r\nContent-Encoding: identity\r\nContent-Encoding: identity\r\n{length}");
        assert_eq!(raw_status(address, duplicated_encoding.as_bytes(), body), 415);
        assert_eq!(raw_status(address, b"Content-Type: text/plain\r\nContent-Length: 65537\r\n", b""), 415);
        for (length, expected) in [(65536, 200), (65537, 413)] {
            let mut padded = body.to_vec();
            padded.resize(length, b' ');
            let mut encoded = Vec::new();
            for chunk in padded.chunks(32768) {
                encoded.extend(format!("{:x}\r\n", chunk.len()).as_bytes());
                encoded.extend(chunk);
                encoded.extend(b"\r\n");
            }
            encoded.extend(b"0\r\n\r\n");
            assert_eq!(raw_status(address, b"Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n", &encoded), expected);
        }
    }).await.unwrap();
    let failure = Arc::new(AtomicBool::new(true));
    stop_after_drain(pipeline.clone(), handle, failure.clone()).await;
    server_task.await.unwrap().unwrap();
    assert!(!failure.load(Ordering::Acquire));
    assert_eq!(pipeline.snapshot()["preparation_count"], 0);
    assert_eq!(pipeline.snapshot()["admission_candidates"], 0);
}
