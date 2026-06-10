# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# CURRENT STATE — READ FIRST ON RESUME (updated 2026-05-20)

## Where we are
PRs 9–17 (security/efficiency hardening) are **committed and pushed** to `parrrthiv/cancerqr-patient-intake` (`main`) but **NOT yet deployed** to EC2. The EC2 box is currently running the **OLD pre-PR-9 image** (`cancerqr-app:latest` = image id `deae87f40697`). The deploy/swap is mid-flight.

## Immediate next task: deploy PRs 9–17 to the test EC2
Testing happens ON EC2 — there is no local test environment. Resume sequence:

1. RDS snapshot already taken: `pre-pr17-20260519` (verify it shows `available`).
2. SSH in: `ssh -i cancerqr-tuf.pem ubuntu@<CURRENT-public-ip>` (IP changes on stop/start — re-check it). With the OLD `cancerqr` container still running:
   - Backup env vars: `docker inspect cancerqr --format '{{json .Config.Env}}' > ~/env-backup-$(date +%Y%m%d).json` — confirm non-empty (this failed once already because the container had been removed first).
   - Tag rollback target: `docker tag cancerqr-app:latest cancerqr-app:pre-pr17`
   - Generate and SAVE a new `PHI_HMAC_KEY`: `openssl rand -base64 32`
   - `cd ~/cancerqr-patient-intake && git pull origin main && docker build -t cancerqr-app:latest .`
   - Swap: `docker stop cancerqr && docker rm cancerqr`, then `docker run -d --name cancerqr --restart unless-stopped -p 8080:8080 ...` with ALL env vars taken from the backup PLUS the new `-e PHI_HMAC_KEY=...`. The only new flag vs. the previous run is `PHI_HMAC_KEY`. (Reuse the exact `SPRING_PROFILES_ACTIVE` value from the backup — do not guess `prod` vs `production`.)
3. Watch `docker logs -f cancerqr` for the 7 verification lines (below).
4. End-to-end test: WhatsApp intake → upload a report → `/api/dashboard/reports/phi-review` shows it PENDING → Approve → confirm report becomes visible to tumor board; flag-for-redaction keeps it out.

## CRITICAL WARNINGS
- **V7 migration WIPES all patient data** (`final_protocols`, `tumor_board_reviews`, `analyses`, `reports`, `patients`, in that order). This was the user's explicit decision — no backfill. Test RDS only.
- **`PHI_HMAC_KEY` is a NEW required production env var** (PR 10). App fails fast on startup without it. Store it alongside `PHI_ENCRYPTION_KEY`.
- **Losing `PHI_ENCRYPTION_KEY` = permanent loss of all encrypted PHI.** Recover from the password manager before any teardown.
- **Secrets pasted in an earlier chat are COMPROMISED and must be rotated**: `WHATSAPP_ACCESS_TOKEN`, `DB_PASSWORD`, and the previously-used `PHI_ENCRYPTION_KEY` value. Do not reuse long-term.
- **On EC2 the container is named `cancerqr`** (not `cancerqr-app`); the image is `cancerqr-app:latest`.
- **EC2 public IP changes on every stop/start.** Re-check via console/CloudShell before SSH. Last seen: `13.235.181.126`.
- **App context path is `/api`** — health is at `/api/actuator/health`, login at `/api/dashboard/login`. Earlier 404s came from omitting the `/api` prefix. Use `docker ps` + `GET /api/dashboard/login` to confirm liveness.

## Verification — 7 startup log lines that confirm PRs 9–17 are healthy
```
Migrating schema "public" to version "7"
Migrating schema "public" to version "8"
Migrating schema "public" to version "9"
Successfully validated 9 migrations
PHI encryptor initialised (AES-256-GCM, key id=<8 hex>)
WhatsApp number hasher initialised (HMAC-SHA256, key id=<8 hex>)
Started IntakeApplication in N.NN seconds
```

## Rollback
App-only: re-run the old env vars (without `PHI_HMAC_KEY`) on image `cancerqr-app:pre-pr17`. BUT once V7–V9 have run, the old image's Flyway refuses to start (version/checksum mismatch) — so a true rollback also requires restoring RDS from snapshot `pre-pr17-20260519`.

## AWS resources (region ap-south-1)
NOTE: migrated to a NEW AWS account (free credits) ~2026-06; OLD account DECOMMISSIONED
2026-06 (all resources deleted). Non-secret values below; secrets live in
`cancerqr-new-account-values.md` (local, not committed). DB name/user changed to
`cancerqr` / `cancerqr_db`.
| Resource | ID / name |
|---|---|
| AWS account | `822725963424` |
| EC2 | `i-0356247845a6873b0` ("cancerqr-webserver") |
| Elastic IP | `3.7.180.174` |
| RDS | `cancerqr-db.ch62wekoa00z.ap-south-1.rds.amazonaws.com` (db `cancerqr`, user `cancerqr_db`) |
| S3 | `cancerqr-files` |
| Public URL | `https://testapi.cancerqr.com` |
| Working repo (fork) | `parrrthiv/cancerqr-patient-intake` |
| Upstream repo | `vineetrk/cancerqr-intake-system` |
| Cost mgmt | CloudShell `~/start-cancerqr.sh` / `~/stop-cancerqr.sh` |

---

# ROLE

You are acting as:

- Senior Java/Spring Boot Architect
- Staff Security Engineer
- DevSecOps Engineer
- Healthcare Compliance Reviewer
- Backend Performance Engineer
- AWS Infrastructure Reviewer
- API Security Auditor
- Database Reliability Engineer

You must think like both:
- a senior production engineer
- a malicious attacker

This project handles sensitive healthcare-related patient data.
Treat all code as production-critical and PHI-sensitive.

---

# PRIMARY OBJECTIVES

When modifying or reviewing code:

1. Prevent security vulnerabilities
2. Prevent PHI/data leakage
3. Preserve data integrity
4. Maintain production reliability
5. Maintain auditability
6. Prevent unsafe deployments
7. Prevent privilege escalation
8. Ensure scalability
9. Ensure thread safety
10. Ensure secure API behavior

Always prioritize:
- security
- reliability
- correctness
- maintainability
over speed of implementation.

---

# MANDATORY SECURITY REVIEW

For EVERY meaningful code change, ALWAYS review for:

## OWASP Risks

- SQL Injection
- XSS
- CSRF
- SSRF
- RCE
- Path Traversal
- Insecure Deserialization
- Broken Authentication
- Broken Access Control
- Sensitive Data Exposure
- Security Misconfiguration
- Dependency Vulnerabilities

## Spring Boot Risks

- Unsafe `@CrossOrigin`
- Missing authorization checks
- Unsafe actuator exposure
- Unvalidated request bodies
- Missing DTO validation
- Entity exposure directly to APIs
- Mass assignment vulnerabilities
- Insecure file upload handling
- Dangerous Jackson polymorphic deserialization
- Weak JWT handling
- Hardcoded secrets
- Logging PHI or secrets
- Missing rate limiting
- Unsafe async execution
- Thread pool exhaustion
- Transaction boundary issues

## Infrastructure Risks

- Exposed AWS credentials
- Public S3 buckets
- Weak nginx configs
- Docker container privilege escalation
- Secrets in images
- Insecure environment variable handling
- Open database exposure
- Weak TLS assumptions
- Missing backup/recovery concerns

---

# SECURITY REQUIREMENTS

## NEVER

- Never log PHI
- Never log tokens/secrets
- Never hardcode credentials
- Never expose stack traces to users
- Never trust client input
- Never bypass validation
- Never disable security checks silently
- Never expose internal IDs unnecessarily
- Never use plaintext passwords
- Never use insecure random generators
- Never deserialize untrusted objects blindly

## ALWAYS

- Validate all user input
- Use DTO validation annotations
- Sanitize uploaded filenames
- Verify MIME types
- Use parameterized queries
- Use BCrypt/Argon2 for passwords
- Use constant-time comparisons for secrets
- Handle failures safely
- Fail securely by default
- Use least-privilege principles
- Review async/concurrency safety
- Consider rate limiting
- Consider replay attacks
- Consider denial-of-service risks

---

# HEALTHCARE / PHI RULES

This application handles healthcare-related data.

Always review for:
- PHI exposure
- accidental logging of patient data
- insecure report/document access
- insecure file storage
- insecure S3 permissions
- audit trail gaps
- improper authorization checks
- data retention risks
- insecure report download endpoints

Never expose:
- patient reports
- medical analyses
- phone numbers
- physician notes
- internal IDs
without explicit authorization checks.

---

# PROJECT SUMMARY

Spring Boot 3.2.1 / Java 17 application providing a WhatsApp-based cancer patient intake system plus a physician-facing dashboard and tumor board review workflow.

Patients converse via WhatsApp, the system collects demographics + medical reports, runs a rule-based protocol engine, and surfaces cases to physicians for review and final protocol generation.

Production deployment:
AWS EC2 (Docker) + RDS PostgreSQL + S3 + nginx reverse proxy with HTTPS.

Production URL:
https://testapi.cancerqr.com/api/...

---

# BUILD & RUN COMMANDS

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean package

# Run application (default profile = H2 in-memory, dev only)
./mvnw spring-boot:run

# Run with local PostgreSQL
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-pg

# Run with production profile (PostgreSQL + S3, all required env vars must be set)
./mvnw spring-boot:run -Dspring-boot.run.profiles=production

# Run tests
./mvnw test
```

The `default` profile is dev-only (H2, demo seed via `dev` profile only, `H2_CONSOLE_ENABLED=false` baseline). NEVER deploy without `SPRING_PROFILES_ACTIVE=production`.

---

# ARCHITECTURE

## Request flow — patient intake (WhatsApp)

```
WhatsApp Cloud API
    │  POST /api/webhook/whatsapp  (HMAC-SHA256 verified against WHATSAPP_WEBHOOK_SECRET)
    ▼
WhatsAppWebhookController            ←── Micrometer counters: webhook.received,
    │                                     webhook.signature_failed, webhook.parse_failed,
    │                                     webhook.processing_failed
    ▼
ConversationService  (14-state machine, @Async dispatch)
    │
    ├─► PatientIntakeService     ──►  MediaValidator (MIME + magic bytes + size)
    │                                  StorageService (S3 PutObject with AES256 SSE)
    │                                  ReportDataExtractionService (PDFBox)
    │
    ├─► AnalysisService  ──►  FormulaEngine (formula-rules.yml)
    │                          └─►  AIVerificationService (optional Claude review)
    │
    └─► WhatsAppClientService   (bounded ConnectionProvider, 10s response timeout)
```

## Request flow — physician dashboard

```
Browser (Thymeleaf, CSRF-enabled forms)
    │  HTTPS via nginx → Spring Security form login
    ▼
DashboardController  (/api/dashboard/*)
    │  All routes default-deny: .anyRequest().authenticated()
    │  Per-route role checks: ROLE_ADMIN for /doctors/**, /protocol/*/approve;
    │                          ROLE_REFERRING_DOCTOR for /patients/add
    │
    ├─► PatientAccessService   (canViewPatient / canViewReport — IDOR enforcement)
    ├─► PatientRepository, DoctorRepository, TumorBoardReviewRepository
    ├─► TumorBoardService       (8-domain physician review workflow)
    └─► CancerQRProtocolConfig  (cancerqr-protocols.yml)
```

## Request flow — patient portal (web, no WhatsApp required)

```
Browser (mobile-first Thymeleaf, CSRF-enabled forms)
    │  HTTPS via nginx → DEDICATED Spring Security chain for /portal/** (@Order(1))
    ▼
PatientPortalController  (/api/portal/*)
    │  Login: phone + password (PatientAccountDetailsService → PatientPortalPrincipal,
    │         single role ROLE_PATIENT). Public: /portal/login, /portal/register, /portal/verify.
    │  IDOR-proof by construction: every handler resolves the patient ONLY from the
    │  session principal — no patient id is ever accepted from the request.
    │
    ├─► PatientPortalService
    │     ├─ Registration. Account-takeover guard: a number that already has a
    │     │  patient record (WhatsApp-created, may hold PHI) gets a DISABLED account
    │     │  until a 6-digit code sent to that number ON WHATSAPP is confirmed
    │     │  (SHA-256-hashed OTP, 10 min expiry, 5 attempts). Brand-new numbers
    │     │  register directly (empty record — nothing to take over).
    │     ├─ Web intake wizard driving the SAME ConversationState machine as the
    │     │  bot (consent → details(+referral code) → PET upload → blood upload),
    │     │  reusing PatientIntakeService, MediaValidator, storeReport(Path) and
    │     │  the atomic advanceStateIfCurrent step-claim. Patients can switch
    │     │  channels (WhatsApp ↔ web) mid-intake.
    │     └─ Completion = same sequence as the WhatsApp flow (analysis → tumor
    │        board tasks → RESULT_SENT), minus the WhatsApp sends.
    └─► PatientMessageService  (doctor → patient messages; portal inbox is the
          source of truth, mirrored to WhatsApp best-effort; doctors send from
          the patient-review page, gated by PatientAccessService)

Patients see ONLY: their own status/progress, diet+lifestyle guidance
(AnalysisService.PatientDietGuidance — the single source of truth for
patient-safe content; NO medicines pre-review), and care-team messages.
```

App context path is `/api`. All routes below relative to `/api`.

## Key components

| Component | Responsibility |
|---|---|
| `WhatsAppWebhookController` | Webhook auth (HMAC), parse, dispatch to ConversationService. Returns 200 even on processing errors so Meta doesn't retry-storm. |
| `ConversationService` | 14-state machine: INITIAL → AWAITING_CONSENT → ASK_REFERRAL_CODE → ASK_CANCER_TYPE → ASK_AGE → ASK_WEIGHT → ASK_PAIN_SCALE → ASK_DIAGNOSIS_DATE → ASK_PET_SCAN → ASK_BLOOD_REPORT → PROCESSING → RESULT_SENT → COMPLETED → EXPIRED. Prompts are constants at the top of the file. |
| `PatientIntakeService` | Persists Patient + Report rows. Calls `MediaValidator.validate(...)` BEFORE storing. Cap from `app.max-upload-size-mb`. |
| `MediaValidator` | Whitelist (image/jpeg, image/png, image/webp, application/pdf) + magic-byte verification + size cap. WhatsApp-supplied content type is never trusted alone. |
| `StorageService` | S3 / local / MinIO strategy. S3 PutObject sets `ServerSideEncryption.AES256`. AWS SDK uses default credentials chain → EC2 IAM role when no static keys are configured. |
| `FormulaEngine` | Loads `formula-rules.yml` once at `@PostConstruct`. Computes derived metrics + 5 categories of recommendations + alerts. |
| `TumorBoardService` | Per-domain review queue. `getUnassignedReviewsForDomain` uses indexed SQL (was `findAll().stream().filter(...)`). Static helper `buildReviewStatus(reviews)` lets callers compute status without re-querying. |
| `PatientAccessService` | Single source of truth for "can doctor X see patient Y / report Z." ADMIN sees all; REFERRING_DOCTOR sees only their own; 8 tumor-board domains see patients with at least one review on file. |
| `PhiEncryptor` + `EncryptedStringConverter` | AES-256-GCM column encryption. Multi-key: reads `PHI_ENCRYPTION_KEY` (back-compat v1) and `PHI_ENCRYPTION_KEY_V1..V20`; writes under the highest-numbered key (`{enc:v<N>}`), decrypts any version; absence-of-prefix = legacy plaintext (graceful migration). Applied to `Patient.name/whatsappNumber`, numeric PHI (`weightKg`, `painScale`, `effectivePainScale`, `esrValue`, `crpValue`, `cancerStage` via `EncryptedBigDecimalConverter`/`EncryptedIntegerConverter`), and `Doctor.fullName/email/phone`. Rotation driven by `PhiRotationController` (`/admin/phi/state`, `/admin/phi/rotate`). |
| `WhatsAppNumberHasher` + `PatientHashListener` | HMAC-SHA256 of the normalised WhatsApp number → `patients.whatsapp_number_hash` (unique). Lets `findByWhatsappNumberHash` do equality lookups against the encrypted column. JPA `@PrePersist/@PreUpdate` listener computes the hash. Fails fast in prod if `PHI_HMAC_KEY` unset. |
| `DoctorPrincipal` + `DoctorUserDetailsService` | Spring Security UserDetails wrapping Doctor. `DelegatingPasswordEncoder` (bcrypt + noop legacy) auto-upgrades on login. |
| `AsyncConfig` | `@EnableAsync` lives here (not on main app class) so `AsyncUncaughtExceptionHandler` can wire in. Increments `async.errors{method=...}` Micrometer counter on every uncaught async exception. |

---

# ENVIRONMENT VARIABLES

## Required in production (app fails to start without them)

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection (RDS) |
| `STORAGE_TYPE=s3`, `S3_BUCKET_NAME`, `AWS_REGION` | S3 storage |
| `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN` | Meta WhatsApp Business Cloud API |
| `WHATSAPP_WEBHOOK_SECRET` | Meta App Secret. HMAC-verifies inbound webhooks. Empty = verification skipped (dev only). |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed origins, e.g. `https://testapi.cancerqr.com`. Never `*` with credentials. |
| `PHI_ENCRYPTION_KEY` | Base64 32-byte AES-256 key (treated as v1). Generate with `openssl rand -base64 32`. **Loss = permanent loss of every encrypted PHI value.** |
| `PHI_HMAC_KEY` | Base64 32-byte HMAC-SHA256 key for the WhatsApp-number lookup hash (PR 10). Generate with `openssl rand -base64 32`. App fails to start in production without it. **Loss = cannot look up patients by WhatsApp number.** |

## Optional / not used in production

| Variable | Purpose |
|---|---|
| `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | Static S3 credentials. Leave BLANK in production — use the EC2 instance role instead. SDK auto-falls back to IMDS when these are empty. |
| `S3_ENDPOINT` | Only for MinIO; leave unset for AWS S3. |
| `ANTHROPIC_API_KEY`, `AI_VERIFICATION_ENABLED` | Optional Claude review of generated analyses. |
| `H2_CONSOLE_ENABLED` | Defaults to `false`. Never enable in production. |
| `SESSION_COOKIE_SECURE` | Forced to `true` by the production profile. Override only when debugging over plain HTTP locally. |
| `PHI_ENCRYPTION_KEY_V1` … `_V20` | Additional AES-256 keys for rotation (PR 12). Highest-numbered configured key becomes the write key; all configured keys stay available for decrypt. Leave unset until rotating. |

## Removed / dead env vars (do NOT set)

`ENCRYPTION_KEY`, `JWT_SECRET` — were committed defaults that no class read. Removed in PR 4. Replaced (functionally) by `PHI_ENCRYPTION_KEY` for at-rest column encryption.

---

# SECURITY POSTURE (post PR 1–17)

What's wired and how to keep it that way:

- **Authentication**: Spring Security form login via `DoctorUserDetailsService`. BCrypt via `DelegatingPasswordEncoder`. Legacy `{noop}` rows auto-upgrade to `{bcrypt}` on next successful login.
- **Authorization (read)**: Default-deny on `/dashboard/**`. Per-route role checks for ADMIN and REFERRING_DOCTOR. `PatientAccessService` is the single source of truth for per-patient/per-report access — call it from every new read endpoint.
- **Authorization (write)**: `submitReview` clean-redirects on wrong domain (was: 500 stack trace). `approveProtocol` is admin-only at both filter and controller level.
- **CSRF**: Enabled with plain `CsrfTokenRequestAttributeHandler`. Excluded only for `/webhook/whatsapp/**` (Meta can't carry our token; HMAC is the auth) and `/admin/test/**` (dev-only). All Thymeleaf forms use `th:action`, which auto-injects the token.
- **Webhook signature**: HMAC-SHA256 over the raw body, constant-time compared to `X-Hub-Signature-256`. Never declare two `@RequestBody` parameters on the same handler — body is non-rewindable.
- **Cookies**: `Secure` (production), `HttpOnly`, `SameSite=Lax`. `JSESSIONID` deleted on logout.
- **Headers**: HSTS (1 year, includeSubDomains), CSP (`default-src 'self'`, inline scripts/styles allowed for current Tailwind CDN templates).
- **CORS**: Pinned via `CORS_ALLOWED_ORIGINS`. Never `*`.
- **Upload validation**: `MediaValidator` enforces MIME whitelist + magic bytes + size cap before storage. Download endpoint forces `Content-Disposition: attachment` + `nosniff` + `application/octet-stream`.
- **At rest**: S3 PutObject with `AES256` SSE. RDS encryption-at-rest enabled at instance level. AES-256-GCM column encryption (multi-key, rotatable) on PHI columns: `Patient.name/whatsappNumber`, numeric PHI (`weightKg`, `painScale`, `effectivePainScale`, `esrValue`, `crpValue`, `cancerStage`), and `Doctor.fullName/email/phone`. WhatsApp number additionally HMAC-SHA256 hashed for equality lookups.
- **Logging**: PHI-safe at INFO. Cancer stage, ESR, CRP, pain values, names, full phone numbers all moved to TRACE or removed. Phone numbers in INFO logs are masked (`first4****last4`).
- **Observability**: Micrometer counters on the webhook flow + async errors + rate-limit rejections. Available at `/api/actuator/metrics/<name>`.
- **Rate limiting** (PR 16): `RateLimitFilter` (Bucket4j, highest filter precedence) — 60 req/min per IP on the webhook, 5 login attempts / 5 min per username; returns 429 + `Retry-After`. Parses `X-Forwarded-For`.
- **PHI redaction review** (PR 13): every uploaded report starts `PENDING` and is hidden from tumor-board reviewers until an ADMIN marks it `APPROVED` (or `REDACTION_NEEDED`) via `/dashboard/reports/phi-review`.
- **Async safety** (PR 17): PDF extraction runs fire-and-forget via `ReportDataExtractionAsyncRunner` (a separate bean so the `@Async` proxy applies) — no PDF parsing on the request thread.
- **Patient portal isolation** (patient-portal PR): TWO security filter chains. `/portal/**` (@Order(1)) authenticates patients (ROLE_PATIENT via `PatientAccountDetailsService`); the staff chain now requires `hasAnyRole(<PhysicianDomain names>)` instead of bare `authenticated()` — otherwise a patient session would satisfy `/dashboard/**`. NEVER revert the staff chain to `.anyRequest().authenticated()`; `PortalSecurityIntegrationTest` locks this in. Portal rate limits in `RateLimitFilter`: login per-phone 5/5min, register per-IP 10/h, verify per-IP 15/5min. Portal accounts: bcrypt passwords, encrypted phone + HMAC hash lookup (same pattern as `Patient.whatsappNumber`).

---

# COMMON CHANGE LOCATIONS

| Want to change... | Edit... |
|---|---|
| WhatsApp prompt wording | Constants at the top of `ConversationService.java` |
| Add a conversation state | `Patient.ConversationState` enum + transitions in `ConversationService` + (probably) a Flyway migration |
| Dosing rules / herbs / mushrooms | `formula-rules.yml` (no Java change needed unless YAML schema changes) |
| Cancer types / domain protocols | `cancerqr-protocols.yml` (`CancerQRProtocolConfig`/`TumorBoardService` if new domain semantics) |
| Add a new dashboard route | `DashboardController` — it inherits `.authenticated()` automatically. If it reads a Patient/Report by id, **MUST** call `PatientAccessService` first. |
| Add a new POST endpoint | Inherits CSRF protection. If called by Meta or a non-browser client, add to the CSRF `ignoringRequestMatchers(...)` list (carefully). |
| Change login behaviour | Spring Security form login config in `SecurityConfig`. Don't add a `@PostMapping("/login")` to `DashboardController` — Spring's filter owns POST `/dashboard/login`. |
| Add a column with PHI | Bump column `length` to ≥500, add `@Convert(converter = EncryptedStringConverter.class)`, write a Flyway migration to widen the existing column. For NUMERIC fields, write a new converter (not yet provided). |
| Schema change | Flyway migration `V<N>__<desc>.sql`. Production runs `ddl-auto=validate`, so the entity must match exactly. |
| Dashboard UI | Thymeleaf templates in `src/main/resources/templates/dashboard/`. Forms must use `th:action` (auto-injects CSRF token). |
| Outbound WhatsApp call resilience | `WebClientConfig` — bounded pool + timeouts already configured. NO retry on POST `/messages` (not idempotent). Add `Retry.backoff(...)` only at call sites for idempotent reads. |

---

# DATABASE / FLYWAY

| Profile | Schema management | DB |
|---|---|---|
| default (dev) | `ddl-auto: update`, Flyway off | H2 in-memory |
| `local-pg` | `ddl-auto: validate`, Flyway on | localhost:5432/oncologydb |
| `production` | `ddl-auto: validate`, Flyway on | RDS PostgreSQL |

Migration history (current):
- `V1__initial_schema.sql` — base tables
- `V2__add_report_extraction_columns.sql` — cancer_stage, esr_value, crp_value, effective_pain_scale
- `V3__add_referring_doctor.sql` — referring_doctor_id, referral_code on doctors
- `V4__prefix_legacy_passwords.sql` — `{noop}` prefix for backward-compat with BCrypt
- `V5__add_referring_doctor_index.sql` — index on `patients.referring_doctor_id`
- `V6__widen_phi_columns_for_encryption.sql` — `name`, `full_name`, `email`, `phone` → `VARCHAR(500)`
- `V7__encrypt_whatsapp_number.sql` (PR 10) — **DESTRUCTIVE**: deletes all patient-related rows, drops FK/unique constraints, widens `whatsapp_number` to `VARCHAR(500)`, adds `whatsapp_number_hash VARCHAR(64) NOT NULL` + unique index
- `V8__encrypt_numeric_phi.sql` (PR 11) — alters `weight_kg`, `pain_scale`, `effective_pain_scale`, `esr_value`, `crp_value`, `cancer_stage` to `VARCHAR(500)` via `USING ::TEXT` (now hold ciphertext)
- `V9__add_phi_review_to_reports.sql` (PR 13) — adds `phi_review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'`, `phi_reviewed_by_doctor_id UUID`, `phi_reviewed_at TIMESTAMP`, index on `phi_review_status`
- `V10__patient_portal.sql` (patient portal) — adds `patient_accounts` (portal login: encrypted phone + HMAC `phone_hash`, bcrypt password, WhatsApp-OTP columns) and `patient_messages` (doctor → patient, encrypted body)

When adding a migration: increment the V number. NEVER edit a migration that's already been applied to production. NOTE: V7–V9 have NOT yet been applied to the test RDS — they run on the next deploy.

---

# DEFERRED WORK (call out explicitly when this surface comes up)

Done since the original list (do NOT re-do):
- Encrypt `Patient.whatsappNumber` + HMAC hash lookup — done in PR 10
- Encrypt numeric PHI — done in PR 11
- PDFBox extraction off the request thread — done in PR 17 (`ReportDataExtractionAsyncRunner`)
- Hibernate batch fetch — done in PR 9
- PHI key rotation (multi-key) — done in PR 12
- Streaming uploads (C7) — done (temp-file Path end-to-end: `DataBufferUtils.write` download, `MediaValidator.validate(Path)`, `RequestBody.fromFile` to S3, streamed checksum)
- Patient portal — done (web intake + status + doctor→patient messages; see "Request flow — patient portal")

Still open (real projects, not one-line fixes):

0. **Portal go-live hardening** —
   (a) SMS OTP for NEW-number portal registrations: today a brand-new number registers
   without ownership proof (nothing to take over yet), but if that person later uses the
   WhatsApp bot, the portal registrant could read the WhatsApp-collected data. Existing
   records are already protected by the WhatsApp-OTP link flow.
   (b) nginx: `client_max_body_size 30m;` for `/api/portal/` — the default 1 MB cap will
   413 portal report uploads at the proxy (WhatsApp uploads never hit nginx, so this
   never surfaced before).
2. **Resilience4j circuit breaker** (C11) — outbound WhatsApp/Claude calls have timeouts but no breaker; a sustained Meta outage ties up threads. Add a breaker around `WhatsAppClientService`.
3. **LICENSE file** (C19) — repo has none; needs a license decision from the user (proprietary vs OSS) before adding.
4. **More tests / e2e** (C22) — PR 14 added unit tests for the security layer; still missing controller-level and end-to-end coverage.
5. **PHI redaction stage 2** — PR 13 shipped the human-review queue (`PENDING/APPROVED/REDACTION_NEEDED/REDACTED`). Automated detection (Textract + Comprehend Medical) and actual pixel-level redaction are future work.

AWS-side (cannot be done from code — user action in console/CloudShell):
- Secrets Manager for env vars (currently passed via `docker run -e`), S3 versioning, RDS restore-test drill, off-box backup of `PHI_ENCRYPTION_KEY`/`PHI_HMAC_KEY`, log shipping (CloudWatch), metric shipping, CI/CD pipeline.

Legal/compliance (tracked separately from coding — Indian regulatory):
- NMC registration verification for reviewing physicians, parental consent flow for minors, published privacy policy, designated DPO, breach-notification procedure, SaMD classification with CDSCO. (DPDPA, Telemedicine Practice Guidelines 2020, IT Rules 2011.)

When working on adjacent code, prefer creating a focused PR for one of the open items over bundling.

---

# WHEN MODIFYING THIS PROJECT

## Always

- Run `./mvnw clean package` (with tests) locally before pushing — production runs `ddl-auto=validate` so an entity-vs-schema mismatch will only fail at production startup.
- For new entity fields containing PHI: encrypt by default (the `Patient.name` and `Doctor.email` patterns are the model).
- For new dashboard routes that fetch a Patient/Report by id: call `PatientAccessService` BEFORE returning anything, even just status.
- Log IDs, never PHI values. If you genuinely need a value for debugging, log at TRACE behind a feature flag.

## Never

- Don't add `@PostMapping("/login")` or `@GetMapping("/logout")` to `DashboardController` — Spring Security owns those URLs.
- Don't declare two `@RequestBody` parameters on the same handler — request body is a non-rewindable stream.
- Don't pass static AWS keys via env vars in production — use the EC2 instance role.
- Don't combine `@Transactional` with a reactive return type (`Mono`/`Flux`) on the same method — the transaction commits before the chain runs.
- Don't `findAll().stream().filter(...)` over an entity table with growth potential — write a repository method instead.
- Don't log a Doctor or Patient instance directly — use IDs. `Doctor.toString` excludes the password but Patient has lazy collections that LazyInit on log render.