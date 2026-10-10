package com.mydelivery.fiscal.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Foto ou PDF de uma nota recebida. Não é um XML fiscal nem gera escrituração automática. */
@Entity
@Table(name = "nota_entrada_anexo",
        indexes = @Index(name = "idx_nea_rest_data", columnList = "restaurante_id, criado_em"),
        uniqueConstraints = @UniqueConstraint(name = "uk_nea_rest_sha", columnNames = {"restaurante_id", "sha256"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotaEntradaAnexo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "tipo_mime", nullable = false, length = 80)
    private String tipoMime;

    @Column(nullable = false)
    private Long tamanho;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] conteudo;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;
}
