package com.mydelivery.equipe;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

/**
 * CRUD de MembroEquipe. Toda operação é multi-tenant: recebe o
 * {@code emailDono} do @AuthenticationPrincipal e resolve o restaurante,
 * garantindo que ninguém enxergue/mexa em membro de outra loja.
 *
 * Segurança adicional: as ações de escrita são gated no CONTROLLER via
 * @PermissaoRequerida — proprietário passa sempre, membro só se tiver
 * CADASTRAR_USUARIOS/EDITAR_USUARIOS/EXCLUIR_USUARIOS.
 *
 * Auditoria: chamadas de create/edit/block/unblock/delete registram no
 * AuditoriaService pra futura tela de histórico.
 */
@Service
@RequiredArgsConstructor
public class MembroEquipeService {

    private final MembroEquipeRepository repo;
    private final RestauranteRepository restauranteRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditoriaService auditoriaService;

    // ── LISTAR ────────────────────────────────────────────────────────
    public List<MembroEquipe> listarDoRestaurante(String emailDono) {
        Restaurante r = resolverRestaurante(emailDono);
        return repo.findByRestauranteIdOrderByNomeCompletoAsc(r.getId());
    }

    public MembroEquipe buscar(String emailDono, Long id) {
        MembroEquipe m = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membro não encontrado"));
        guardTenant(m, emailDono);
        return m;
    }

    // ── CRIAR ─────────────────────────────────────────────────────────
    @Transactional
    public MembroEquipe criar(String emailDono, DadosMembro dados) {
        Restaurante r = resolverRestaurante(emailDono);
        validarDados(dados, true);
        String login = dados.login().trim();
        if (repo.existsByLoginIgnoreCase(login)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este login já está em uso");
        }
        Cargo cargo = dados.cargo() == null ? Cargo.FUNCIONARIO : dados.cargo();
        // Se o front não enviou permissões, aplica o template do cargo.
        // Se enviou, usa exatamente o que o dono marcou (respeita ajustes finos).
        Set<Permissao> permissoes = (dados.permissoes() == null || dados.permissoes().isEmpty())
                ? Permissao.defaultDoCargo(cargo)
                : dados.permissoes();

        MembroEquipe m = MembroEquipe.builder()
                .restaurante(r)
                .nomeCompleto(dados.nomeCompleto().trim())
                .email(vazio(dados.email()) ? null : dados.email().trim().toLowerCase())
                .telefone(vazio(dados.telefone()) ? null : dados.telefone().trim())
                .login(login)
                .senhaHash(passwordEncoder.encode(dados.senha()))
                .cargo(cargo)
                .status(StatusMembro.ATIVO)
                .permissoesCsv(serializar(permissoes))
                .tokenVersion(0)
                .criadoPor(emailDono)
                .build();
        MembroEquipe salvo = repo.save(m);

        auditoriaService.registrar(r.getId(), AcaoAuditoria.MEMBRO_CRIADO,
                "MembroEquipe", String.valueOf(salvo.getId()),
                "{\"login\":\"" + login.replace("\"", "\\\"") + "\",\"cargo\":\"" + cargo.name() + "\"}");
        return salvo;
    }

    // ── EDITAR ────────────────────────────────────────────────────────
    @Transactional
    public MembroEquipe editar(String emailDono, Long id, DadosMembro dados) {
        MembroEquipe m = buscar(emailDono, id);
        validarDados(dados, false);
        if (!vazio(dados.nomeCompleto())) m.setNomeCompleto(dados.nomeCompleto().trim());
        if (dados.email() != null) m.setEmail(vazio(dados.email()) ? null : dados.email().trim().toLowerCase());
        if (dados.telefone() != null) m.setTelefone(vazio(dados.telefone()) ? null : dados.telefone().trim());
        if (!vazio(dados.senha())) m.setSenhaHash(passwordEncoder.encode(dados.senha()));
        if (dados.cargo() != null) m.setCargo(dados.cargo());
        if (dados.permissoes() != null) m.setPermissoesCsv(serializar(dados.permissoes()));

        // Alteração de permissões/senha/cargo INVALIDA a sessão atual do
        // membro imediatamente — ele precisa relogar. Evita cenário de
        // "reduzi permissão mas ele continua usando as antigas".
        m.setTokenVersion((m.getTokenVersion() == null ? 0 : m.getTokenVersion()) + 1);
        MembroEquipe salvo = repo.save(m);

        auditoriaService.registrar(m.getRestaurante().getId(),
                AcaoAuditoria.MEMBRO_EDITADO,
                "MembroEquipe", String.valueOf(salvo.getId()), null);
        return salvo;
    }

    // ── BLOQUEAR / DESBLOQUEAR ────────────────────────────────────────
    @Transactional
    public MembroEquipe bloquear(String emailDono, Long id) {
        MembroEquipe m = buscar(emailDono, id);
        m.setStatus(StatusMembro.BLOQUEADO);
        m.setTokenVersion((m.getTokenVersion() == null ? 0 : m.getTokenVersion()) + 1);
        MembroEquipe salvo = repo.save(m);
        auditoriaService.registrar(m.getRestaurante().getId(),
                AcaoAuditoria.MEMBRO_BLOQUEADO,
                "MembroEquipe", String.valueOf(salvo.getId()), null);
        return salvo;
    }

    @Transactional
    public MembroEquipe desbloquear(String emailDono, Long id) {
        MembroEquipe m = buscar(emailDono, id);
        m.setStatus(StatusMembro.ATIVO);
        MembroEquipe salvo = repo.save(m);
        auditoriaService.registrar(m.getRestaurante().getId(),
                AcaoAuditoria.MEMBRO_DESBLOQUEADO,
                "MembroEquipe", String.valueOf(salvo.getId()), null);
        return salvo;
    }

    // ── EXCLUIR ───────────────────────────────────────────────────────
    @Transactional
    public void excluir(String emailDono, Long id) {
        MembroEquipe m = buscar(emailDono, id);
        Long restId = m.getRestaurante().getId();
        String snap = "{\"login\":\"" + (m.getLogin() == null ? "" : m.getLogin().replace("\"", "\\\"")) + "\"}";
        repo.delete(m);
        auditoriaService.registrar(restId,
                AcaoAuditoria.MEMBRO_EXCLUIDO,
                "MembroEquipe", String.valueOf(id), snap);
    }

    // ── Helpers privados ─────────────────────────────────────────────
    private Restaurante resolverRestaurante(String emailDono) {
        return restauranteRepository.findByUsuarioEmail(emailDono)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Restaurante do usuário não localizado"));
    }

    /** Guard multi-tenant: um restaurante não mexe em membro de outro. */
    private void guardTenant(MembroEquipe m, String emailDono) {
        Restaurante r = resolverRestaurante(emailDono);
        if (!r.getId().equals(m.getRestaurante().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a este membro");
        }
    }

    private void validarDados(DadosMembro d, boolean criando) {
        if (criando) {
            if (vazio(d.nomeCompleto())) badRequest("Nome completo é obrigatório");
            if (vazio(d.login())) badRequest("Login é obrigatório");
            if (vazio(d.senha())) badRequest("Senha é obrigatória");
            if (d.senha().length() < 6) badRequest("Senha deve ter pelo menos 6 caracteres");
        } else {
            if (d.senha() != null && !d.senha().isEmpty() && d.senha().length() < 6) {
                badRequest("Senha deve ter pelo menos 6 caracteres");
            }
        }
    }

    private static void badRequest(String msg) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static boolean vazio(String s) { return s == null || s.trim().isEmpty(); }

    private static String serializar(Set<Permissao> ps) {
        if (ps == null || ps.isEmpty()) return "";
        return ps.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** DTO interno pra o Controller montar. Record = imutável, evita builder. */
    public record DadosMembro(
            String nomeCompleto,
            String email,
            String telefone,
            String login,
            String senha,
            Cargo cargo,
            Set<Permissao> permissoes) {}

    /** Facilita o parse de permissoes vindas do request (List<String> → EnumSet). */
    public static Set<Permissao> parsePermissoes(java.util.Collection<String> raw) {
        if (raw == null || raw.isEmpty()) return EnumSet.noneOf(Permissao.class);
        Set<Permissao> out = EnumSet.noneOf(Permissao.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try { out.add(Permissao.valueOf(s.trim())); }
            catch (IllegalArgumentException ignore) { /* enum removido, ignora */ }
        }
        return out;
    }
}
