CREATE TABLE campanha_controles (
 restaurante_id BIGINT PRIMARY KEY,
 intervalo_segundos INT NOT NULL DEFAULT 90,
 limite_hora INT NOT NULL DEFAULT 20,
 limite_dia INT NOT NULL DEFAULT 100,
 intervalo_dias_cliente INT NOT NULL DEFAULT 7,
 limite_30_dias INT NOT NULL DEFAULT 2,
 atribuicao_dias INT NOT NULL DEFAULT 7,
 inicio VARCHAR(5) NOT NULL DEFAULT '09:00',
 fim VARCHAR(5) NOT NULL DEFAULT '20:00',
 proximo_envio DATETIME(6), bloqueado BOOLEAN NOT NULL DEFAULT FALSE,
 recibos_disponiveis BOOLEAN NOT NULL DEFAULT FALSE, diagnostico VARCHAR(300), diagnostico_em DATETIME(6), canal_restrito BOOLEAN NOT NULL DEFAULT FALSE, motivo VARCHAR(300), FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE campanha_contatos (
 restaurante_id BIGINT NOT NULL, telefone VARCHAR(20) NOT NULL,
 consentimento_em DATETIME(6), evidencia VARCHAR(500), registrado_por VARCHAR(180),
 restrito BOOLEAN NOT NULL DEFAULT FALSE, ultima_resposta DATETIME(6),
 PRIMARY KEY(restaurante_id,telefone), FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE campanhas_crm (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL,
 chave VARCHAR(36) NOT NULL, texto VARCHAR(700) NOT NULL, imagem_url VARCHAR(1500),
 segmento VARCHAR(20) NOT NULL, inativo_dias INT NOT NULL DEFAULT 30,
 produto_id BIGINT, categoria_id BIGINT,
 status VARCHAR(20) NOT NULL DEFAULT 'RASCUNHO', motivo VARCHAR(300),
 selecionados INT NOT NULL DEFAULT 0, elegiveis INT NOT NULL DEFAULT 0,
 exclusoes TEXT, agendada_em DATETIME(6), criada_em DATETIME(6) NOT NULL,
 iniciada_em DATETIME(6), concluida_em DATETIME(6),
 instance_name VARCHAR(80), instance_phone VARCHAR(20), atribuicao_dias INT NOT NULL DEFAULT 7,
 UNIQUE KEY uq_campanha_chave(restaurante_id,chave),
 INDEX ix_campanha_estado(status,agendada_em),
 FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE campanha_destinos (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, campanha_id BIGINT NOT NULL, restaurante_id BIGINT NOT NULL,
 telefone VARCHAR(20) NOT NULL, nome VARCHAR(120), pedidos INT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE', motivo VARCHAR(300),
 tentativas INT NOT NULL DEFAULT 0, tentativa_em DATETIME(6), proxima_tentativa DATETIME(6),
 aceito_em DATETIME(6), entregue_em DATETIME(6), resposta_em DATETIME(6),
 opt_out_em DATETIME(6), revisado_em DATETIME(6), negativa_em DATETIME(6), provider_id VARCHAR(180), provider_internal_id VARCHAR(180),
 UNIQUE KEY uq_campanha_destino(campanha_id,telefone),
 INDEX ix_campanha_fila(restaurante_id,status,proxima_tentativa),
 INDEX ix_campanha_frequencia(restaurante_id,telefone,aceito_em),
 FOREIGN KEY(campanha_id) REFERENCES campanhas_crm(id), FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE campanha_eventos (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL,
 chave VARCHAR(180) NOT NULL, tipo VARCHAR(40) NOT NULL, detalhe VARCHAR(500),
 criado_em DATETIME(6) NOT NULL, UNIQUE KEY uq_campanha_evento(restaurante_id,chave),
 FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE campanha_atribuicoes (
 pedido_id BIGINT PRIMARY KEY, campanha_id BIGINT NOT NULL, destino_id BIGINT NOT NULL,
 restaurante_id BIGINT NOT NULL, criado_em DATETIME(6) NOT NULL,
 FOREIGN KEY(pedido_id) REFERENCES pedidos(id), FOREIGN KEY(campanha_id) REFERENCES campanhas_crm(id),
 FOREIGN KEY(destino_id) REFERENCES campanha_destinos(id), FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
-- Filas antigas não possuem prova de consentimento nem reserva durável.
-- Preserva o histórico e exige nova revisão; não altera conexão ou atendimento do bot.
UPDATE promo_campanhas SET status='PAUSADA' WHERE status='ENVIANDO';
