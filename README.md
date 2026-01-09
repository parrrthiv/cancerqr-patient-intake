# Cancer Patient Intake System

A secure Spring Boot backend application that integrates with WhatsApp Business Cloud API to capture cancer patient intake details, store medical reports, and generate initial medicine suggestions based on preset formulas.

> ⚠️ **IMPORTANT DISCLAIMER**: This system provides **initial suggestions only** and does NOT replace professional medical advice. All recommendations must be reviewed by a qualified oncologist before any treatment begins.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [WhatsApp Setup](#whatsapp-setup)
- [API Endpoints](#api-endpoints)
- [Database Schema](#database-schema)
- [Security](#security)
- [Testing](#testing)
- [Deployment](#deployment)

## Features

### Patient Intake Flow
- Structured WhatsApp conversation flow for patient intake
- Capture patient demographics: age, weight, pain scale, diagnosis date
- Medical report uploads: PET scan and blood report (images/PDFs)
- Input validation with friendly error messages
- Interactive buttons for pain scale selection

### Medicine Suggestion Engine
- Rule-based formula engine with versioning
- Pain-based treatment recommendations (WHO analgesic ladder)
- Dose adjustments for age and weight
- Supportive care recommendations
- Alert system for urgent cases
- Mandatory disclaimers on all outputs

### Storage & Security
- Multi-backend file storage (local, S3, MinIO)
- Encrypted data at rest
- HTTPS/TLS support
- Webhook signature verification
- PHI-safe logging (no sensitive data logged)
- Comprehensive audit logging

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        WhatsApp Users                           │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                WhatsApp Business Cloud API                      │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              WhatsApp Webhook Controller                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                │                                 │
│  ┌─────────────┐  ┌───────────┴───────────┐  ┌─────────────┐   │
│  │ Conversation│  │   Patient Intake      │  │  Analysis   │   │
│  │   Service   │──│      Service          │──│   Service   │   │
│  │(State Machine)│ └───────────────────────┘  └─────────────┘   │
│  └─────────────┘              │                      │          │
│         │         ┌───────────┴──────────┐  ┌────────┴───────┐  │
│         │         │    Storage Service   │  │ Formula Engine │  │
│         │         └──────────────────────┘  └────────────────┘  │
│         │                     │                                  │
│  ┌──────┴───────────┐  ┌─────┴─────┐                           │
│  │ WhatsApp Client  │  │  S3/MinIO │                           │
│  └──────────────────┘  └───────────┘                           │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              PostgreSQL / H2 Database                    │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │   │
│  │  │ Patients │  │ Reports  │  │ Analyses │  │  Audit   │ │   │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Java 17 or higher
- Maven 3.8+
- PostgreSQL 14+ (for production) or H2 (for development)
- WhatsApp Business Account with Cloud API access
- S3-compatible storage (AWS S3 or MinIO) for production

## Quick Start

### 1. Clone and Build

```bash
git clone <repository-url>
cd cancer-intake-system
mvn clean install
```

### 2. Configure Environment

Create a `.env` file or set environment variables:

```bash
# WhatsApp Configuration (Required)
export WHATSAPP_PHONE_NUMBER_ID=your-phone-number-id
export WHATSAPP_ACCESS_TOKEN=your-access-token
export WHATSAPP_VERIFY_TOKEN=your-verify-token

# Database (Optional - defaults to H2)
export DATABASE_URL=jdbc:postgresql://localhost:5432/oncologydb
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=your-password

# Storage (Optional - defaults to local)
export STORAGE_TYPE=local  # or s3, minio
export LOCAL_STORAGE_PATH=./uploads
```

### 3. Run the Application

```bash
# Development mode (H2 database)
mvn spring-boot:run

# Or with production profile
mvn spring-boot:run -Dspring-boot.run.profiles=production
```

### 4. Expose Webhook (Development)

Use ngrok or similar to expose local server:

```bash
ngrok http 8080
```

Use the ngrok URL for WhatsApp webhook configuration.

## Configuration

### Application Properties

Key configuration in `application.yml`:

```yaml
# WhatsApp API
whatsapp:
  api:
    base-url: https://graph.facebook.com/v18.0
    phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID}
    access-token: ${WHATSAPP_ACCESS_TOKEN}
    verify-token: ${WHATSAPP_VERIFY_TOKEN}
    webhook-secret: ${WHATSAPP_WEBHOOK_SECRET}

# Storage
storage:
  type: ${STORAGE_TYPE:local}
  local:
    base-path: ${LOCAL_STORAGE_PATH:./uploads}
  s3:
    bucket-name: ${S3_BUCKET_NAME:oncology-reports}
    region: ${AWS_REGION:us-east-1}

# Formula Engine
formula:
  version: "1.0.0"
  config-path: classpath:formula-rules.yml
```

### Formula Rules

Medicine rules are defined in `formula-rules.yml`:

```yaml
pain_management:
  - level: "MILD"
    pain_range: [1, 3]
    medicines:
      - name: "Paracetamol"
        category: "Non-opioid analgesic"
        base_dose_mg: 500
        frequency: "every 6-8 hours"
        max_daily_mg: 4000
        duration_days: 7
```

## WhatsApp Setup

### 1. Create WhatsApp Business Account

1. Go to [Meta Business Suite](https://business.facebook.com/)
2. Create a WhatsApp Business account
3. Enable Cloud API access

### 2. Configure Webhook

1. In Meta Developer Console, go to WhatsApp > Configuration
2. Set Webhook URL: `https://your-domain.com/api/webhook/whatsapp`
3. Set Verify Token: (same as `WHATSAPP_VERIFY_TOKEN`)
4. Subscribe to: `messages`, `message_status`

### 3. Get API Credentials

1. Phone Number ID: WhatsApp > Getting Started
2. Access Token: Create a System User token with `whatsapp_business_messaging` permission

## API Endpoints

### Webhook Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/webhook/whatsapp` | Webhook verification |
| POST | `/api/webhook/whatsapp` | Receive WhatsApp events |

### Health Check

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/actuator/health` | Application health |
| GET | `/api/actuator/info` | Application info |

## Database Schema

### Patients Table

```sql
CREATE TABLE patients (
    id UUID PRIMARY KEY,
    whatsapp_number VARCHAR(20) UNIQUE NOT NULL,
    age INTEGER,
    weight_kg DECIMAL(5,2),
    pain_scale INTEGER,
    diagnosis_date DATE,
    conversation_state VARCHAR(50) NOT NULL,
    intake_completed BOOLEAN DEFAULT FALSE,
    pet_scan_uploaded BOOLEAN DEFAULT FALSE,
    blood_report_uploaded BOOLEAN DEFAULT FALSE,
    consent_given BOOLEAN DEFAULT FALSE,
    consent_timestamp TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_interaction_at TIMESTAMP
);
```

### Reports Table

```sql
CREATE TABLE reports (
    id UUID PRIMARY KEY,
    patient_id UUID REFERENCES patients(id),
    report_type VARCHAR(50) NOT NULL,
    storage_location VARCHAR(500) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255),
    content_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT,
    checksum VARCHAR(64),
    whatsapp_media_id VARCHAR(100),
    processed BOOLEAN DEFAULT FALSE,
    uploaded_at TIMESTAMP NOT NULL
);
```

### Analyses Table

```sql
CREATE TABLE analyses (
    id UUID PRIMARY KEY,
    patient_id UUID REFERENCES patients(id),
    formula_version VARCHAR(20) NOT NULL,
    derived_metrics_json JSONB,
    recommended_medicines_json JSONB,
    supportive_care_json JSONB,
    alerts_json JSONB,
    assessment_summary TEXT,
    disclaimer_text TEXT NOT NULL,
    requires_urgent_review BOOLEAN DEFAULT FALSE,
    sent_to_patient BOOLEAN DEFAULT FALSE,
    sent_at TIMESTAMP,
    reviewed_by_physician BOOLEAN DEFAULT FALSE,
    physician_review_notes TEXT,
    input_snapshot_json JSONB,
    created_at TIMESTAMP NOT NULL
);
```

## Security

### Data Protection

- All PHI is encrypted at rest
- HTTPS required in production
- Webhook signature verification
- PHI excluded from logs

### Privacy Compliance

- Consent tracking before data collection
- Data anonymization support
- Audit logging for all data access
- Retention policy support

### Best Practices

1. Never log patient data
2. Use environment variables for secrets
3. Enable webhook signature verification
4. Regular security audits

## Testing

### Run Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=FormulaEngineTest

# With coverage
mvn test jacoco:report
```

### Test Categories

- **Unit Tests**: Formula engine, validation logic
- **Integration Tests**: Database, storage operations
- **API Tests**: Webhook endpoints

## Deployment

### Docker

```dockerfile
FROM eclipse-temurin:17-jre
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Docker Compose

```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=production
      - DATABASE_URL=jdbc:postgresql://db:5432/oncologydb
    depends_on:
      - db
      - minio
  
  db:
    image: postgres:14
    environment:
      POSTGRES_DB: oncologydb
      POSTGRES_PASSWORD: secret
    volumes:
      - pgdata:/var/lib/postgresql/data
  
  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    volumes:
      - miniodata:/data

volumes:
  pgdata:
  miniodata:
```

### Production Checklist

- [ ] Use PostgreSQL with proper backup
- [ ] Configure S3/MinIO for file storage
- [ ] Set up HTTPS/TLS
- [ ] Enable webhook signature verification
- [ ] Configure proper logging
- [ ] Set up monitoring and alerts
- [ ] Review and update formula rules with medical team
- [ ] Legal review of disclaimers

## License

MIT License - See LICENSE file

## Support

For issues and questions, please create a GitHub issue.

---

**⚠️ MEDICAL DISCLAIMER**: This software is intended for initial assessment purposes only. It does not provide medical diagnoses or treatment recommendations. All output from this system must be reviewed by qualified healthcare professionals. Do not start, stop, or change any medication based solely on this system's suggestions.
