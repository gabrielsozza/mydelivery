package com.mydelivery.fiscal.model;

import java.time.LocalDateTime;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.*;
import lombok.*;

/**
 * Config fiscal por CNPJ (loja) — dados que o CONTADOR do dono preenche.
 * Regime tributário, IE, CSC, UF, ambiente SEFAZ (produção vs homologação).
 *
 * <p>Emissão só é habilitada ({@code emissaoAtiva=true}) quando TODA a config
 * estiver preenchida e validada. Isso protege o dono de emitir nota com
 * config errada e ter que cancelar/inutilizar.
 */
@Entity
@Table(name = "perfil_fiscal_restaurante")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerfilFiscalRestaurante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false, unique = true)
    private Restaurante restaurante;

    @Column(nullable = false, length = 20)
    private String cnpj;

    @Column(name = "razao_social")
    private String razaoSocial;

    @Column(name = "nome_fantasia")
    private String nomeFantasia;

    @Column(name = "inscricao_estadual", length = 30)
    private String inscricaoEstadual;

    @Column(name = "inscricao_municipal", length = 30)
    private String inscricaoMunicipal;

    /** 1 = Simples Nacional, 2 = SN excesso sublimite, 3 = Regime Normal. */
    @Column(name = "regime_tributario", nullable = false)
    @Builder.Default
    private Integer regimeTributario = 1;

    /** 1 = Produção, 2 = Homologação (SEFAZ de teste). Novo cadastro entra em 2. */
    @Column(name = "ambiente_sefaz", nullable = false)
    @Builder.Default
    private Integer ambienteSefaz = 2;

    @Column(nullable = false, length = 2)
    private String uf;

    @Column(name = "municipio_codigo_ibge", length = 10)
    private String municipioCodigoIbge;

    @Column(name = "endereco_logradouro")
    private String enderecoLogradouro;

    @Column(name = "endereco_numero", length = 20)
    private String enderecoNumero;

    @Column(name = "endereco_bairro", length = 120)
    private String enderecoBairro;

    @Column(name = "endereco_cep", length = 10)
    private String enderecoCep;

    @Column(name = "endereco_complemento", length = 120)
    private String enderecoComplemento;

    /** CSC ID — identificador do CSC (o "número" do CSC, ex "000001"). */
    @Column(name = "csc_id", length = 10)
    private String cscId;

    /** CSC criptografado (é chave de acesso pra NFC-e, tratamos igual ao cert). */
    @Column(name = "csc_ciphertext", length = 1024)
    private byte[] cscCiphertext;

    @Column(name = "csc_iv", length = 16)
    private byte[] cscIv;

    @Column(name = "csc_tag", length = 16)
    private byte[] cscTag;

    /** Emissão só liberada quando toda a config estiver certa. Default false. */
    @Column(name = "emissao_ativa", nullable = false)
    @Builder.Default
    private Boolean emissaoAtiva = false;

    /**
     * Manifesto do destinatário habilitado — permite ao dono assinar/aceitar
     * NF-e recebidas de fornecedores (compra de insumos). Opcional.
     * Adicionado via Hibernate ddl-auto (Railway MySQL não aceita ALTER ADD IF NOT EXISTS).
     */
    @Column(name = "manifesto_habilitado", nullable = false)
    @Builder.Default
    private Boolean manifestoHabilitado = false;

    /** Texto livre pra imprimir no rodapé do cupom (ex: "Volte sempre"). */
    @Column(name = "mensagem_rodape", length = 500)
    private String mensagemRodape;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
