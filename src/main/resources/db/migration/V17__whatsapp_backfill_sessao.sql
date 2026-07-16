-- ═══════════════════════════════════════════════════════════════════
-- BACKFILL de sessao_iniciada_em nas instâncias existentes.
--
-- CONTEXTO: colunas novas (sessao_iniciada_em, warmup_forcado_ate,
-- ultimo_heartbeat_em, ultimo_heartbeat_ok, heartbeats_falhados_seguidos,
-- msgs_ciclo_atual) e a tabela whatsapp_desconexao_log são criadas pelo
-- Hibernate ddl-auto=update baseado nas entities. Isso evita o problema
-- de Duplicate column que V15/V16 anteriores tiveram (Hibernate criava
-- ANTES do Flyway rodar).
--
-- O que Hibernate NÃO faz sozinho: backfill de dados. Sem esse UPDATE,
-- todas as instâncias antigas ficariam com sessao_iniciada_em=NULL e o
-- HealthJob cairia no fallback pra conectado_em (preservando comportamento
-- anterior — bug do warmup permanente NÃO fica corrigido).
--
-- Idempotente: só atualiza onde ainda tá NULL.
-- ═══════════════════════════════════════════════════════════════════

UPDATE whatsapp_instances
   SET sessao_iniciada_em = COALESCE(conectado_em, criado_em, CURRENT_TIMESTAMP)
 WHERE sessao_iniciada_em IS NULL;
