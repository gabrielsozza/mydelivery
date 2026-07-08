-- Módulo Equipe: membros com login/senha próprios + permissões granulares.
-- Módulo Auditoria: registro de ações sensíveis pra futura tela de histórico.

CREATE TABLE membros_equipe (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id  BIGINT       NOT NULL,
    nome_completo   VARCHAR(120) NOT NULL,
    email           VARCHAR(160) NULL,
    telefone        VARCHAR(30)  NULL,
    login           VARCHAR(60)  NOT NULL,
    senha_hash      VARCHAR(100) NOT NULL,
    cargo           VARCHAR(20)  NOT NULL DEFAULT 'FUNCIONARIO',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ATIVO',
    permissoes_csv  VARCHAR(2000) NULL,
    token_version   INT          NOT NULL DEFAULT 0,
    criado_em       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    criado_por      VARCHAR(160) NULL,
    ultimo_login_em TIMESTAMP    NULL,
    CONSTRAINT fk_membro_restaurante FOREIGN KEY (restaurante_id)
        REFERENCES restaurantes(id) ON DELETE CASCADE,
    CONSTRAINT uk_membro_login_rest UNIQUE (restaurante_id, login)
);

CREATE INDEX idx_membro_login_ci ON membros_equipe (login);
CREATE INDEX idx_membro_rest_status ON membros_equipe (restaurante_id, status);

CREATE TABLE logs_auditoria (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id  BIGINT       NOT NULL,
    membro_id       BIGINT       NULL,
    ator_label      VARCHAR(200) NULL,
    acao            VARCHAR(40)  NOT NULL,
    entidade_tipo   VARCHAR(40)  NULL,
    entidade_id     VARCHAR(60)  NULL,
    detalhes_json   TEXT         NULL,
    ip              VARCHAR(60)  NULL,
    criado_em       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_log_rest_ts   ON logs_auditoria (restaurante_id, criado_em);
CREATE INDEX idx_log_membro    ON logs_auditoria (membro_id);
CREATE INDEX idx_log_acao      ON logs_auditoria (acao);
