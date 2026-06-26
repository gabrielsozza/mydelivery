package com.mydelivery.dto.entregador;

import com.mydelivery.model.Pedido;
import lombok.Data;

/**
 * Status que o entregador pode setar do app mobile.
 * Validação adicional no service: só aceita SAIU_ENTREGA e ENTREGUE.
 * Transições inválidas (ex: voltar de ENTREGUE pra EM_PREPARO) são bloqueadas.
 */
@Data
public class AtualizarStatusEntregadorPedidoRequest {
    private Pedido.Status status;
}
