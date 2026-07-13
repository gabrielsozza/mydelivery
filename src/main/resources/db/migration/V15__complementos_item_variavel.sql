-- ═══════════════════════════════════════════════════════════════════
-- FEAT: "Cardápio do dia" — item de complemento variável (marmitex etc)
--
-- Contexto: restaurantes de marmita/PF têm cardápio rotativo — algumas
-- guarnições/carnes/saladas mudam todo dia (moqueca só quarta, batata
-- frita só quando tem batata etc). Hoje o dono precisa entrar item por
-- item pra desativar. Novo fluxo: marca o item como "variável" e ganha
-- uma tela rápida "O que tem hoje?" que faz toggle em massa.
--
-- - variavel: se true, item aparece na tela "Cardápio do dia" pro dono
--   controlar rápido. Não afeta cardápio público diretamente (é o ativo
--   que continua controlando visibilidade).
-- - Restaurantes que não usam marcar nada como variável nem veem a tela.
-- ═══════════════════════════════════════════════════════════════════

ALTER TABLE complementos_item
    ADD COLUMN variavel BOOLEAN NOT NULL DEFAULT false;

-- Index parcial pra query da tela "Cardápio do dia" (filtra só variáveis)
CREATE INDEX idx_complementos_item_variavel ON complementos_item(grupo_id) WHERE variavel = true;
