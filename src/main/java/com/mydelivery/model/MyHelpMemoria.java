package com.mydelivery.model;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Memória do myHelp — camada de conhecimento persistente que fica EM VOLTA do
 * interpretador (não retreina nada). Guarda o que o myHelp aprendeu sobre como
 * cada restaurante fala, pra melhorar as próximas interpretações.
 *
 * <p>Multi-tenant por design: {@code restauranteId} nulo = memória GLOBAL (vale
 * pra todas as lojas, ex.: cortesias); preenchido = memória só daquela loja. As
 * leituras sempre filtram por {@code loja OR global} — a preferência de uma loja
 * NUNCA vaza pra outra.
 *
 * <p>A memória é usada como CONTEXTO AUXILIAR na resolução (candidato extra no
 * match), nunca como substituição cega de texto — por isso "Coca-Cola 2L"
 * continua sendo respeitado mesmo com um alias "coca → Coca-Cola 350ml".
 *
 * <p>Tabela nova → o Hibernate ddl-auto=update cria com todas as colunas (sem o
 * problema de ALTER ADD COLUMN em tabela existente do MySQL Railway).
 */
@Entity
@Table(name = "myhelp_memoria", indexes = {
        @Index(name = "ix_mhmem_busca", columnList = "restaurante_id,contexto,ativa"),
        @Index(name = "ix_mhmem_chave", columnList = "restaurante_id,contexto,chave_norm")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MyHelpMemoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dono da memória. NULL = global (todas as lojas). */
    @Column(name = "restaurante_id")
    private Long restauranteId;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Tipo tipo;

    /** Em que situação essa memória se aplica. Ex.: "product_ref", "bairro_ref". */
    @Column(length = 40)
    private String contexto;

    /** Gatilho normalizado (minúsculo, sem acento). Ex.: "coca". */
    @Column(name = "chave_norm", length = 160)
    private String chaveNorm;

    /** O que o gatilho significa pra essa loja. Ex.: "Coca-Cola 350ml". */
    @Column(length = 255)
    private String valor;

    /** 0.0–1.0. Sobe quando o dono repete/confirma, desce quando contradiz. */
    @Builder.Default
    private Double confianca = 0.5;

    /** Quantas vezes essa memória já ajudou (reforço). */
    @Builder.Default
    private Integer usos = 1;

    /** Liga/desliga sem apagar — pronto pra tela futura "Aprendizados do myHelp". */
    @Builder.Default
    private Boolean ativa = true;

    private Instant criadoEm;
    private Instant atualizadoEm;

    public enum Tipo {
        /** Regra que vale pra todas as lojas (restauranteId = null). */
        GLOBAL_RULE,
        /** "Quando eu falar X, é Y" — apelido/sinônimo daquela loja. */
        ALIAS,
        /** Preferência geral da loja (reservado pra evoluções futuras). */
        PREFERENCIA
    }

    @PrePersist
    void aoCriar() {
        Instant agora = Instant.now();
        if (criadoEm == null) criadoEm = agora;
        atualizadoEm = agora;
    }

    @PreUpdate
    void aoAtualizar() {
        atualizadoEm = Instant.now();
    }
}
