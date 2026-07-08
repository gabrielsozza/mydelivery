-- Toggle da feature "integração com balança de pesagem" no Balcão.
-- Default false: 100% dos restaurantes atuais continuam com fluxo antigo
-- (porções via complementos) e o frontend nem carrega o módulo Web Serial.
-- Vira on-demand quando dono liga na tela de Configurações.

ALTER TABLE restaurantes
    ADD COLUMN balanca_ativa BOOLEAN NOT NULL DEFAULT FALSE;
