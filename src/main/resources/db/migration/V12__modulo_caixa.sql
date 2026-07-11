-- Módulo Caixa (jul/2026) — controle de abertura, movimentações e fechamento.
-- Estrutura Fase 1: um caixa aberto por restaurante por vez. Fase 2 pode
-- adicionar coluna terminal_id + remover o índice único parcial pra
-- múltiplos caixas simultâneos.

CREATE TABLE caixas (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id        BIGINT NOT NULL,
    operador_email        VARCHAR(120) NULL,
    operador_nome         VARCHAR(120) NULL,
    valor_inicial         DECIMAL(12,2) NOT NULL DEFAULT 0,
    valor_esperado        DECIMAL(12,2) NULL,
    valor_encontrado      DECIMAL(12,2) NULL,
    diferenca             DECIMAL(12,2) NULL,
    aberto_em             DATETIME NOT NULL,
    fechado_em            DATETIME NULL,
    status                VARCHAR(15) NOT NULL DEFAULT 'ABERTO',
    observacao_fechamento TEXT NULL,
    criado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_caixa_restaurante FOREIGN KEY (restaurante_id)
        REFERENCES restaurantes(id) ON DELETE CASCADE,
    INDEX idx_cx_rest_status (restaurante_id, status),
    INDEX idx_cx_aberto_em (aberto_em)
);

CREATE TABLE movimentacoes_caixa (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    caixa_id       BIGINT NOT NULL,
    tipo           VARCHAR(20) NOT NULL,
    valor          DECIMAL(12,2) NOT NULL,
    descricao      TEXT NULL,
    pedido_id      BIGINT NULL,
    operador_email VARCHAR(120) NULL,
    criado_em      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mv_caixa FOREIGN KEY (caixa_id) REFERENCES caixas(id) ON DELETE CASCADE,
    INDEX idx_mv_caixa (caixa_id),
    INDEX idx_mv_caixa_tipo (caixa_id, tipo),
    INDEX idx_mv_pedido (pedido_id)
);
