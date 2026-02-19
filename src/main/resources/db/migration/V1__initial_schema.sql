-- V1: Initial schema for oncology intake system

CREATE TABLE patients (
    id UUID PRIMARY KEY,
    whatsapp_number VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100),
    cancer_type VARCHAR(100),
    age INTEGER,
    weight_kg NUMERIC(5, 2),
    pain_scale INTEGER,
    diagnosis_date DATE,
    conversation_state VARCHAR(255) NOT NULL,
    intake_completed BOOLEAN,
    pet_scan_uploaded BOOLEAN,
    blood_report_uploaded BOOLEAN,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_interaction_at TIMESTAMP,
    is_active BOOLEAN,
    consent_given BOOLEAN,
    consent_timestamp TIMESTAMP
);

CREATE INDEX idx_whatsapp_number ON patients (whatsapp_number);
CREATE INDEX idx_created_at ON patients (created_at);

CREATE TABLE reports (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    report_type VARCHAR(255) NOT NULL,
    storage_location VARCHAR(500) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255),
    content_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT,
    checksum VARCHAR(64),
    whatsapp_media_id VARCHAR(100),
    uploaded_at TIMESTAMP NOT NULL,
    processed BOOLEAN,
    processing_notes TEXT
);

CREATE INDEX idx_patient_id ON reports (patient_id);
CREATE INDEX idx_report_type ON reports (report_type);
CREATE INDEX idx_uploaded_at ON reports (uploaded_at);

CREATE TABLE analyses (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    formula_version VARCHAR(20) NOT NULL,
    derived_metrics_json JSONB,
    recommended_medicines_json JSONB,
    supportive_care_json JSONB,
    alerts_json JSONB,
    assessment_summary TEXT,
    disclaimer_text TEXT NOT NULL,
    requires_urgent_review BOOLEAN,
    sent_to_patient BOOLEAN,
    sent_at TIMESTAMP,
    reviewed_by_physician BOOLEAN,
    physician_review_notes TEXT,
    physician_reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    input_snapshot_json JSONB
);

CREATE INDEX idx_analysis_patient_id ON analyses (patient_id);
CREATE INDEX idx_analysis_created_at ON analyses (created_at);
CREATE INDEX idx_formula_version ON analyses (formula_version);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    patient_id UUID,
    action VARCHAR(255) NOT NULL,
    action_detail VARCHAR(500),
    actor_id VARCHAR(100),
    actor_type VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    metadata_json JSONB,
    success BOOLEAN,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_audit_patient_id ON audit_logs (patient_id);
CREATE INDEX idx_audit_action ON audit_logs (action);
CREATE INDEX idx_audit_created_at ON audit_logs (created_at);
CREATE INDEX idx_audit_actor ON audit_logs (actor_id);

CREATE TABLE doctors (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    domain VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(255),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE tumor_board_reviews (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    doctor_id UUID REFERENCES doctors(id),
    physician_domain VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    selected_protocols JSONB,
    notes TEXT,
    recommendations TEXT,
    created_at TIMESTAMP,
    reviewed_at TIMESTAMP
);

CREATE TABLE final_protocols (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL UNIQUE REFERENCES patients(id),
    cancer_type VARCHAR(255) NOT NULL,
    ecs_protocol JSONB,
    diet_fasting_protocol JSONB,
    mushroom_protocol JSONB,
    herb_protocol JSONB,
    drug_protocol JSONB,
    specialty_protocol JSONB,
    consolidated_notes TEXT,
    status VARCHAR(255) NOT NULL,
    approval_count INTEGER NOT NULL,
    sent_to_patient BOOLEAN NOT NULL,
    sent_at TIMESTAMP,
    created_at TIMESTAMP,
    approved_at TIMESTAMP
);
