# MCP ZAP Wiring Smoke Pilot

This repository has a manual GitHub Actions pilot for the MCP ZAP Security Gate wiring path:

- Workflow: `.github/workflows/zap-security-gate.yml`
- App override: `.github/zap/docker-compose.quarkus-receiver.yml`
- Default target: `http://app:8080/q/health/ready`

The pilot starts the Quarkus receiver in `http-only` mode, then starts ZAP and the MCP ZAP server through the published CI Pack. The default target is the readiness endpoint, so the default run is a wiring smoke proof only. It proves the app, ZAP, MCP server, artifacts, baseline handling, and report flow can run together in a different repository.

It does not prove security coverage for the benchmark's real `POST /bid-request` surface. Do not sell a green readiness run as application security coverage. That would be theatre.

The first run is intentionally non-blocking:

- `baseline_mode`: `seed`
- `fail_on_new_findings`: `false`
- `run-active-scan`: `false`

Use this first mode to prove the integration, inspect the uploaded `zap-wiring-smoke` artifact, and decide whether the target is the right signal for a benchmark repo.

## Moving To Enforced Smoke

After the first reviewed run:

1. Download the `zap-wiring-smoke` artifact from the workflow run.
2. Commit the reviewed `zap-artifacts/current-findings.json` as `.zap/baselines/quarkus-receiver-wiring-smoke.json`.
3. Run the workflow with:
   - `baseline_mode`: `enforce`
   - `fail_on_new_findings`: `true`

In `enforce` mode, the gate should fail if the baseline is missing or no auditable diff can be produced. That is intentional. A gate that passes because its baseline vanished is not a gate.

Enforced smoke still means enforced smoke. It only locks the selected target. With the default readiness target and `run-active-scan: false`, it should be treated as integration evidence, not as a meaningful security baseline for `POST /bid-request`.

## Toward Real Receiver Coverage

The receiver surface that matters is `POST /bid-request` with representative JSON payloads. The current reusable CI Pack input is URL-based, so this repo should not pretend it has full receiver coverage until one of these is true:

- the CI Pack supports seeded HTTP requests or OpenAPI-driven scanning,
- the receiver exposes a safe scan fixture that ZAP can crawl into the bid request path,
- or this repo adds a dedicated pre-scan seeding step that drives `POST /bid-request` through ZAP before findings are captured.

Only after that should a baseline be described as receiver security coverage.

## Why This Uses MCP Instead Of Raw ZAP

This workflow does not involve an LLM. It uses the MCP server because the gateway provides the policy, baseline, evidence, report, and artifact contract around ZAP. Raw ZAP can scan the app, but it does not give this repo the same MCP-native security-gate behavior we are trying to pilot.
