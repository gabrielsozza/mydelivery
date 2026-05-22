package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "restaurantes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(unique = true)
    private String cnpj;

    /**
     * CPF: opção pra restaurantes menores que não têm CNPJ.
     * O front exige UM ou OUTRO (não os dois). Único pra evitar duplicidade.
     */
    @Column(unique = true, length = 14)
    private String cpf;

    // ── Aparência ──
    private String logoUrl;
    private String capaUrl;
    private String corPrimaria;
    // Tema do cardápio público para o cliente final: "claro" ou "escuro"
    @Builder.Default
    @Column(name = "tema_cardapio", length = 10)
    private String temaCardapio = "claro";

    // ── Info pública ──
    private String descricao;
    private String telefone;

    /**
     * Endereço — antes era uma única string "endereco". Agora detalhado:
     * rua / numero / bairro / cep + cidade / estado. O campo `endereco`
     * antigo é mantido pra retrocompat de cadastros legados.
     */
    private String endereco;
    private String rua;
    @Column(length = 20)
    private String numero;
    private String bairro;
    @Column(length = 10)
    private String cep;
    private String cidade;
    private String estado;

    // ── Operação ──
    @Builder.Default
    private Boolean aberto = false;

    private Integer tempoEntrega;

    @Column(precision = 10, scale = 2)
    private BigDecimal taxaEntrega;

    @Column(precision = 10, scale = 2)
    private BigDecimal pedidoMinimo;

    // ── Modos de atendimento ──
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurante_modos", joinColumns = @JoinColumn(name = "restaurante_id"))
    @Column(name = "modo")
    @Builder.Default
    private List<String> modos = List.of("delivery", "retirada", "mesa");

    // ── Formas de pagamento ──
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurante_pagamentos", joinColumns = @JoinColumn(name = "restaurante_id"))
    @Column(name = "pagamento")
    @Builder.Default
    private List<String> pagamentos = List.of("pix", "credito", "debito", "dinheiro");

    // ── Mesas ──
    @Builder.Default
    private Integer qtdMesas = 0;

    // ── Aceitar pedidos automaticamente ──
    // Quando true, pedidos criados pelo cardápio público entram direto como CONFIRMADO
    // (pulando PENDENTE). Pedidos agendados continuam em PENDENTE pra revisão.
    @Builder.Default
    private Boolean aceitarPedidosAutomaticamente = false;

    // ── Horários de funcionamento ──
    // JSON serializado: { "seg": {"aberto":true,"abertura":"11:00","fechamento":"22:00"}, ... }
    // Guardado como TEXT pra evitar tabela separada — o front já trata como objeto.
    // Hibernate ddl-auto=update adiciona a coluna sem afetar dados existentes.
    @Column(name = "horarios_json", columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String horariosJson;

    /** Expõe como objeto JSON pro front (mais natural que string). */
    @com.fasterxml.jackson.annotation.JsonProperty("horarios")
    public Object getHorariosAsObject() {
        if (horariosJson == null || horariosJson.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(horariosJson, Object.class);
        } catch (Exception e) { return null; }
    }

    // ── Agendamento ──
    @Builder.Default
    private Boolean agendamentoAtivo = false;

    private Integer agendamentoAntecedencia;
    private Integer agendamentoIntervalo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurante_slots", joinColumns = @JoinColumn(name = "restaurante_id"))
    @Column(name = "slot")
    @Builder.Default                          // ← adicionado
    private List<String> agendamentoSlots = new ArrayList<>();  // ← adicionado

    /**
     * Regiões de entrega — cada item tem nome do bairro + taxa própria.
     * No cardápio público a taxa NÃO aparece de cara; só é revelada no checkout
     * quando o cliente informa o bairro (lookup via /publico/{slug}/bairros/{nome}/taxa).
     *
     * Antes era List<String> (só nome). Hibernate em ddl-auto=update adiciona a coluna
     * "taxa" na tabela restaurante_bairros existente — registros antigos ficam com taxa=null
     * até o dono reconfigurar.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurante_bairros", joinColumns = @JoinColumn(name = "restaurante_id"))
    @Builder.Default
    private List<BairroEntrega> bairrosAtendidos = new ArrayList<>();

    // ── Status / plano ──
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private LocalDateTime trialExpiraEm;
    private LocalDateTime bloqueadoEm;
    private String motivoBloqueio;

    @CreationTimestamp
    private LocalDateTime criadoEm;

    public enum Status {
        ATIVO, BLOQUEADO, TRIAL, CANCELADO
    }
}
