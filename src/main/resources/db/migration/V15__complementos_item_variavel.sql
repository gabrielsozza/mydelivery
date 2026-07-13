-- ═══════════════════════════════════════════════════════════════════
-- FEAT: "Cardápio do dia" — item de complemento variável (marmitex etc)
--
-- Contexto: restaurantes de marmita/PF têm cardápio rotativo — algumas
-- guarnições/carnes/saladas mudam todo dia. Novo fluxo: marca item como
-- "variável" e ganha tela rápida "O que tem hoje?" com toggle em massa.
--
-- ── Idempotência ──
-- Hibernate ddl-auto=update pode ter adicionado a coluna antes do
-- Flyway rodar (v1 dessa migration crashou na Railway assim). ADD
-- COLUMN IF NOT EXISTS é suportado no MySQL 8.0.29+ e Postgres 9.6+.
--
-- ── Sem partial index ──
-- Versão anterior usava CREATE INDEX ... WHERE variavel=true (partial
-- index), sintaxe só do Postgres — MySQL falharia. Index padrão simples
-- funciona em ambos. Volume esperado é pequeno (dezenas de itens
-- variáveis por restaurante), então overhead do index cheio é ínfimo.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE complementos_item
    ADD COLUMN IF NOT EXISTS variavel BOOLEAN NOT NULL DEFAULT false;
