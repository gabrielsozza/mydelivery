ALTER TABLE crm_leads ADD COLUMN assigned_collaborator_id BIGINT NULL;
CREATE INDEX idx_crm_assigned_collaborator ON crm_leads(assigned_collaborator_id, id);

CREATE TABLE crm_appointments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 lead_id BIGINT NOT NULL,
 collaborator_id BIGINT NOT NULL,
 contact_type VARCHAR(16) NOT NULL,
 scheduled_at DATETIME(6) NOT NULL,
 note VARCHAR(1000) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 CONSTRAINT fk_crm_appointment_lead FOREIGN KEY(lead_id) REFERENCES crm_leads(id),
 INDEX idx_crm_appointment_owner(collaborator_id, status, scheduled_at),
 INDEX idx_crm_appointment_lead(lead_id)
);
