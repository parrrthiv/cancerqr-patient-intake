# CancerQR Patient Intake

Spring Boot 3.2.1 / Java 17 backend that runs a WhatsApp-based intake flow for cancer patients, stores their medical reports, generates a rule-based protocol suggestion, and exposes a physician dashboard with a tumor-board review workflow.

> **Medical disclaimer**: All suggestions are computer-generated initial assessments. They are not prescriptions and must be reviewed by a qualified oncologist before any treatment is started, stopped, or changed.

---

## What it does

- **WhatsApp intake bot** — patients message the bot and walk through a 14-state conversation that collects consent, referral code, cancer type, age, weight, pain scale, diagnosis date, and uploads (PET scan + blood report).
- **Rule-based protocol engine** — `formula-rules.yml` defines dosing tables, herb/mushroom selections, alert thresholds, and supportive care. The engine derives metrics (pain/weight/age categories) and emits a per-patient suggestion with mandatory disclaimers.
- **Tumor board review** — once intake completes, the case is queued for a multi-domain (8 physician domains) review. A `FinalProtocol` is produced after sign-off.
- **Physician dashboard** — Thymeleaf-rendered admin/doctor UI for triaging cases, adding patients on behalf of referring doctors, reviewing analyses, and managing physicians.
- **Pluggable storage** — local filesystem, AWS S3, or MinIO for uploaded reports.
- **Optional AI verification** — Anthropic Claude can review generated analyses if `ANTHROPIC_API_KEY` is set.

---

## Architecture

```
WhatsApp Cloud API
    │  POST /api/webhook/whatsapp
    ▼
WhatsAppWebhookController
    │
    ▼
ConversationService  (14-state machine)
    │
    ├─► PatientIntakeService     ──►  StorageService  (S3 / local / MinIO)
    │                                  ReportDataExtractionService
    │
    ├─► AnalysisService  ──►  FormulaEngine   (formula-rules.yml)
    │                          │
    │                          └─►  AIVerificationService (optional Claude review)
    │
    └─► WhatsAppClientService   (replies, interactive buttons, media)

DashboardController  (/api/dashboard/*)
    └─► TumorBoardService  ──►  CancerQRProtocolConfig (cancerqr-protocols.yml)
```

The app runs on `/api` (Spring `server.servlet.context-path`). All HTTP routes below are relative to `/api`.

---

## Prerequisites

- Java 17 / Maven 3.8+
- PostgreSQL 14+ for production (H2 in-memory works out of the box for local dev)
- WhatsApp Business Cloud API credentials (Meta Developer Console)
- An S3 bucket if you want production-grade storage

---

## Quick start (local, H2)

```bash
git clone https://github.com/parrrthiv/cancerqr-patient-intake.git
cd cancerqr-patient-intake

./mvnw clean package -DskipTests
./mvnw spring-boot:run
```

App listens on `http://localhost:8080/api`. Health check: `GET /api/actuator/health`.

To make the WhatsApp webhook reachable from Meta during development, expose it with ngrok:

```bash
ngrok http 8080
# Use https://<id>.ngrok.io/api/webhook/whatsapp as the Meta Callback URL
```

---

## Quick start (Docker)

The repo ships a multi-stage `Dockerfile` and a `docker-compose.yml` that bundles a Postgres container.

```bash
cp deploy/env.example .env
# fill in DB_PASSWORD, WhatsApp creds, S3 bucket / region (use IAM role on EC2)

docker compose up -d --build
docker compose logs -f app
```

Or run the image standalone against an external database:

```bash
docker build -t cancerqr-app .

docker run -d --name cancerqr -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=production \
  -e DB_HOST=your-rds-endpoint -e DB_PORT=5432 -e DB_NAME=oncologydb \
  -e DB_USERNAME=postgres -e DB_PASSWORD=... \
  -e STORAGE_TYPE=s3 -e S3_BUCKET_NAME=... -e AWS_REGION=us-east-1 \
  -e AWS_ACCESS_KEY=... -e AWS_SECRET_KEY=... \
  -e WHATSAPP_PHONE_NUMBER_ID=... -e WHATSAPP_ACCESS_TOKEN=... \
  -e WHATSAPP_VERIFY_TOKEN=... -e WHATSAPP_WEBHOOK_SECRET=... \
  cancerqr-app
```

---

## Configuration

`src/main/resources/application.yml` defines three profiles:

| Profile | DB | Storage | Used for |
|---|---|---|---|
| _(default)_ | H2 in-memory | local filesystem | `./mvnw spring-boot:run` (dev) |
| `local-pg` | PostgreSQL on `localhost:5432` | local filesystem | running against a local Postgres |
| `production` | PostgreSQL via `DB_HOST` / `DB_PORT` / `DB_NAME` | S3 | RDS / EC2 deployment |

Production profile expects these environment variables:

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `STORAGE_TYPE=s3`, `S3_BUCKET_NAME`, `AWS_REGION`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | S3 storage |
| `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_WEBHOOK_SECRET` | WhatsApp Cloud API. `WEBHOOK_SECRET` is the Meta App Secret used to HMAC-verify inbound webhooks; leave empty in dev to skip the check, always set it in production. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of dashboard origins (e.g. `https://testapi.cancerqr.com`). Required in production. |
| `SESSION_COOKIE_SECURE` | Forced to `true` by the production profile; only override locally when debugging over plain HTTP. |
| `PHI_ENCRYPTION_KEY` | Base64-encoded 32-byte AES-256 key used to encrypt PHI columns (`patient.name`, `doctor.full_name`, `doctor.email`, `doctor.phone`) at rest. Generate with `openssl rand -base64 32`. **Required in production**; loss of this key = permanent loss of every encrypted PHI value. |
| `AI_VERIFICATION_ENABLED`, `ANTHROPIC_API_KEY` | Optional Claude review |

`deploy/env.example` is a template — copy it to `.env` for `docker compose`.

### Rule files

- `src/main/resources/formula-rules.yml` — dosing tables, herb/mushroom database, alert rules.
- `src/main/resources/cancerqr-protocols.yml` — cancer-type × physician-domain protocol matrix used by the tumor board.

Tune medical content here without touching Java code.

### Database migrations

Flyway runs on the `production` and `local-pg` profiles (`spring.flyway.enabled=true`, `ddl-auto=validate`). Migrations live in `src/main/resources/db/migration/V*.sql`.

---

## WhatsApp setup

1. Create a Meta Business app and add the **WhatsApp** product.
2. Generate a **System User Access Token** with `whatsapp_business_messaging` permission. Put it in `WHATSAPP_ACCESS_TOKEN`.
3. Note the **Phone Number ID** (WhatsApp → API Setup) and put it in `WHATSAPP_PHONE_NUMBER_ID`.
4. Pick any string for `WHATSAPP_VERIFY_TOKEN` — Meta will echo it back during the verification handshake.
5. Copy your **App Secret** (Settings → Basic → App Secret) into `WHATSAPP_WEBHOOK_SECRET`. The webhook controller HMAC-verifies every inbound POST against this. Leave empty only in local dev; production must set it.
6. In WhatsApp → Configuration → Webhook:
   - **Callback URL**: `https://<your-host>/api/webhook/whatsapp`
   - **Verify Token**: same string as `WHATSAPP_VERIFY_TOKEN`
   - Subscribe to the `messages` field.
7. For trial-tier accounts, add tester phone numbers under WhatsApp → API Setup → Recipients.

> The webhook controller reads the body once as a raw `String`, HMAC-SHA256s it against `WHATSAPP_WEBHOOK_SECRET`, and constant-time-compares to the `X-Hub-Signature-256` header. Counts of received / signature-failed / parse-failed / processing-failed events are exposed at `/api/actuator/metrics/webhook.received` (and similar names).

---

## HTTP endpoints

### WhatsApp webhook
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/webhook/whatsapp` | Meta verification handshake (`hub.verify_token`) |
| `POST` | `/api/webhook/whatsapp` | Inbound messages and status callbacks |

### Dashboard (Thymeleaf)
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/dashboard/login` | Login form |
| `POST` | `/api/dashboard/login` | Submit credentials |
| `GET` | `/api/dashboard` | Home (queues, recent intakes) |
| `GET` | `/api/dashboard/patients` | Patient list |
| `GET` | `/api/dashboard/patients/{id}` | Patient detail / review |
| `GET` | `/api/dashboard/patients/add` | Referring-doctor add patient form |
| `POST` | `/api/dashboard/patients/add` | Create patient on behalf of a referring doctor |
| `GET` | `/api/dashboard/protocol` | Protocol viewer |
| `GET` | `/api/dashboard/doctors` | Doctor management (admin) |

### Health
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/actuator/health` | Liveness / readiness |
| `GET` | `/api/actuator/info` | Build info |

---

## Conversation state machine

```
INITIAL
  ↓
AWAITING_CONSENT
  ↓
ASK_REFERRAL_CODE → ASK_CANCER_TYPE → ASK_AGE → ASK_WEIGHT
  → ASK_PAIN_SCALE → ASK_DIAGNOSIS_DATE → ASK_PET_SCAN → ASK_BLOOD_REPORT
  → PROCESSING → RESULT_SENT → COMPLETED
                    │
                    └─► EXPIRED  (after timeout)
```

`START` / `RESTART` from the user resets the conversation. Validation rules: age 0–120, weight 1–300 kg, pain 0–10, date format `YYYY-MM-DD`. All prompt strings are constants at the top of `ConversationService.java`.

---

## Data model

| Entity | Notes |
|---|---|
| `Patient` | WhatsApp number is the unique business key. Tracks demographics, intake flags, `ConversationState`, consent, and `referringDoctor`. |
| `Doctor` | Referring physicians and tumor-board members. |
| `Report` | Uploaded medical document metadata + storage URI. |
| `Analysis` | JSON-column suggestions from the formula engine, with formula version stamped for audit. |
| `TumorBoardReview` | Per-patient case with eight per-domain sign-off slots. |
| `FinalProtocol` | Consensus protocol produced after all domains approve. |
| `AuditLog` | PHI-safe trail (IDs only, 28 action types). |

---

## Testing

```bash
./mvnw test                                        # all tests
./mvnw test -Dtest=FormulaEngineTest               # one class
./mvnw test -Dtest=FormulaEngineTest#testMildPainPatientAnalysis
```

JUnit 5, Mockito. Existing suites:
- `FormulaEngineTest` — derived metrics, alerts, supportive care, disclaimers (nested test classes).
- `ConversationServiceTest` — state-machine transitions and input validation.
- `ReportDataExtractionServiceTest` — report parsing.

---

## Production deployment notes

The reference deployment runs on AWS:
- **EC2** — host running the Docker container, fronted by **nginx** terminating TLS.
- **RDS PostgreSQL** — managed database (set `DB_HOST` to the RDS endpoint). Enable encryption-at-rest at instance creation time.
- **S3** — bucket for uploaded reports. The app calls `PutObject` with `serverSideEncryption=AES256`; bucket policy should additionally deny `aws:SecureTransport=false` to require TLS in transit.
- **Elastic IP** — fixed IP for the EC2 instance, mapped to a DNS A record.

**Credentials**: attach an IAM instance role to the EC2 host with `s3:GetObject / PutObject / DeleteObject / HeadObject` on the bucket. Leave `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` blank — the AWS SDK falls back to the EC2 metadata service automatically. Static keys leak through `docker inspect`, `ps`, and `.env` files on disk.

`deploy/setup-ec2.sh` installs Docker + Compose on Amazon Linux 2023 / Ubuntu 22.04 and prints the next steps.

Bring-up sketch:
1. Create RDS Postgres + S3 bucket; allow the EC2 security group access to both.
2. Allocate an Elastic IP and associate it with the EC2 instance.
3. Run `deploy/setup-ec2.sh` on the instance.
4. `git clone` this repo to `/opt/cancerqr`, fill `.env`, `docker compose up -d`.
5. Front it with nginx + Let's Encrypt cert at your domain.
6. Point Meta's webhook at `https://<your-domain>/api/webhook/whatsapp`.

---

## Project layout

```
src/main/java/com/oncology/intake/
  config/      Spring configuration, WhatsApp / Storage / Security beans
  controller/  WhatsApp webhook + Thymeleaf dashboard
  dto/         WhatsApp + analysis DTOs
  engine/      FormulaEngine
  entity/      JPA entities (Patient, Doctor, Report, Analysis, ...)
  exception/   Global exception handler
  repository/  Spring Data JPA repositories
  service/     ConversationService, PatientIntakeService, AnalysisService, ...

src/main/resources/
  application.yml                Profiles + config
  formula-rules.yml              Dosing tables / herbs / alerts
  cancerqr-protocols.yml         Cancer × domain protocol matrix
  db/migration/                  Flyway migrations
  templates/dashboard/           Thymeleaf views

deploy/
  setup-ec2.sh                   EC2 bootstrap
  env.example                    Env var template

Dockerfile / docker-compose.yml  Container build + bundled Postgres
```

---

## License

Internal project — license to be added.
