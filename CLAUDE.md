# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
| `PhiEncryptor` + `EncryptedStringConverter` | AES-256-GCM column encryption. Versioned `{enc:v1}` prefix supports rotation; absence-of-prefix = legacy plaintext (graceful migration). Currently applied to `Patient.name`, `Doctor.fullName/email/phone`. |
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
| `PHI_ENCRYPTION_KEY` | Base64 32-byte AES-256 key. Generate with `openssl rand -base64 32`. **Loss = permanent loss of every encrypted PHI value.** |

## Optional / not used in production

| Variable | Purpose |
|---|---|
| `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | Static S3 credentials. Leave BLANK in production — use the EC2 instance role instead. SDK auto-falls back to IMDS when these are empty. |
| `S3_ENDPOINT` | Only for MinIO; leave unset for AWS S3. |
| `ANTHROPIC_API_KEY`, `AI_VERIFICATION_ENABLED` | Optional Claude review of generated analyses. |
| `H2_CONSOLE_ENABLED` | Defaults to `false`. Never enable in production. |
| `SESSION_COOKIE_SECURE` | Forced to `true` by the production profile. Override only when debugging over plain HTTP locally. |

## Removed / dead env vars (do NOT set)

`ENCRYPTION_KEY`, `JWT_SECRET` — were committed defaults that no class read. Removed in PR 4. Replaced (functionally) by `PHI_ENCRYPTION_KEY` for at-rest column encryption.

---

# SECURITY POSTURE (post PR 1–8)

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
- **At rest**: S3 PutObject with `AES256` SSE. RDS encryption-at-rest enabled at instance level. AES-256-GCM column encryption on the four PHI String fields listed above.
- **Logging**: PHI-safe at INFO. Cancer stage, ESR, CRP, pain values, names, full phone numbers all moved to TRACE or removed. Phone numbers in INFO logs are masked (`first4****last4`).
- **Observability**: Micrometer counters on the webhook flow + async errors. Available at `/api/actuator/metrics/<name>`.

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

When adding a migration: increment the V number. NEVER edit a migration that's already been applied to production.

---

# DEFERRED WORK (call out explicitly when this surface comes up)

These are real projects, not one-line fixes:

1. **Encrypt `Patient.whatsappNumber`** — needs companion `whatsapp_number_hash` (HMAC-SHA256) column for `findByWhatsappNumber` lookups + data backfill migration. Hot path on every inbound webhook.
2. **Encrypt numeric PHI** (`weightKg`, `esrValue`, `crpValue`, `effectivePainScale`, `painScale`, `cancerStage`) — needs `BigDecimal`/`Integer` JPA converters and an audit of every place these flow into formula calculations and templates.
3. **Streaming uploads** — `StorageService.storeFile` reads whole files into heap before sending to S3. With 25 MB cap × concurrent uploads × multiple buffers, t3.small can OOM. Refactor to `RequestBody.fromInputStream(...)`.
4. **PDFBox sync extraction inside the request thread** — `ReportDataExtractionService` runs PDF parsing inside `/dashboard/patient/{id}` render. Move to `@Async` post-upload, persist results.
5. **Hibernate batch fetch** — every dashboard render still re-hits DB for `reports`/`analyses` collections. Set `spring.jpa.properties.hibernate.default_batch_fetch_size=20`.
6. **Key rotation for PHI encryption** — currently single-key. To rotate, `PhiEncryptor` needs to support `{enc:v2}` writes under a new key while still decrypting `{enc:v1}` rows; plus a sweep job that re-saves every row.

When working on adjacent code, prefer creating a focused PR for one of these over bundling.

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