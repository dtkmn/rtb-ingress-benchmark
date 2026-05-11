# MCP ZAP Receiver Seed Proof

This repository has a manual GitHub Actions pilot for the MCP ZAP Security Gate receiver path:

- Workflow: `.github/workflows/zap-security-gate.yml`
- App override: `.github/zap/docker-compose.quarkus-receiver.yml`
- Seed requests: `.zap/seed-requests/quarkus-receiver-bid-request.json`
- Default target: `http://app:8080`

The pilot starts the Quarkus receiver in `http-only` mode, then starts ZAP and the MCP ZAP server through the published CI Pack. It now sends representative `POST /bid-request` traffic through the ZAP proxy before collecting findings. That proves the app, ZAP, MCP server, seeded request replay, artifacts, baseline handling, and report flow can run together in a different repository.

Do not oversell this as exhaustive receiver security coverage. It proves the seeded request paths, not every auction payload, auth edge case, malformed input, or active attack path. Treat it as a product proof for MCP-backed seeded API scanning.

The first run is intentionally non-blocking:

- `baseline_mode`: `seed`
- `fail_on_new_findings`: `false`
- `run-active-scan`: `false`

Use this first mode to prove the integration, inspect the uploaded `zap-receiver-seed-proof` artifact, and decide whether the seed payloads are the right signal for this benchmark repo.

## Seeded Traffic

The seed file currently sends:

- an accepted bid request expected to return `200`
- a privacy-filtered bid request with `device.lmt = 1` expected to return `204`

The seed artifact sanitizes URLs before upload. Headers and bodies are not copied into CI artifacts, so payload details stay in the repository source instead of the uploaded run output.

## Moving To Enforced Receiver Proof

After the first reviewed seeded run:

1. Download the `zap-receiver-seed-proof` artifact from the workflow run.
2. Commit the reviewed `zap-artifacts/current-findings.json` as `.zap/baselines/quarkus-receiver-bid-request-seed.json`.
3. Run the workflow with:
   - `baseline_mode`: `enforce`
   - `fail_on_new_findings`: `true`

In `enforce` mode, the gate should fail if the baseline is missing or no auditable diff can be produced. That is intentional. A gate that passes because its baseline vanished is not a gate.

Enforced seeded proof still means enforced seeded proof. It only locks the selected base target and seed traffic. With `run-active-scan: false`, it should be treated as passive/API traffic evidence, not as a complete attack simulation.

## Toward Broader Receiver Coverage

The receiver surface that matters is `POST /bid-request` with representative JSON payloads. This workflow now covers that path through seeded requests, but broader coverage still needs one or more of:

- more seed payloads for malformed input, app-based requests, blocked IPs, and boundary values,
- OpenAPI-driven scanning if the receiver publishes a contract,
- active scan mode after we decide the CI runtime and target app can safely tolerate it.

Only after that should a baseline be described as broad receiver security coverage.

## Why This Uses MCP Instead Of Raw ZAP

This workflow does not involve an LLM. It uses the MCP server because the gateway provides the policy, baseline, evidence, report, and artifact contract around ZAP. Raw ZAP can scan the app, but it does not give this repo the same MCP-native security-gate behavior we are trying to pilot.
