package com.mydelivery.dto.entregador;

import com.mydelivery.model.Entregador;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class EntregadorResponse {
    private Long id;
    private String nome;
    private String telefone;
    private String veiculo;
    private String placa;
    private Entregador.Status status;
    private Boolean ativo;
    private LocalDateTime criadoEm;
    /**
     * PIN em texto plano. Devolvido só pra painel admin do dono — frontend
     * mostra dentro de um card com botão "Mostrar PIN" oculto por default.
     */
    private String pin;
    /** True se entregador está logado no app mobile agora. */
    private Boolean online;
    /** Última autenticação via PIN. */
    private LocalDateTime ultimoLoginEm;
    /** Quantos pedidos ativos (não entregues/cancelados) o entregador tem agora. */
    private Integer pedidosEmAndamento;
}
