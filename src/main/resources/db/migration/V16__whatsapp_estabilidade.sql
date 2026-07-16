-- ═══════════════════════════════════════════════════════════════════
-- FIX: BUG WARMUP PERMANENTE + trilha de auditoria de desconexões
--
-- BUG #1 (crítico): WhatsappService.marcarConectada resetava conectadoEm
-- toda vez que o webhook de sucesso chegava. HealthJob usa esse campo pra
-- calcular "está em warmup <48h?", então instâncias que caíam e voltavam
-- viravam "conta nova permanente" e auto-reconexão ficava bloqueada.
--
-- FIX: nova coluna sessao_iniciada_em setada UMA VEZ no primeiro sucesso
-- e NUNCA resetada. Warmup passa a olhar este campo. conectadoEm continua
-- rastreando "última conexão", útil pra métricas.
--
-- Backfill: instâncias existentes ganham sessao_iniciada_em = COALESCE(
-- conectado_em, criado_em, agora). Retrocompat garantida.
--
-- ── OUTROS CAMPOS ──
--  warmup_forcado_ate: kill-switch admin. Se preenchido, warmup fica off
--    até essa data. Uso: dono migra número usado no WhatsApp Business
--    (não é conta nova de fato).
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS sessao_iniciada_em TIMESTAMP NULL;

ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS warmup_forcado_ate TIMESTAMP NULL;

-- Backfill: usa conectado_em (última conexão conhecida) ou criado_em como
-- fallback. Isso mantém warmup 48h pra realmente novas E libera as
-- instâncias antigas (criado_em >> 48h atrás = warmup expirado).
UPDATE whatsapp_instances
   SET sessao_iniciada_em = COALESCE(conectado_em, criado_em, CURRENT_TIMESTAMP)
 WHERE sessao_iniciada_em IS NULL;


-- ═══════════════════════════════════════════════════════════════════
-- LOG DE DESCONEXÕES — trilha de auditoria por evento
--
-- 1 linha por queda ou tentativa de reconexão. Serve pra:
--  - Admin ver "essa instância caiu 4x essa semana"
--  - Diagnóstico remoto: correlation_id atravessa webhook → job →
--    tentativa → resultado
--  - Dono ver no painel "última queda foi X, sistema tentou reconectar
--    às Y, resultado Z"
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS whatsapp_desconexao_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    instance_name VARCHAR(80) NOT NULL,
    -- Tipo de evento:
    --   QUEDA — instância mudou de CONECTADA pra DESCONECTADA
    --   RECONEXAO_TENTADA — health job disparou /instance/restart
    --   RECONEXAO_OK — reconexão restaurou conexão
    --   RECONEXAO_FALHA — tentativa não teve efeito
    --   HEARTBEAT_FALHOU — /instance/status ou self-check indicou anomalia
    --   MARCADA_INSTAVEL — sistema mudou status pra INSTAVEL sem queda física
    tipo VARCHAR(30) NOT NULL,
    -- Motivo humano-legível ("phone sumiu no status", "conta em <48h", "usbprint claim", etc)
    motivo VARCHAR(200) NULL,
    -- Código bruto retornado pela API Uazapi quando aplicável
    codigo_api VARCHAR(60) NULL,
    -- Snapshot do momento do evento
    status_antes VARCHAR(25) NULL,
    status_depois VARCHAR(25) NULL,
    -- Métricas do CICLO que terminou (só preenchido em QUEDA)
    conectado_desde TIMESTAMP NULL,
    duracao_min INT NULL,
    msgs_processadas_no_ciclo INT NULL,
    -- Vínculo com tentativa de reconexão em cascata
    tentativa_num INT NULL,
    correlation_id VARCHAR(60) NULL,
    criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wa_desc_log_inst FOREIGN KEY (instance_id)
        REFERENCES whatsapp_instances(id) ON DELETE CASCADE
);

-- Query mais comum: histórico da última semana por instância
CREATE INDEX IF NOT EXISTS idx_wa_desc_log_inst_data
    ON whatsapp_desconexao_log (instance_id, criado_em DESC);

-- Dashboard admin: contagem de eventos por tipo nas últimas 24h
CREATE INDEX IF NOT EXISTS idx_wa_desc_log_tipo_data
    ON whatsapp_desconexao_log (tipo, criado_em DESC);


-- ═══════════════════════════════════════════════════════════════════
-- HEARTBEAT REAL — controle de ping ativo à Uazapi
--
-- Colunas pra rastrear as últimas verificações ativas:
--  - ultimo_heartbeat_em: última vez que /instance/status foi checado
--  - ultimo_heartbeat_ok: resultado da última checagem
--  - heartbeats_falhados_seguidos: contador — se >= 3, marca INSTAVEL
--    mesmo com Uazapi dizendo "conectada"
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS ultimo_heartbeat_em TIMESTAMP NULL;

ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS ultimo_heartbeat_ok BOOLEAN NULL;

ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS heartbeats_falhados_seguidos INT NOT NULL DEFAULT 0;

-- Contador de mensagens do CICLO ATUAL (zera em cada nova conexão).
-- Usado pra métrica "msgs_processadas_no_ciclo" no desconexao_log
-- quando a instância cai.
ALTER TABLE whatsapp_instances
    ADD COLUMN IF NOT EXISTS msgs_ciclo_atual INT NOT NULL DEFAULT 0;
