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

    /**
     * Coordenadas geográficas do restaurante — usadas SÓ quando
     * {@link #modoTaxa} = RAIO. Dono ajusta no mapa Leaflet do painel
     * (arrasta o pin). Se null, sistema tenta geocodificar via CEP no
     * primeiro save do painel; falha silenciosa não bloqueia salvamento.
     */
    @Column(name = "endereco_latitude", precision = 10, scale = 7)
    private java.math.BigDecimal enderecoLatitude;

    @Column(name = "endereco_longitude", precision = 10, scale = 7)
    private java.math.BigDecimal enderecoLongitude;

    /**
     * Modo de cálculo da taxa de entrega:
     * <ul>
     *   <li>{@code BAIRRO} (default, retrocompat) — cliente escolhe bairro,
     *       taxa vem da tabela {@code taxas_bairro}.</li>
     *   <li>{@code RAIO} — cliente digita endereço/CEP, sistema geocodifica
     *       e aplica taxa da zona circular correspondente
     *       (ver {@code zonas_entrega}).</li>
     * </ul>
     */
    @Builder.Default
    @Column(name = "modo_taxa", length = 10, nullable = false)
    private String modoTaxa = "BAIRRO";

    // ── Operação ──
    @Builder.Default
    private Boolean aberto = false;

    /** Tempo mínimo de entrega (em minutos). Mostrado como "30 - 50 min" se max definido. */
    private Integer tempoEntrega;
    /** Tempo máximo de entrega — opcional. Se preenchido, vira range "min - max". */
    @Column(name = "tempo_entrega_max")
    private Integer tempoEntregaMax;

    @Column(precision = 10, scale = 2)
    private BigDecimal taxaEntrega;

    @Column(precision = 10, scale = 2)
    private BigDecimal pedidoMinimo;

    /**
     * Frete grátis a partir de X. Se subtotal do pedido (sem taxa) >= este
     * valor, o checkout zera a taxa de entrega automaticamente.
     *
     * Null OU zero = feature desligada (comportamento original: sempre cobra
     * a taxa configurada). Guardar como BigDecimal 10,2 mantém coerência com
     * pedidoMinimo/taxaEntrega. Frontend do cardápio consome via endpoint
     * público de cardápio e mostra badge "Frete grátis acima de R$ X" +
     * barra de progresso durante compra ("Faltam R$ Y pra frete grátis").
     */
    @Column(name = "frete_gratis_apartir_de", precision = 10, scale = 2)
    private BigDecimal freteGratisApartirDe;

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

    /** Se true, cliente que escolher PIX no checkout recebe a chave PIX do
     *  restaurante e é orientado a pagar antes e mandar comprovante no WhatsApp.
     *  Só faz sentido com "pix" em {@link #pagamentos}. */
    @Builder.Default
    @Column(name = "exigir_pix_antecipado")
    private Boolean exigirPixAntecipado = false;

    /** Chave PIX (CPF/CNPJ/email/telefone/aleatória) usada quando
     *  {@link #exigirPixAntecipado} = true. */
    @Column(name = "chave_pix_antecipado", length = 200)
    private String chavePixAntecipado;

    /** Tipo da chave PIX — "CPF" | "CNPJ" | "EMAIL" | "TELEFONE" | "ALEATORIA".
     *  Mostrado ao cliente final junto da chave pra evitar pagamento errado. */
    @Column(name = "tipo_chave_pix_antecipado", length = 20)
    private String tipoChavePixAntecipado;

    // ── Mesas ──
    @Builder.Default
    private Integer qtdMesas = 0;

    // ── Aceitar pedidos automaticamente ──
    // Quando true, pedidos criados pelo cardápio público entram direto como CONFIRMADO
    // (pulando PENDENTE). Pedidos agendados continuam em PENDENTE pra revisão.
    @Builder.Default
    private Boolean aceitarPedidosAutomaticamente = false;

    /**
     * Se true, após o cliente finalizar o pedido no cardápio digital o
     * frontend mostra um botão "Confirmar via WhatsApp" que abre conversa
     * com o restaurante já com a mensagem pré-preenchida. O bot WhatsApp
     * (Evolution API) detecta a chegada e responde automaticamente
     * confirmando que o pedido foi recebido. Útil pra dar segurança
     * adicional pro cliente em pedidos de delivery.
     */
    @Builder.Default
    @Column(name = "confirmacao_whatsapp_ativa")
    private Boolean confirmacaoWhatsappAtiva = false;

    /**
     * Quando true (default), bot WhatsApp envia 1 mensagem proativa pro
     * cliente DEPOIS de criar o pedido pelo cardápio, com link único de
     * acompanhamento em tempo real (mydeliveryfood.com.br/acompanhar/{id}).
     *
     * Risco shadow ban: BAIXO — é 1 msg por pedido, em conversa que o
     * cliente iniciou ao fazer pedido. Bot manda pra clientes que
     * forneceram o telefone no cardápio.
     *
     * Salvaguardas implementadas: pool de 15+ variações de texto, delay
     * aleatório 15-90s antes de enviar, só horário comercial (8h-23h),
     * throttle 5min entre msgs pro mesmo número, fail-safe (nunca quebra
     * criação do pedido).
     *
     * Dono pode desativar via toggle no painel se notar problema.
     */
    @Builder.Default
    @Column(name = "notif_link_acomp_whatsapp")
    private Boolean notificarLinkAcompanhamentoWhatsapp = true;

    // ── Integração com balança de pesagem (venda por peso) ──
    /**
     * Habilita integração com balança serial no módulo Balcão.
     * Quando true, o Balcão carrega o módulo Web Serial (js/balanca/*),
     * mostra widget de status no topo e abre modal de pesagem automática
     * ao clicar em produtos com {@code precoVitrine=true}.
     *
     * Toggle no {@code configuracoes.html}. Default false: 100% dos restaurantes
     * atuais continuam com o fluxo antigo (porções via complementos), sem
     * carga extra de script na página.
     *
     * A conexão física com a balança é por-máquina (Chrome guarda a permissão
     * de porta serial por origin), então esse campo só liga a FEATURE — cada
     * PC do balcão escolhe a própria porta e o protocolo (Toledo, Filizola,
     * Urano, Elgin) uma vez e salva em localStorage.
     */
    @Builder.Default
    @Column(name = "balanca_ativa")
    private Boolean balancaAtiva = false;

    // ── Integração iFood ──
    /**
     * UUID da loja no iFood (merchantId). Preenchido quando o dono autoriza
     * o aplicativo MyDelivery dentro do Gestor de Pedidos do iFood.
     * Null = restaurante ainda não integrou com iFood.
     * Usado pelo IfoodPollingJob pra saber quais merchants pollar.
     */
    @Column(name = "ifood_merchant_id", length = 60)
    private String ifoodMerchantId;

    /** Sinaliza se a integração está ativa (dono pode pausar sem desfazer
     *  a autorização). Quando false, IfoodPollingJob ignora esse restaurante. */
    @Builder.Default
    @Column(name = "ifood_integracao_ativa")
    private Boolean ifoodIntegracaoAtiva = false;

    /** Última vez que o polling pegou eventos com sucesso pra esse merchant.
     *  Usado no painel pra mostrar "Sincronizado há X minutos" e detectar
     *  integração que parou de funcionar. */
    @Column(name = "ifood_ultimo_polling_em")
    private LocalDateTime ifoodUltimoPollingEm;

    // ── Horários de funcionamento ──
    // JSON serializado: { "seg": {"aberto":true,"abertura":"11:00","fechamento":"22:00"}, ... }
    // Guardado como TEXT pra evitar tabela separada — o front já trata como objeto.
    // Hibernate ddl-auto=update adiciona a coluna sem afetar dados existentes.
    @Column(name = "horarios_json", columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String horariosJson;

    // ── Automação de horário ──
    /** Se true: scheduler abre a loja automaticamente quando chega o horário cadastrado. */
    @Builder.Default
    @Column(name = "abertura_automatica")
    private Boolean aberturaAutomatica = false;

    /** Se true: para de aceitar pedidos N minutos ANTES do fechamento (mas loja permanece visualmente aberta). */
    @Builder.Default
    @Column(name = "parar_pedidos_antes_fechamento")
    private Boolean pararPedidosAntesFechamento = false;

    /** Quantos minutos antes do fechamento bloquear novos pedidos. */
    @Builder.Default
    @Column(name = "minutos_antes_fechamento")
    private Integer minutosAntesFechamento = 10;

    /**
     * Campo calculado em runtime (não persiste): "estou aceitando pedidos agora?".
     * O ConfiguracaoController.getPublico seta antes de devolver pro front,
     * usando HorarioLojaService. Se null → front trata como true (retrocompat).
     */
    @jakarta.persistence.Transient
    private Boolean aceitandoPedidos;

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

    // ── Programa de Afiliados ──
    /**
     * Código do afiliado que trouxe esse restaurante (8 chars).
     * Vazio se o restaurante chegou orgânico. Imutável após cadastro.
     * Usado pra disparar webhook pra myafiliados-api a cada mudança de status.
     */
    @Column(name = "afiliado_codigo", length = 16)
    private String afiliadoCodigo;

    /**
     * SNAPSHOT do afiliado no momento do cadastro. Imutável.
     *
     * Guardamos cópia dos dados aqui pra:
     *   (a) o painel admin conseguir mostrar "quem indicou" mesmo se o
     *       myafiliados-api estiver offline ou fora do ar;
     *   (b) preservar histórico se o afiliado for deletado no futuro
     *       (o restaurante ainda saberá por quem foi indicado);
     *   (c) não depender de round-trip HTTP toda vez que abrir os
     *       detalhes do restaurante no admin.
     *
     * REGRA CRÍTICA: esses campos JAMAIS devem ser sobrescritos após o
     * cadastro do restaurante. Nenhum endpoint público ou de restaurante
     * pode alterá-los. Só admin via SQL manual em caso de correção
     * humana comprovada.
     */
    @Column(name = "afiliado_id_snap")
    private Long afiliadoIdSnap;

    @Column(name = "afiliado_nome_snap", length = 200)
    private String afiliadoNomeSnap;

    @Column(name = "afiliado_email_snap", length = 200)
    private String afiliadoEmailSnap;

    @Column(name = "afiliado_comissao_snap", precision = 5, scale = 2)
    private java.math.BigDecimal afiliadoComissaoSnap;

    @Column(name = "afiliado_vinculado_em")
    private LocalDateTime afiliadoVinculadoEm;

    // ── Precificação personalizada ──
    /**
     * Quando preenchido, sobrescreve o valor do plano lido da tabela `planos`.
     * Permite cobrar valores diferentes pra restaurantes diferentes (ex: novos
     * pagam R$75, antigos seguem R$50). Null = usa valor padrão do PlanoCatalogo.
     */
    @Column(name = "valor_mensal_personalizado", precision = 10, scale = 2)
    private java.math.BigDecimal valorMensalPersonalizado;

    @Column(name = "valor_semestral_personalizado", precision = 10, scale = 2)
    private java.math.BigDecimal valorSemestralPersonalizado;

    @Column(name = "valor_anual_personalizado", precision = 10, scale = 2)
    private java.math.BigDecimal valorAnualPersonalizado;

    @CreationTimestamp
    private LocalDateTime criadoEm;

    public enum Status {
        ATIVO, BLOQUEADO, TRIAL, CANCELADO
    }
}
