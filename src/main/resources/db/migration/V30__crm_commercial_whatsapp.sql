CREATE TABLE crm_commercial_read_state (
 lead_id BIGINT PRIMARY KEY,
 last_read_interaction_id BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_crm_commercial_read_lead FOREIGN KEY (lead_id) REFERENCES crm_leads(id) ON DELETE CASCADE
);

CREATE TABLE crm_commercial_sends (
 request_key VARCHAR(80) PRIMARY KEY,
 lead_id BIGINT NOT NULL,
 collaborator_id BIGINT NOT NULL,
 body TEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 provider_id VARCHAR(200) NULL,
 created_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 CONSTRAINT fk_crm_commercial_send_lead FOREIGN KEY (lead_id) REFERENCES crm_leads(id) ON DELETE CASCADE,
 INDEX idx_crm_commercial_send_lead (lead_id, created_at)
);
