package com.mydelivery.dto.pedido;

import com.mydelivery.model.Pedido;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AtualizarStatusRequest {

    @NotNull(message = "Status é obrigatório")
    private Pedido.Status status;

    /**
     * Código do motivo de cancelamento — obrigatório APENAS quando
     * status = CANCELADO E o pedido veio do iFood. O código precisa vir
     * da lista devolvida por GET /pedidos/{id}/motivos-cancelamento
     * (que o painel consulta antes de mostrar o modal de cancelamento).
     * Sem esse campo, cancelar pedido do iFood é rejeitado no service.
     */
    private String motivoCancelamentoCodigo;

    /**
     * Texto livre do motivo (aparece no app do cliente iFood).
     * Se vazio, usamos o "description" do motivo escolhido.
     */
    private String motivoCancelamentoTexto;
}