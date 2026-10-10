CREATE TABLE crm_settings (
 id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 daily_limit INT NOT NULL DEFAULT 40, hourly_limit INT NOT NULL DEFAULT 10,
 interval_seconds INT NOT NULL DEFAULT 60, start_time VARCHAR(5) NOT NULL DEFAULT '09:00',
 end_time VARCHAR(5) NOT NULL DEFAULT '20:00', last_attempt_at DATETIME(6) NULL
);
INSERT INTO crm_settings(id) VALUES (1);
CREATE TABLE crm_leads (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, profile LONGTEXT NOT NULL,
 stage VARCHAR(32) NOT NULL DEFAULT 'NOVO_LEAD', handoff VARCHAR(40) NOT NULL DEFAULT 'AUTOMATICO',
 do_not_contact BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
 prepared_message TEXT NULL, sent_message TEXT NULL, sent_at DATETIME(6) NULL,
 attempts INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
 last_interaction_at DATETIME(6) NULL, next_action_at DATETIME(6) NULL,
 customer_store_id BIGINT NULL UNIQUE, INDEX idx_crm_stage(stage), INDEX idx_crm_created(created_at)
);
CREATE TABLE crm_identities (
 identity_key VARCHAR(80) PRIMARY KEY, lead_id BIGINT NOT NULL,
 CONSTRAINT fk_crm_identity_lead FOREIGN KEY(lead_id) REFERENCES crm_leads(id), INDEX idx_crm_identity_lead(lead_id)
);
CREATE TABLE crm_do_not_contact (
 phone_key VARCHAR(40) PRIMARY KEY, reason TEXT NOT NULL, created_at DATETIME(6) NOT NULL
);
CREATE TABLE crm_interactions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, lead_id BIGINT NOT NULL, type VARCHAR(40) NOT NULL,
 detail LONGTEXT NOT NULL, actor VARCHAR(160) NOT NULL, created_at DATETIME(6) NOT NULL,
 dedup_key VARCHAR(64) NULL UNIQUE,
 CONSTRAINT fk_crm_interaction_lead FOREIGN KEY(lead_id) REFERENCES crm_leads(id),
 INDEX idx_crm_history(lead_id, created_at), INDEX idx_crm_metrics(type, created_at)
);
CREATE TABLE crm_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, lead_id BIGINT NOT NULL, phone VARCHAR(20) NOT NULL,
 phone_key VARCHAR(40) NOT NULL, body TEXT NOT NULL, kind VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', request_key VARCHAR(80) NOT NULL UNIQUE,
 content_key VARCHAR(64) NOT NULL UNIQUE, initial_key VARCHAR(40) NULL UNIQUE,
 created_at DATETIME(6) NOT NULL, attempted_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
 provider_id VARCHAR(200) NULL, error_detail VARCHAR(500) NULL,
 CONSTRAINT fk_crm_outbox_lead FOREIGN KEY(lead_id) REFERENCES crm_leads(id),
 INDEX idx_crm_queue(status, id), INDEX idx_crm_outbox_lead(lead_id)
);
