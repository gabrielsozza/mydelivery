package com.mydelivery.fiscal.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mydelivery.model.Produto;

import jakarta.persistence.*;
import lombok.*;

/**
 * Config fiscal por produto — NCM, CFOP, CST/CSOSN, origem, alíquotas.
 * Preenchido pelo CONTADOR do dono. Sem isso, o produto não pode virar
 * item de NFC-e (SEFAZ rejeita).
 */
@Entity
@Table(name = "perfil_fiscal_produto")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerfilFiscalProduto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "produto_id", nullable = false, unique = true)
    private Produto produto;

    /** NCM 8 dígitos. Default 21069090 (preparações alimentícias) — contador confirma. */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String ncm = "21069090";

    /** CFOP 4 dígitos. Default 5102 (venda dentro do estado). */
    @Column(nullable = false, length = 4)
    @Builder.Default
    private String cfop = "5102";

    /** CST — usado pelo Regime Normal (3). */
    @Column(length = 4)
    private String cst;

    /** CSOSN — usado pelo Simples Nacional (1 e 2). Default 102 (sem permissão de crédito). */
    @Column(length = 4)
    @Builder.Default
    private String csosn = "102";

    /** 0 = Nacional (padrão). Ver tabela de Origem da Mercadoria. */
    @Column(nullable = false)
    @Builder.Default
    private Integer origem = 0;

    @Column(name = "unidade_comercial", nullable = false, length = 6)
    @Builder.Default
    private String unidadeComercial = "UN";

    @Column(name = "aliquota_icms", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal aliquotaIcms = BigDecimal.ZERO;

    @Column(name = "aliquota_pis", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal aliquotaPis = BigDecimal.ZERO;

    @Column(name = "aliquota_cofins", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal aliquotaCofins = BigDecimal.ZERO;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
