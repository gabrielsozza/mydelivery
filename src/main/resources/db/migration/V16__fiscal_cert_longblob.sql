-- ═══════════════════════════════════════════════════════════════════════
-- FISCAL V16 — Corrige tipo da coluna pfx_ciphertext em certificado_digital
--
-- Hibernate 6 + MySQL: `@Lob byte[]` sem columnDefinition gera TINYBLOB
-- (255 bytes). Um certificado A1 cifrado AES-GCM tem ~3-4 KB — TRUNCA
-- na hora do save (erro "Data too long for column 'pfx_ciphertext'").
--
-- Fix: MODIFY pra LONGBLOB (mesmo tipo que o V15__fiscal_base.sql já
-- declara — essa migration existe pro caso do schema ter sido criado
-- por ddl-auto antes do V15 aplicar).
--
-- ALTER TABLE MODIFY não usa IF NOT EXISTS, só substitui — safe.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE certificado_digital MODIFY pfx_ciphertext LONGBLOB NOT NULL;
