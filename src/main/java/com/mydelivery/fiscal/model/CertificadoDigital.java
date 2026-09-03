package com.mydelivery.fiscal.model;

import java.time.LocalDateTime;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.*;
import lombok.*;

/**
 * Certificado A1 (.pfx) do CNPJ do restaurante — armazenado JÁ CRIPTOGRAFADO.
 * O banco NUNCA tem plain text. Descriptografia acontece só em memória, no
 * momento exato do uso, pelo {@code CofreCertificadoService}.
 */
@Entity
@Table(name = "certificado_digital")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificadoDigital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 20)
    private String cnpj;

    @Column(name = "nome_titular")
    private String nomeTitular;

    /** Cert PKCS12 criptografado com AES-256-GCM. */
    // Hibernate 6 + MySQL: @Lob byte[] vira TINYBLOB (255 bytes) por default —
    // certificado A1 cifrado tem ~3-4 KB, então TRUNCATE. columnDefinition
    // força LONGBLOB (4 GB) e Hibernate cria/mantém corretamente.
    @Lob
    @Column(name = "pfx_ciphertext", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] pfxCiphertext;

    @Column(name = "pfx_iv", nullable = false, length = 16)
    private byte[] pfxIv;

    @Column(name = "pfx_tag", nullable = false, length = 16)
    private byte[] pfxTag;

    /** Senha do PFX criptografada (separadamente, mesma proteção). */
    @Column(name = "senha_ciphertext", nullable = false, length = 1024)
    private byte[] senhaCiphertext;

    @Column(name = "senha_iv", nullable = false, length = 16)
    private byte[] senhaIv;

    @Column(name = "senha_tag", nullable = false, length = 16)
    private byte[] senhaTag;

    @Column(name = "valido_ate", nullable = false)
    private LocalDateTime validoAte;

    @Column(name = "fingerprint_sha256", nullable = false, length = 128)
    private String fingerprintSha256;

    @Column(nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
