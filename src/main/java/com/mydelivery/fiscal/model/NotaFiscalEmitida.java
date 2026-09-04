package com.mydelivery.fiscal.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.*;
import lombok.*;

/**
 * Registro de UMA nota fiscal emitida (NFC-e/NF-e).
 *
 * <p>Estados possíveis:
 * <ul>
 *   <li><b>PENDENTE</b>          — criada, ainda não enviada</li>
 *   <li><b>ENVIANDO</b>          — em transmissão pra SEFAZ</li>
 *   <li><b>AUTORIZADA</b>        — SEFAZ aceitou (cStat=100)</li>
 *   <li><b>REJEITADA</b>         — SEFAZ rejeitou (cStat != 100), motivo em {@code sefazMotivo}</li>
 *   <li><b>DENEGADA</b>          — SEFAZ denegou (cStat=110) — CNPJ com pendência</li>
 *   <li><b>CANCELADA</b>         — cancelada dentro do prazo</li>
 *   <li><b>INUTILIZADA</b>       — numeração inutilizada</li>
 *   <li><b>CONTINGENCIA_EPEC</b> — emitida em contingência offline (retransmitir depois)</li>
 * </ul>
 */
@Entity
@Table(name = "nota_fiscal_emitida",
       uniqueConstraints = @UniqueConstraint(name = "uq_nota_numero",
           columnNames = {"cnpj","serie","numero","ambiente","modelo"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotaFiscalEmitida {

    public enum Status {
        PENDENTE, ENVIANDO, AUTORIZADA, REJEITADA, DENEGADA,
        CANCELADA, INUTILIZADA, CONTINGENCIA_EPEC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(nullable = false, length = 20)
    private String cnpj;

    /** 65 = NFC-e, 55 = NF-e. */
    @Column(nullable = false)
    @Builder.Default
    private Integer modelo = 65;

    @Column(nullable = false)
    private Integer serie;

    @Column(nullable = false)
    private Long numero;

    /** 1 = produção, 2 = homologação. */
    @Column(nullable = false)
    private Integer ambiente;

    /** Chave de acesso 44 dígitos (preenchida ao gerar o XML). */
    @Column(name = "chave_acesso", length = 50)
    private String chaveAcesso;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.PENDENTE;

    @Column(name = "sefaz_cstat", length = 40)
    private String sefazCstat;

    @Column(name = "sefaz_motivo", length = 500)
    private String sefazMotivo;

    // Trunca antes de gravar — a coluna do MySQL Railway tem length fixa e
    // o Hibernate ddl-auto pode não ter aumentado a mais nova em cima da
    // antiga (Railway MySQL não aceita ALTER MODIFY IF NOT EXISTS). Sem isso,
    // um cStat/motivo maior que o campo lança "Data too long for column".
    public void setSefazCstat(String v) {
        // Trunca em 10 pra caber mesmo se o Hibernate ddl-auto não tiver
        // expandido a coluna original (length=10) pro novo tamanho 40. Perde
        // um pouco de contexto (cSTat "ERRO_MONTAGEM" → "ERRO_MONTA") mas o
        // motivo completo fica em sefazMotivo (500 chars).
        this.sefazCstat = v == null ? null : (v.length() > 10 ? v.substring(0, 10) : v);
    }
    public void setSefazMotivo(String v) {
        this.sefazMotivo = v == null ? null : (v.length() > 500 ? v.substring(0, 500) : v);
    }

    @Column(length = 40)
    private String protocolo;

    @Column(name = "valor_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal valorTotal;

    @Column(name = "xml_url", length = 500)
    private String xmlUrl;

    @Column(name = "danfe_url", length = 500)
    private String danfeUrl;

    @Column(name = "qrcode_url_consulta", length = 500)
    private String qrcodeUrlConsulta;

    @Column(nullable = false)
    @Builder.Default
    private Integer tentativas = 0;

    @Column(name = "proxima_tentativa_em")
    private LocalDateTime proximaTentativaEm;

    @Column(name = "emitida_em")
    private LocalDateTime emitidaEm;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
