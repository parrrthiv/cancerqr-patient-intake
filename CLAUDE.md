# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

Spring Boot 3.2.1 / Java 17 application providing a WhatsApp-based cancer patient intake system plus a physician-facing dashboard and tumor board review workflow. Patients converse via WhatsApp, the system collects demographics + medical reports, runs a rule-based protocol engine, and surfaces cases to physicians for review and final protocol generation.

Production deployment: AWS EC2 (Docker) + RDS PostgreSQL + S3 + nginx reverse proxy with HTTPS at `https://testapi.cancerqr.com/api/...`.

## Build & Run Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean package

# Run application (default profile, H2 in-memory DB)
./mvnw spring-boot:run

# Run with local PostgreSQL profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-pg

# Run with production profile (PostgreSQL + S3, all env vars required)
./mvnw spring-boot:run -Dspring-boot.run.profiles=production

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=FormulaEngineTest

# Run a single test method
./mvnw test -Dtest=FormulaEngineTest#testMildPainPatientAnalysis
```

## Docker / Deployment

```bash
# Build the image
docker build -t cancerqr-app .

# Run with docker-compose (bundles a Postgres container; useful for local)
docker compose up -d

# Run standalone (production: against external RDS / S3)
docker run -d --name cancerqr -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=production \
  -e DB_HOST=... -e DB_PORT=5432 -e DB_NAME=oncologydb \
  -e DB_USERNAME=... -e DB_PASSWORD=... \
  -e STORAGE_TYPE=s3 -e S3_BUCKET_NAME=... -e AWS_REGION=... \
  -e AWS_ACCESS_KEY=... -e AWS_SECRET_KEY=... \
  -e WHATSAPP_PHONE_NUMBER_ID=... -e WHATSAPP_ACCESS_TOKEN=... \
  -e WHATSAPP_VERIFY_TOKEN=... \
  -e ENCRYPTION_KEY=... -e JWT_SECRET=... \
  cancerqr-app
```

Notes:
- `Dockerfile` is multi-stage: Maven build → `eclipse-temurin:17-jre`, runs as non-root `appuser`, exposes 8080.
- `deploy/setup-ec2.sh` installs Docker + Compose on Amazon Linux 2023 / Ubuntu 22.04.
- `deploy/env.example` is the env template — `cp` to `.env` for `docker compose`.
- nginx on the host terminates TLS for `testapi.cancerqr.com` and proxies to `localhost:8080`.
- **Webhook signature verification is currently disabled.** The controller accepts any POST to `/api/webhook/whatsapp`. To re-enable Meta's `X-Hub-Signature-256` HMAC check, add a `webhookSecret` field back to `WhatsAppConfig`, read the request body once as a raw `String`, deserialize it manually with `ObjectMapper`, and HMAC-SHA256 it against the App Secret. **Do not** declare both `@RequestBody WebhookPayload` and `@RequestBody String` on the same handler — the request body is a single non-rewindable stream and the second binding will be empty.

## Required Environment Variables (production profile)

Database (PostgreSQL via RDS):
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`

WhatsApp Business Cloud API:
- `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN`

Storage (S3):
- `STORAGE_TYPE=s3`, `S3_BUCKET_NAME`, `AWS_REGION`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`

Security / misc:
- `ENCRYPTION_KEY`, `JWT_SECRET`
- `AI_VERIFICATION_ENABLED`, `ANTHROPIC_API_KEY` (optional Claude review of analyses)

Default profile uses H2 in-memory and loose defaults — fine for `./mvnw spring-boot:run` but never deploy without `SPRING_PROFILES_ACTIVE=production`.

## Architecture

### Request flow (patient intake)

```
WhatsApp Cloud API
    │ POST /api/webhook/whatsapp  (HMAC-SHA256 signed)
    ▼
WhatsAppWebhookController
    │
    ▼
ConversationService (state machine)
    │
    ├─► PatientIntakeService          (persist Patient + Report rows)
    │       │
    │       ├─► StorageService        (S3 / local / MinIO)
    │       └─► ReportDataExtractionService (parse uploaded reports)
    │
    ├─► AnalysisService → FormulaEngine  (rule-based protocol from formula-rules.yml)
    │       │
    │       └─► AIVerificationService  (optional Claude review)
    │
    └─► WhatsAppClientService         (send replies / interactive buttons)
```

### Request flow (physician dashboard)

```
Browser (Thymeleaf templates)
    │
    ▼
DashboardController  (/api/dashboard/...)
    │
    ├─► PatientRepository / DoctorRepository / TumorBoardReviewRepository
    ├─► TumorBoardService             (8-domain physician review workflow)
    └─► CancerQRProtocolConfig        (loads cancerqr-protocols.yml)
```

The app runs at `/api` context path (`server.servlet.context-path`). All routes below are relative to `/api`.

## Key Components

**WhatsAppWebhookController** (`controller/WhatsAppWebhookController.java`)
- `GET /webhook/whatsapp` — Meta verification handshake (`hub.verify_token`).
- `POST /webhook/whatsapp` — incoming events. Signature verification is currently disabled; every POST is accepted and dispatched.
- Async-dispatches each message to `ConversationService`.

**ConversationService** (`service/ConversationService.java`)
- Central state machine with **14 states** (see `Patient.ConversationState`):
  `INITIAL → AWAITING_CONSENT → ASK_REFERRAL_CODE → ASK_CANCER_TYPE → ASK_AGE → ASK_WEIGHT → ASK_PAIN_SCALE → ASK_DIAGNOSIS_DATE → ASK_PET_SCAN → ASK_BLOOD_REPORT → PROCESSING → RESULT_SENT → COMPLETED → EXPIRED`
- Each state validates input (age 0–120, weight 1–300 kg, pain 0–10, date `YYYY-MM-DD`).
- Supports `START` / `RESTART` to reset.
- Prompt strings are constants at the top of the class — change wording there.

**PatientIntakeService** (`service/PatientIntakeService.java`)
- Persists `Patient` rows keyed by WhatsApp number (unique).
- Downloads media from WhatsApp, uploads via `StorageService`, creates `Report` rows.

**FormulaEngine** (`engine/FormulaEngine.java`)
- Loads `formula-rules.yml` at startup. Computes derived metrics (pain/weight/age categories, dose adjustments) and emits suggestions across 5 categories: CBD therapy, mono herbs, functional mushrooms, repurposed compounds, fasting/diet.
- Generates alerts for urgent cases (pediatric, severe pain, elderly, underweight, newly diagnosed).

**CancerQRProtocolConfig** (`config/CancerQRProtocolConfig.java`)
- Loads `cancerqr-protocols.yml` — matrix of 12 cancer types × 8 physician domains used to scaffold tumor board reviews.

**TumorBoardService** (`service/TumorBoardService.java`)
- Creates `TumorBoardReview` rows, tracks per-domain physician sign-offs, and produces a `FinalProtocol` once all domains approve.

**StorageService** (`service/StorageService.java`)
- Strategy over local filesystem, AWS S3, and MinIO. Backend selected by `storage.type` (`local` | `s3` | `minio`).

**WhatsAppClientService** (`service/WhatsAppClientService.java`)
- Reactive `WebClient` (Spring WebFlux) calling Meta Graph API. Sends text, interactive buttons/lists, and downloads media.

**DashboardController** (`controller/DashboardController.java`, ~720 lines)
- Thymeleaf-rendered admin/doctor dashboard.
- Routes: `/dashboard/login`, `/dashboard` (home), `/dashboard/patients`, `/dashboard/patients/{id}` (review), `/dashboard/protocol`, `/dashboard/doctors`, `POST /dashboard/patients/add` (referring-doctor flow).
- **Login currently uses plaintext password compare** (login at line ~83). Replace with hashed compare before any non-test deployment.

**AdminTestController** (`controller/AdminTestController.java`)
- Test-only endpoints — keep gated or removed in production.

## Data model

- **Patient** (`entity/Patient.java`) — WhatsApp number is the unique business key. Tracks demographics, intake flags (`intakeCompleted`, `petScanUploaded`, `bloodReportUploaded`), `ConversationState` enum, consent fields, and lazy `referringDoctor`. Has helper methods `addReport`, `addAnalysis`, `hasAllUploads`, `hasBasicInfo`.
- **Doctor** — referring physicians; can also be tumor board members.
- **Report** — uploaded medical document metadata + storage URI.
- **Analysis** — JSON-column suggestions from the formula engine (derived metrics, recommendations, alerts) with formula version for audit.
- **TumorBoardReview** — per-patient case with eight per-domain sign-off slots.
- **FinalProtocol** — the consensus protocol emitted after all domains approve.
- **AuditLog** — PHI-safe trail (IDs only, 28 action types).

## Configuration files

- `src/main/resources/application.yml` — three profiles:
  - default → H2 in-memory, dev defaults
  - `production` → PostgreSQL (`DB_HOST/PORT/NAME`), S3 storage, Flyway enabled, Hibernate `ddl-auto: validate`
  - `local-pg` → local PostgreSQL on `localhost:5432/oncologydb`
- `src/main/resources/formula-rules.yml` — dosing tables, herb/mushroom DB, alert rules.
- `src/main/resources/cancerqr-protocols.yml` — tumor board protocol matrix.
- `src/main/resources/db/migration/V*.sql` — Flyway migrations (V1 initial schema, V2 report extraction columns, V3 referring doctor).

## Patterns & conventions

- Lombok `@RequiredArgsConstructor` for constructor DI throughout.
- `@EnableAsync` on `CancerIntakeApplication`; webhook handling dispatches async.
- WebFlux `WebClient` for outbound WhatsApp calls — the app itself is Servlet/MVC, not reactive end-to-end.
- HMAC-SHA256 signature verification in the webhook controller (skip-on-empty-secret).
- All analysis outputs append a mandatory medical disclaimer (`app.disclaimer` in `application.yml`).
- `.bak` files exist next to several sources/templates — these are local backups, not used by the build.

## Testing

JUnit 5, no custom test infrastructure:
- `FormulaEngineTest` — nested `@Nested` classes covering derived metrics, alert generation, supportive care, disclaimers.
- `ConversationServiceTest` — Mockito-based state-machine + input-validation tests.
- `ReportDataExtractionServiceTest` — report parsing.

There is no integration test covering the WhatsApp webhook end-to-end; manual testing uses `curl_examples.sh` and `postman_collection.json`.

## Common change recipes

- **Reword a WhatsApp prompt** → `ConversationService` constants at top of file.
- **Add a new conversation state** → add to `Patient.ConversationState` enum, then update transitions in `ConversationService` and any persistence migrations.
- **Tune dosing rules / add an herb** → `formula-rules.yml`. No code change needed unless the YAML schema changes (then update `FormulaEngine`).
- **Add a cancer type or physician domain** → `cancerqr-protocols.yml` plus `CancerQRProtocolConfig`/`TumorBoardService` if new domain semantics are needed.
- **Database schema change** → add a Flyway migration `V<N>__<desc>.sql`. Production runs `ddl-auto: validate`, so the entity must match exactly.
- **Dashboard UI** → Thymeleaf templates in `src/main/resources/templates/dashboard/`.
- **Change login behavior** → `DashboardController` `loginSubmit` (~line 83). Replace plaintext compare with a hashed/BCrypt compare before any real deployment.
