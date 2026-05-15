package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anexo de uma mensagem de suporte.
 *
 * NÃO guardamos o arquivo no banco (BLOB) — guardamos apenas o path relativo
 * pra pasta de uploads. Servir pelo endpoint /api/suporte/anexos/{id} que
 * verifica tenant antes de stream.
 *
 * nomeOriginal é mantido pra UX (download com o nome correto). nomeArquivo
 * (o salvo em disco) é UUID — evita colisão e nomes maliciosos.
 */
@Entity
@Table(name = "suporte_anexos", indexes = {
        @Index(name = "idx_anexo_msg", columnList = "mensagem_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuporteAnexo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mensagem_id", nullable = false)
    private SuporteMensagem mensagem;

    /** Nome original do arquivo enviado pelo usuário (pra UX/download). */
    @Column(name = "nome_original", length = 255)
    private String nomeOriginal;

    /**
     * Identificador do arquivo na origem de armazenamento.
     * - Legado: UUID + extensão no filesystem local (./uploads/suporte/{ticketId}/).
     * - Novo:   public_id do Cloudinary (anexos a partir desta migração).
     */
    @Column(name = "nome_arquivo", length = 200, nullable = false)
    private String nomeArquivo;

    /**
     * URL pública (secure_url do Cloudinary) do anexo.
     * Preenchida sempre nos uploads novos. Null em anexos legados que ainda
     * estão no filesystem local — esses caem no fluxo de stream do disco.
     */
    @Column(name = "url", columnDefinition = "TEXT")
    private String url;

    @Column(name = "mime_type", length = 80)
    private String mimeType;

    @Column(name = "tamanho_bytes")
    private Long tamanhoBytes;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;
}
