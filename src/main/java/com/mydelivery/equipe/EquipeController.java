package com.mydelivery.equipe;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints do módulo Equipe.
 *
 * Autenticação: {@code hasRole('RESTAURANTE')} — precisa estar logado como
 *   dono OU como membro (JWT ambos carregam a role).
 *
 * Autorização granular via @PermissaoRequerida:
 *   - Ler lista/detalhe → VER_EQUIPE (dono sempre passa; gerente/funcionário
 *     só se explicitamente permitido)
 *   - Criar   → CADASTRAR_USUARIOS
 *   - Editar  → EDITAR_USUARIOS
 *   - Bloquear/Desbloquear → EDITAR_USUARIOS
 *   - Excluir → EXCLUIR_USUARIOS
 *   - Catálogo de permissões e cargos → público pra qualquer autenticado
 *     (não é info sensível, é só rótulos pra montar checkboxes)
 */
@RestController
@RequiredArgsConstructor
public class EquipeController {

    private final MembroEquipeService service;

    // ── LISTAR ────────────────────────────────────────────────────────
    @GetMapping("/api/equipe")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_EQUIPE)
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String emailDono) {
        List<Map<String, Object>> out = service.listarDoRestaurante(emailDono)
                .stream()
                .map(EquipeController::toDtoSemSenha)
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/equipe/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_EQUIPE)
    public ResponseEntity<Map<String, Object>> detalhar(
            @AuthenticationPrincipal String emailDono,
            @PathVariable Long id) {
        return ResponseEntity.ok(toDtoSemSenha(service.buscar(emailDono, id)));
    }

    // ── CRIAR ─────────────────────────────────────────────────────────
    @PostMapping("/api/equipe")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.CADASTRAR_USUARIOS)
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String emailDono,
            @RequestBody Map<String, Object> body) {
        var dados = parseDados(body);
        var salvo = service.criar(emailDono, dados);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDtoSemSenha(salvo));
    }

    // ── EDITAR ────────────────────────────────────────────────────────
    @PutMapping("/api/equipe/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.EDITAR_USUARIOS)
    public ResponseEntity<Map<String, Object>> editar(
            @AuthenticationPrincipal String emailDono,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        var dados = parseDados(body);
        var salvo = service.editar(emailDono, id, dados);
        return ResponseEntity.ok(toDtoSemSenha(salvo));
    }

    // ── BLOQUEAR ──────────────────────────────────────────────────────
    @PatchMapping("/api/equipe/{id}/bloquear")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.EDITAR_USUARIOS)
    public ResponseEntity<Map<String, Object>> bloquear(
            @AuthenticationPrincipal String emailDono,
            @PathVariable Long id) {
        return ResponseEntity.ok(toDtoSemSenha(service.bloquear(emailDono, id)));
    }

    @PatchMapping("/api/equipe/{id}/desbloquear")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.EDITAR_USUARIOS)
    public ResponseEntity<Map<String, Object>> desbloquear(
            @AuthenticationPrincipal String emailDono,
            @PathVariable Long id) {
        return ResponseEntity.ok(toDtoSemSenha(service.desbloquear(emailDono, id)));
    }

    // ── EXCLUIR ───────────────────────────────────────────────────────
    @DeleteMapping("/api/equipe/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.EXCLUIR_USUARIOS)
    public ResponseEntity<Void> excluir(
            @AuthenticationPrincipal String emailDono,
            @PathVariable Long id) {
        service.excluir(emailDono, id);
        return ResponseEntity.noContent().build();
    }

    // ── CATÁLOGO ──────────────────────────────────────────────────────
    /**
     * Retorna o vocabulário de cargos e permissões pra o frontend renderizar
     * checkboxes/dropdowns sem hardcode. Autenticado, mas sem checagem de
     * VER_EQUIPE — a página de config de perfil próprio também pode consumir.
     */
    @GetMapping("/api/equipe/catalogo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> catalogo() {
        Map<String, Object> out = new LinkedHashMap<>();
        // Cargos + template default
        List<Map<String, Object>> cargos = java.util.Arrays.stream(Cargo.values())
                .filter(c -> c != Cargo.PROPRIETARIO) // template só pra gerente/funcionario
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.name());
                    m.put("label", c.getLabel());
                    m.put("permissoesDefault", Permissao.defaultDoCargo(c)
                            .stream().map(Enum::name).sorted().toList());
                    return m;
                })
                .toList();
        out.put("cargos", cargos);
        // Permissões agrupadas por grupo pra UI renderizar seções
        Map<String, List<Map<String, Object>>> grupos = new LinkedHashMap<>();
        for (Permissao p : Permissao.values()) {
            grupos.computeIfAbsent(p.getGrupo(), k -> new java.util.ArrayList<>())
                    .add(Map.of("id", p.name(), "label", p.getLabel()));
        }
        out.put("permissoes", grupos);
        return ResponseEntity.ok(out);
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private static Map<String, Object> toDtoSemSenha(MembroEquipe m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", m.getId());
        d.put("nomeCompleto", m.getNomeCompleto());
        d.put("email", m.getEmail());
        d.put("telefone", m.getTelefone());
        d.put("login", m.getLogin());
        d.put("cargo", m.getCargo() == null ? null : m.getCargo().name());
        d.put("status", m.getStatus() == null ? null : m.getStatus().name());
        d.put("permissoes", parseCsvList(m.getPermissoesCsv()));
        d.put("criadoEm", m.getCriadoEm());
        d.put("ultimoLoginEm", m.getUltimoLoginEm());
        return d;
    }

    private static List<String> parseCsvList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static MembroEquipeService.DadosMembro parseDados(Map<String, Object> body) {
        String nome = str(body.get("nomeCompleto"));
        String email = str(body.get("email"));
        String telefone = str(body.get("telefone"));
        String login = str(body.get("login"));
        String senha = str(body.get("senha"));
        Cargo cargo = null;
        Object cargoRaw = body.get("cargo");
        if (cargoRaw != null) {
            try { cargo = Cargo.valueOf(cargoRaw.toString()); } catch (Exception ignore) {}
        }
        Set<Permissao> permissoes = null;
        Object permsRaw = body.get("permissoes");
        if (permsRaw instanceof List<?> lst) {
            List<String> raw = new java.util.ArrayList<>();
            for (Object o : lst) if (o != null) raw.add(o.toString());
            permissoes = MembroEquipeService.parsePermissoes(raw);
        } else if (permsRaw == null && body.containsKey("permissoes")) {
            permissoes = EnumSet.noneOf(Permissao.class);
        }
        return new MembroEquipeService.DadosMembro(nome, email, telefone, login, senha, cargo, permissoes);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
