-- Suporte a pagamento dividido no BALCÃO (jul/2026).
-- Operador pode dividir 1 pedido entre 2 formas (PIX+Dinheiro, Crédito+PIX,
-- etc). Coluna JSON evita entity nova e mantém retrocompat total: pedidos
-- antigos ficam com pagamentos_json=NULL e continuam usando forma_pagamento.
--
-- Formato: [{"forma":"PIX","valor":20.00},{"forma":"DINHEIRO","valor":15.00}]
-- Regra: soma dos valores == pedido.total. Validado no service.

ALTER TABLE pedidos
    ADD COLUMN pagamentos_json TEXT NULL;
