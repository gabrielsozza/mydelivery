package com.mydelivery.fiscal.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.*;
import lombok.*;

/**
 * NF-e RECEBIDA de fornecedor (compra de insumos, produtos revenda, etc).
 * Upload manual do dono/contador — não vem da SEFAZ automaticamente
 * (isso exigiria manifesto do destinatário via NFDistribuicaoDFe, que fica
 * pra evolução futura). O XML fica guardado inteiro na coluna pra o
 * contador reimportar no sistema fiscal dele no fechamento mensal.
 */
@Entity
@Table(name = "nota_fiscal_entrada",
       uniqueConstraints = @UniqueConstraint(name = "uk_nfe_entrada_chave", columnNames = "chave_acesso"),
       indexes = {
           @Index(name = "idx_nfe_entrada_rest_data", columnList = "restaurante_id, data_emissao"),
           @Index(name = "idx_nfe_entrada_cnpj_emit", columnList = "cnpj_emitente")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotaFiscalEntrada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    /** CNPJ do FORNECEDOR (quem emitiu a NF-e). 14 dígitos, sem máscara. */
    @Column(name = "cnpj_emitente", nullable = false, length = 20)
    private String cnpjEmitente;

    /** Nome/razão social do fornecedor (extraído do XML pra listagem). */
    @Column(name = "nome_emitente", length = 255)
    private String nomeEmitente;

    /** Chave de acesso 44 dígitos — chave única no banco. */
    @Column(name = "chave_acesso", nullable = false, length = 50)
    private String chaveAcesso;

    /** Número da NF-e do fornecedor (pra referência). */
    @Column(length = 20)
    private String numero;

    /** Modelo (55 = NF-e, 65 = NFC-e — raramente entra 65 como entrada). */
    @Column(length = 2)
    private String modelo;

    /** Data que o fornecedor emitiu a nota (não a data do upload). */
    @Column(name = "data_emissao")
    private LocalDateTime dataEmissao;

    @Column(name = "valor_total", precision = 12, scale = 2)
    private BigDecimal valorTotal;

    /** XML completo — usado no fechamento mensal pro contador. */
    @Lob
    @Column(name = "xml_conteudo", nullable = false, columnDefinition = "LONGTEXT")
    private String xmlConteudo;

    /** Quem subiu o XML (email do dono ou membro autorizado). */
    @Column(name = "usuario_upload", length = 255)
    private String usuarioUpload;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;
}
