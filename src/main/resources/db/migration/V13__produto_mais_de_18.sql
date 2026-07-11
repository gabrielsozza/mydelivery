-- Bug fix jul/2026: campo "+18" existia no front do cardápio mas nunca
-- persistia — a coluna não existia. Jackson descartava o campo silêncio,
-- toggle voltava desmarcado ao reabrir. Ninguém tinha reclamado até uma
-- açaiteria querer vender licor.
--
-- DEFAULT FALSE = todos os produtos existentes continuam liberados; só
-- os marcados agora começam a exigir confirmação de idade no cardápio
-- público.

ALTER TABLE produtos
    ADD COLUMN mais_de_18 BOOLEAN NOT NULL DEFAULT FALSE;
