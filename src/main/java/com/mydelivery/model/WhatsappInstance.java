package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Instância WhatsApp na Evolution API — 1 por restaurante (multi-tenant).
 *
 * Nome da instância é determinístico (mydelivery-rest-{id}) — permite recuperar
 * a mesma sessão se o backend reiniciar. O instanceToken é gerado pela Evolution
 * no momento da criação e usado pra autenticar operações daquela instância
 * (envio de mensagem, etc.) sem expor a apiKey global.
 *
 * Self-hosting: Evolution roda em VPS (Hetzner) atrás de proxy residencial
 * sticky (Proxy-Seller) pra evitar bloqueio de IP de datacenter pelo WhatsApp.
 */
@Entity
@Table(name = "whatsapp_instances", indexes = {
        @Index(name = "idx_wa_restaurante", columnList = "restaurante_id", unique = true),
        @Index(name = "idx_wa_instance_name", columnList = "instance_name", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsappInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "restaurante_id", nullable = false, unique = true)
    private Restaurante restaurante;

    /** Nome único da instância na Evolution. Padrão: mydelivery-rest-{restauranteId}. */
    @Column(name = "instance_name", nullable = false, length = 80, unique = true)
    private String instanceName;

    /** Token específico da instância (devolvido pela Evolution em /instance/create). */
    @Column(name = "instance_token", length = 200)
    private String instanceToken;

    /** Número conectado no formato 55DDDNNNNNNNN. Preenchido após scan do QR. */
    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private Status status = Status.NOVA;

    /** QR Code em base64 (PNG), válido enquanto AGUARDANDO_QR. Limpo após conectar. */
    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    @Column(name = "qr_expira_em")
    private LocalDateTime qrExpiraEm;

    @Column(name = "conectado_em")
    private LocalDateTime conectadoEm;

    /**
     * BUG FIX Jul/2026: `conectadoEm` era resetado a cada CONNECTION_UPDATE
     * de sucesso — instância que caía e reconectava depois de dias entrava
     * em WARMUP eterno (< 48h desde conectadoEm), auto-reconexão bloqueada.
     *
     * `sessaoIniciadaEm` é setado UMA vez no primeiro CONNECTION_UPDATE
     * bem-sucedido após criação da instância e NUNCA é resetado em
     * reconexões subsequentes. Warmup passa a olhar este campo, não
     * conectadoEm. Conta veterana que cai e volta não é tratada como nova.
     *
     * Retrocompat: colunas existentes ficam com NULL. O código trata NULL
     * fallback pra conectadoEm (comportamento antigo, seguro).
     */
    @Column(name = "sessao_iniciada_em")
    private LocalDateTime sessaoIniciadaEm;

    /**
     * Kill-switch admin: se preenchido, warmup fica desativado até esta
     * data (ex: dono já usou o robô semanas em outro sistema — sabe que
     * não é conta nova). Interpretado no HealthJob e ReconnectJob.
     * NULL = usa cálculo normal por sessaoIniciadaEm.
     */
    @Column(name = "warmup_forcado_ate")
    private LocalDateTime warmupForcadoAte;

    /** Última vez que HeartbeatJob checou /instance/status. Diferente de
     *  ultimaMensagemRecebidaEm — este mede ping ATIVO do backend, aquele
     *  mede tráfego recebido do WhatsApp. */
    @Column(name = "ultimo_heartbeat_em")
    private LocalDateTime ultimoHeartbeatEm;

    /** Resultado da última checagem ativa. NULL = ainda não checado. */
    @Column(name = "ultimo_heartbeat_ok")
    private Boolean ultimoHeartbeatOk;

    /** Contador de heartbeats falhados consecutivos. Reseta a 0 em cada
     *  checagem OK. >= 3 → marca instância como INSTAVEL mesmo com Uazapi
     *  dizendo "connected" (shadow ban invisível). */
    @Column(name = "heartbeats_falhados_seguidos", nullable = false)
    @Builder.Default
    private Integer heartbeatsFalhadosSeguidos = 0;

    /** Msgs processadas no ciclo de conexão atual. Zerado ao reconectar,
     *  copiado pra whatsapp_desconexao_log.msgs_processadas_no_ciclo
     *  quando a instância cai. */
    @Column(name = "msgs_ciclo_atual", nullable = false)
    @Builder.Default
    private Integer msgsCicloAtual = 0;

    /** Heartbeat fraco: atualizado em CADA webhook que chega da Evolution
     *  (incluindo CONNECTION_UPDATE periódico que ela manda sozinha).
     *  Prova só que "Evolution → backend" está vivo, NÃO que o bot funciona.
     *  Pra "bot operacional" use ultimaMensagemClienteEm. */
    @Column(name = "ultima_msg_recebida_em")
    private LocalDateTime ultimaMensagemRecebidaEm;

    /** Heartbeat forte: atualizado SÓ quando chega MESSAGES_UPSERT real
     *  (cliente mandando msg). Esse é o sinal verdadeiro de que o bot está
     *  recebendo mensagens. Se ficar muito tempo nulo + sem msg de cliente,
     *  ainda não sabemos se é loja parada ou bot quebrado. */
    @Column(name = "ultima_msg_cliente_em")
    private LocalDateTime ultimaMensagemClienteEm;

    /** Heartbeat de envio: atualizado quando enviarMensagem retorna sucesso.
     *  Combinado com ultimaMensagemRecebidaEm permite detectar se o bot está
     *  respondendo. */
    @Column(name = "ultima_resp_enviada_em")
    private LocalDateTime ultimaRespostaEnviadaEm;

    /** Quantas tentativas de auto-reconexão foram feitas desde o último
     *  sucesso. Resetado quando recebe webhook ou envia resposta com sucesso. */
    @Builder.Default
    @Column(name = "tentativas_reconexao_seguidas", nullable = false)
    private Integer tentativasReconexaoSeguidas = 0;

    /** Última vez que o job de auto-reconexão tentou. Usado pra throttle. */
    @Column(name = "ultima_tentativa_reconexao_em")
    private LocalDateTime ultimaTentativaReconexaoEm;

    /**
     * TRUE = dono clicou "Desconectar" no painel (fluxo normal — NÃO gera alerta
     *        nem auto-reconexão).
     * FALSE = queda inesperada (webhook close OU heartbeat morreu) — aí sim
     *         registra incidente e tenta reconectar.
     *
     * Resetado pra false quando CONECTADA novamente.
     */
    @Column(name = "desconectado_manualmente", nullable = false)
    @Builder.Default
    private Boolean desconectadoManualmente = false;

    /** Última vez que a sessão caiu inesperadamente (webhook close).
     *  null se nunca caiu ou se está conectada. */
    @Column(name = "ultima_queda_em")
    private LocalDateTime ultimaQuedaEm;

    /**
     * Chave do pool de proxy residencial usado pela instância na criação.
     * Mapeia em {@code mydelivery.evolution.pools.<KEY>} pra distribuir N lojas
     * em vários IPs (reduz densidade por IP, divide banda mensal, isola
     * lojas em shadow ban num pool dedicado).
     *
     * Valores típicos: "A", "B", "C", "D". {@code null} = usa o proxy global
     * legado (fallback pra retrocompat com instâncias criadas antes desse campo).
     *
     * Atribuído ao criar instância. Pra mudar o pool de uma instância existente
     * é necessário deletar+recriar (Evolution só aceita proxy no /instance/create)
     * — fluxo de QR novo é exigido.
     */
    @Column(name = "proxy_pool", length = 4)
    private String proxyPool;

    /** Texto curto descrevendo o motivo da última queda inesperada.
     *  Ex.: "Webhook close (Baileys)", "Heartbeat morto há 60min". */
    @Column(name = "motivo_ultima_queda", length = 120)
    private String motivoUltimaQueda;

    /**
     * Toggle do bot: quando false, mensagens recebidas não disparam resposta
     * automática (mas continuam sendo persistidas pra histórico futuro).
     * Default true pra novo restaurante começar com bot ligado.
     */
    @Column(name = "bot_ativo", nullable = false)
    @Builder.Default
    private Boolean botAtivo = true;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    public enum Status {
        NOVA,              // criada localmente, ainda não chamou Evolution
        AGUARDANDO_QR,     // QR gerado, aguardando scan
        CONECTADA,         // scan feito, sessão ativa
        DESCONECTADA,      // logout (manual ou WhatsApp desconectou)
        ERRO               // falha na Evolution
    }
}
