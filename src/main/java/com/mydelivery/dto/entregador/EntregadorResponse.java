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
}
