-- Migração pra Uazapi: apaga todas as instâncias antigas (era Evolution)
-- pra que cada loja crie do zero no Uazapi com nome estável (mydelivery-rest-{id}).
--
-- Motivação: as linhas legadas têm nome com timestamp (mydelivery-rest-21-1783682852)
-- porque na Evolution cada Reset gerava nome novo. No Uazapi cada instância
-- consome 1 slot de 300 do plano — nome estável evita bomba de slots.
--
-- Efeito prático: TODOS os donos precisam escanear QR novamente uma vez.
-- É uma migração forçada, mas garante estado limpo. Sem isso, o backend
-- ficaria chamando /instance/status com nomes fantasmas que o Uazapi
-- não conhece → falha silenciosa mostrando "Conectado" no painel.

SET FOREIGN_KEY_CHECKS = 0;

-- Tabelas dependentes de whatsapp_instances (FK instance_id)
DELETE FROM whatsapp_health_log;
DELETE FROM whatsapp_incidentes;
DELETE FROM whatsapp_acoes_automaticas;

-- Instâncias — o próximo /conectar de cada loja recria com nome novo
DELETE FROM whatsapp_instances;

SET FOREIGN_KEY_CHECKS = 1;
