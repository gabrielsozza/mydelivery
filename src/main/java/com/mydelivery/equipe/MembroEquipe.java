package com.mydelivery.equipe;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Membro adicional da equipe do restaurante — usuário com login/senha próprios
 * e set de permissões granular. NÃO é o proprietário — o proprietário continua
 * sendo o Usuario original 1:1 com Restaurante.
 *
 * Modelo tenant:
 *   Todo membro pertence a exatamente 1 restaurante. Os endpoints existentes
 *   resolvem tenant via {@code @AuthenticationPrincipal String email} — nós
 *   emitimos o JWT do membro com {@code sub = email do dono do restaurante}
 *   pra reaproveitar essa infra sem tocar. O contexto do membro (id, cargo,
 *   permissões) é injetado pelo JwtAuthenticationFilter e fica no
 *   PermissaoContext (ThreadLocal derivado do SecurityContext).
 *
 * Chave de login:
 *   {@code (restaurante_id, login)} é UNIQUE. Dois restaurantes diferentes
 *   podem ter um membro "joao" — o login no endpoint precisa desambiguar,
 *   e o AuthService tenta primeiro email de dono, depois procura login em
 *   membros ATIVO (bloqueados não logam).
 *
 * Permissões:
 *   Guardadas como CSV de nomes de {@link Permissao} pra simplicidade
 *   (não precisa de tabela N:N nem JSON estruturado — set pequeno,
 *   pesquisa in-memory por membro logado). Enums desconhecidos ao ler
 *   são ignorados (evita quebra em rollback de migração).
 *
 * tokenVersion:
 *   Incrementado quando: bloqueia, altera permissões, exclui. JWTs emitidos
 *   com versão anterior batem no filter e são rejeitados como 401 —
 *   invalidação instantânea SEM manter blocklist de tokens.
 */
@Entity
@Table(name = "membros_equipe",
       uniqueConstraints = @UniqueConstraint(name = "uk_membro_login_rest",
                                             columnNames = {"restaurante_id", "login"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MembroEquipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(name = "nome_completo", nullable = false, length = 120)
    private String nomeCompleto;

    @Column(length = 160)
    private String email;

    @Column(length = 30)
    private String telefone;

    @Column(nullable = false, length = 60)
    private String login;

    /** BCrypt. Nunca em log. */
    @Column(name = "senha_hash", nullable = false, length = 100)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Cargo cargo = Cargo.FUNCIONARIO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatusMembro status = StatusMembro.ATIVO;

    /**
     * CSV de nomes de {@link Permissao}. Ex: "VER_DASHBOARD,VER_PEDIDOS_DELIVERY,ALTERAR_STATUS_PEDIDOS".
     * Null/vazio = sem nenhuma permissão (útil pra membro recém-criado enquanto dono ajusta).
     */
    @Column(name = "permissoes_csv", length = 2000)
    private String permissoesCsv;

    /**
     * Contador incrementado a cada bloqueio/edição de permissão/exclusão.
     * JWT do membro carrega este número; filter compara com o DB. Diferente = 401.
     */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Integer tokenVersion = 0;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @Column(name = "criado_por", length = 160)
    private String criadoPor;

    @Column(name = "ultimo_login_em")
    private LocalDateTime ultimoLoginEm;
}
