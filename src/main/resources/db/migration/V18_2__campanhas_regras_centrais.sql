-- Regras administradas pelo MyDelivery. Não altera bloqueios, consentimentos ou filas.
ALTER TABLE campanha_controles ADD COLUMN ritmo_passos BIGINT NOT NULL DEFAULT 0;
ALTER TABLE campanha_controles ALTER COLUMN fim SET DEFAULT '23:00';
UPDATE campanha_controles SET inicio='09:00',fim='23:00';
