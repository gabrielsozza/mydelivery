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
