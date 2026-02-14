# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Build with tests
./mvnw clean package

# Run application (dev profile, H2 in-memory DB)
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=FormulaEngineTest

# Run a single test method
./mvnw test -Dtest=FormulaEngineTest#testMildPainPatientAnalysis

# Run with production profile (requires PostgreSQL + env vars)
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## Required Environment Variables (Production)

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — PostgreSQL connection
- `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_WEBHOOK_SECRET` — WhatsApp Business API
- `STORAGE_TYPE` (`local`, `s3`, `minio`), `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` — File storage
- `ANTHROPIC_API_KEY` — AI verification service (Claude API)

## Architecture

Spring Boot 3.2.1 / Java 17 REST API that provides a WhatsApp-based cancer patient intake system. Patients interact via WhatsApp chat, the system collects medical data through a conversation state machine, and generates rule-based medicine suggestions.

### Request Flow

```
WhatsApp → WhatsAppWebhookController → ConversationService (state machine)
                                           ↓
                                      PatientIntakeService (persist patient data)
                                           ↓
                                      AnalysisService → FormulaEngine (generate suggestions)
                                           ↓
                                      AIVerificationService (optional Claude API review)
                                           ↓
                                      WhatsAppClientService (send results back)
```

### Key Components

**ConversationService** — Central state machine managing the WhatsApp intake flow. Patient progresses through 11 states: `INITIAL → AWAITING_CONSENT → ASK_AGE → ASK_WEIGHT → ASK_PAIN_SCALE → ASK_DIAGNOSIS_DATE → ASK_PET_SCAN → ASK_BLOOD_REPORT → PROCESSING → RESULT_SENT → COMPLETED`. Each state validates specific input (age 0-120, weight 1-300kg, pain 0-10, date in YYYY-MM-DD format). Supports restart via `START`/`RESTART` commands.

**FormulaEngine** — Rule-based medicine suggestion engine loaded from `formula-rules.yml`. Computes derived metrics (pain/weight/age categories, dose adjustment factors) and generates recommendations across 5 categories: CBD therapy (weight-based dosing with age adjustments), mono herbs (5 selected), functional mushrooms (3 selected), repurposed compounds (informational only), and fasting/diet protocols. Generates alerts for urgent cases (pediatric, severe pain, elderly, underweight, newly diagnosed).

**StorageService** — Abstraction over local filesystem, AWS S3, and MinIO for storing uploaded medical reports (PET scans, blood reports). Backend selected via `app.storage.type` config.

**WhatsAppClientService** — Reactive WebClient-based client for WhatsApp Business API. Handles text messages, interactive buttons/lists, and media downloads.

### Data Model

- **Patient** — Core entity with WhatsApp number (unique key), demographics, medical data, and `ConversationState` enum tracking conversation progress
- **Report** — Medical report metadata with `ReportType` enum (PET_SCAN, BLOOD_REPORT, CT_SCAN, etc.) and storage location references
- **Analysis** — Generated suggestions stored as JSON columns (derived_metrics, recommended_medicines, supportive_care, alerts) with formula version for audit trail
- **AuditLog** — PHI-safe audit trail using only IDs, with 28 action types and actor tracking

### Configuration Files

- `application.yml` — Main config with dev (H2) and prod (PostgreSQL) profiles
- `formula-rules.yml` — Medicine suggestion rules, dosing tables, herb/mushroom databases
- `cancerqr-protocols.yml` — Tumor board protocol definitions

### Patterns

- Constructor injection via Lombok `@RequiredArgsConstructor` throughout
- `@EnableAsync` on main class; async processing for WhatsApp event handling
- Spring WebFlux `WebClient` for non-blocking WhatsApp API calls (not a fully reactive app)
- Webhook signature verification via HMAC-SHA256 in the controller
- GDPR anonymization support in PatientRepository
- All analysis outputs include mandatory medical disclaimers

### Testing

Two test classes exist using JUnit 5:
- `FormulaEngineTest` — Tests formula engine calculations, derived metrics, alert generation, supportive care, and disclaimers using nested `@Nested` test classes
- `ConversationServiceTest` — Tests state machine transitions and input validation (age, weight, pain scale, diagnosis date) using Mockito mocks

Both use standard `@SpringBootTest` / unit test patterns with no custom test infrastructure.
