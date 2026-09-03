-- ═══════════════════════════════════════════════════════════════════════
-- FISCAL — Fundação do módulo de emissão de NFC-e/NF-e
-- Cria as tabelas de: certificado A1 (cofre), perfil fiscal por CNPJ e
-- por produto, contador de numeração por CNPJ+serie, notas emitidas,
-- log de auditoria append-only.
--
-- IMPORTANTE:
--  - Toda coluna binária/texto que armazena dado sensível (cert, senha,
--    CSC) é ARMAZENADA JÁ CRIPTOGRAFADA. O banco NUNCA tem plain text.
--  - contador_numero_nfce usa lock pessimista no service (SELECT FOR UPDATE)
--    pra garantir sequência sem gap por CNPJ+serie+ambiente.
-- ═══════════════════════════════════════════════════════════════════════

-- ── Certificado A1 (cofre) ────────────────────────────────────────────
CREATE TABLE certificado_digital (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id            BIGINT NOT NULL,
    cnpj                      VARCHAR(20) NOT NULL,
    nome_titular              VARCHAR(255),
    -- Cert PKCS12 (.pfx) criptografado com AES-256-GCM + chave derivada por CNPJ
    -- (envelope encryption: chave-mestra no Railway secret + KDF por CNPJ).
    pfx_ciphertext            LONGBLOB NOT NULL,
    pfx_iv                    VARBINARY(16) NOT NULL,
    pfx_tag                   VARBINARY(16) NOT NULL,
    -- Senha do PFX criptografada separadamente (mesma proteção). Precisa das
    -- DUAS descriptografias pra abrir o certificado.
    senha_ciphertext          VARBINARY(1024) NOT NULL,
    senha_iv                  VARBINARY(16) NOT NULL,
    senha_tag                 VARBINARY(16) NOT NULL,
    -- Metadados extraídos do certificado ao subir (dá pra mostrar no painel
    -- sem ter que descriptografar toda hora).
    valido_ate                DATETIME NOT NULL,
    fingerprint_sha256        VARCHAR(128) NOT NULL,
    ativo                     BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_cert_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    INDEX idx_cert_cnpj (cnpj),
    INDEX idx_cert_restaurante (restaurante_id, ativo)
);

-- ── Perfil fiscal por CNPJ (loja) ─────────────────────────────────────
CREATE TABLE perfil_fiscal_restaurante (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id            BIGINT NOT NULL UNIQUE,
    cnpj                      VARCHAR(20) NOT NULL,
    razao_social              VARCHAR(255),
    nome_fantasia             VARCHAR(255),
    inscricao_estadual        VARCHAR(30),
    inscricao_municipal       VARCHAR(30),
    -- 1 = Simples Nacional, 2 = Simples Nacional (excesso sublimite), 3 = Regime Normal
    regime_tributario         INT NOT NULL DEFAULT 1,
    -- 1 = Produção, 2 = Homologação (SEFAZ de teste)
    ambiente_sefaz            INT NOT NULL DEFAULT 2,
    uf                        VARCHAR(2) NOT NULL,
    municipio_codigo_ibge     VARCHAR(10),
    endereco_logradouro       VARCHAR(255),
    endereco_numero           VARCHAR(20),
    endereco_bairro           VARCHAR(120),
    endereco_cep              VARCHAR(10),
    endereco_complemento      VARCHAR(120),
    -- CSC (Código de Segurança do Contribuinte) — obrigatório pra NFC-e.
    -- Também criptografado (é chave de acesso à SEFAZ pro CNPJ).
    csc_id                    VARCHAR(10),
    csc_ciphertext            VARBINARY(1024),
    csc_iv                    VARBINARY(16),
    csc_tag                   VARBINARY(16),
    -- Emissão habilitada? Contador libera o dono quando toda config estiver certa.
    emissao_ativa             BOOLEAN NOT NULL DEFAULT FALSE,
    criado_em                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_perfil_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    INDEX idx_perfil_cnpj (cnpj)
);

-- ── Perfil fiscal por produto ─────────────────────────────────────────
CREATE TABLE perfil_fiscal_produto (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    produto_id                BIGINT NOT NULL UNIQUE,
    -- NCM: 8 dígitos, obrigatório. Padrão preparado 21069090 (preparações alimentícias)
    -- mas contador deve confirmar por produto.
    ncm                       VARCHAR(10) NOT NULL DEFAULT '21069090',
    -- CFOP padrão pra venda ao consumidor final no mesmo estado.
    cfop                      VARCHAR(4) NOT NULL DEFAULT '5102',
    -- Regime Normal usa CST (3 dígitos ex "00"), Simples Nacional usa CSOSN (ex "102").
    cst                       VARCHAR(4),
    csosn                     VARCHAR(4) DEFAULT '102',
    -- Origem da mercadoria (0 = Nacional). Obrigatório.
    origem                    INT NOT NULL DEFAULT 0,
    -- Unidade comercial (UN, KG, LT, PCT...)
    unidade_comercial         VARCHAR(6) NOT NULL DEFAULT 'UN',
    -- Alíquotas — só se necessário conforme CST/CSOSN.
    aliquota_icms             DECIMAL(5,2) DEFAULT 0,
    aliquota_pis              DECIMAL(5,2) DEFAULT 0,
    aliquota_cofins           DECIMAL(5,2) DEFAULT 0,
    criado_em                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_perfil_prod_produto FOREIGN KEY (produto_id) REFERENCES produtos(id)
);

-- ── Contador de numeração NFC-e por CNPJ + serie + ambiente ───────────
-- SEFAZ exige sequência SEM GAP nem duplicata por (CNPJ, serie, ambiente).
-- Se pular ou duplicar, precisa fazer INUTILIZAÇÃO formal ou trava a
-- emissão futura. Este contador é usado com SELECT ... FOR UPDATE no
-- service pra reservar o número ANTES de tentar emitir (elimina race
-- entre pedidos simultâneos).
CREATE TABLE contador_numero_nfce (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    cnpj                      VARCHAR(20) NOT NULL,
    serie                     INT NOT NULL DEFAULT 1,
    ambiente                  INT NOT NULL,
    proximo_numero            BIGINT NOT NULL DEFAULT 1,
    atualizado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_contador UNIQUE (cnpj, serie, ambiente)
);

-- ── Notas fiscais emitidas ────────────────────────────────────────────
CREATE TABLE nota_fiscal_emitida (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id            BIGINT NOT NULL,
    pedido_id                 BIGINT,
    cnpj                      VARCHAR(20) NOT NULL,
    -- Modelo 65 = NFC-e, 55 = NF-e
    modelo                    INT NOT NULL DEFAULT 65,
    serie                     INT NOT NULL,
    numero                    BIGINT NOT NULL,
    ambiente                  INT NOT NULL,
    -- Chave de acesso NFC-e (44 dígitos). Preenchida ao gerar o XML.
    chave_acesso              VARCHAR(50),
    -- Estado da nota:
    --   PENDENTE          -> criada, ainda não enviada
    --   ENVIANDO          -> em transmissão pra SEFAZ
    --   AUTORIZADA        -> SEFAZ aceitou (100)
    --   REJEITADA         -> SEFAZ rejeitou (cStat != 100), motivo em `sefaz_motivo`
    --   DENEGADA          -> SEFAZ denegou (110) — CNPJ com pendência
    --   CANCELADA         -> cancelada dentro do prazo (30min NFC-e)
    --   INUTILIZADA       -> numeração inutilizada
    --   CONTINGENCIA_EPEC -> emitida em contingência offline (retransmitir depois)
    status                    VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
    sefaz_cstat               VARCHAR(10),
    sefaz_motivo              VARCHAR(500),
    protocolo                 VARCHAR(40),
    valor_total               DECIMAL(12,2) NOT NULL,
    -- Referências pro cofre de armazenamento (Cloudflare R2 / disco local em dev).
    xml_url                   VARCHAR(500),
    danfe_url                 VARCHAR(500),
    qrcode_url_consulta       VARCHAR(500),
    -- Retentativa (contingência / erro transitório).
    tentativas                INT NOT NULL DEFAULT 0,
    proxima_tentativa_em      DATETIME,
    emitida_em                DATETIME,
    criado_em                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_nota_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    INDEX idx_nota_restaurante_data (restaurante_id, criado_em DESC),
    INDEX idx_nota_status (status),
    INDEX idx_nota_chave (chave_acesso),
    INDEX idx_nota_pedido (pedido_id),
    -- (cnpj, serie, numero, ambiente) tem que ser único — evita emitir 2× o
    -- mesmo número por race, mesmo se contador falhar.
    CONSTRAINT uq_nota_numero UNIQUE (cnpj, serie, numero, ambiente, modelo)
);

-- ── Log de auditoria fiscal (APPEND-ONLY) ─────────────────────────────
-- Cada uso do certificado, cada emissão, cancelamento, mudança de config
-- vira uma linha aqui. NUNCA UPDATE nem DELETE — só INSERT.
-- Usado pra investigar incidentes ("quem trocou o cert da loja X?").
CREATE TABLE log_auditoria_fiscal (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id            BIGINT,
    cnpj                      VARCHAR(20),
    usuario_email             VARCHAR(255),
    operacao                  VARCHAR(60) NOT NULL,
    detalhes_json             TEXT,
    ip_origem                 VARCHAR(45),
    resultado                 VARCHAR(20) NOT NULL,
    criado_em                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_aud_restaurante_data (restaurante_id, criado_em DESC),
    INDEX idx_aud_operacao (operacao, criado_em DESC),
    INDEX idx_aud_cnpj (cnpj)
);
