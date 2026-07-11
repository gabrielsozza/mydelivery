package com.mydelivery.service;

import com.mydelivery.dto.auth.*;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final RestauranteRepository restauranteRepository;
    private final AssinaturaRepository assinaturaRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final com.mydelivery.service.meta.MetaCapiService metaCapiService;

    // Módulo Equipe: login unificado (dono OU membro). Injeção defensiva
    // (required=false) pra não quebrar caso o bean não esteja disponível
    // por qualquer motivo — cai no fluxo antigo de dono.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.equipe.MembroEquipeRepository membroEquipeRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.equipe.AuditoriaService auditoriaService;

    /** Webhook async pro myafiliados-api — só dispara se restaurante tem afiliadoCodigo. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.service.afiliados.AfiliadosWebhookService afiliadosWebhookService;

    @Value("${app.trial.dias}")
    private int trialDias;

    @Value("${app.mensalidade.valor}")
    private double mensalidadeValor;

    @Transactional
    public LoginResponse cadastrar(CadastroRequest request) {
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("E-mail já cadastrado");
        }

        // ── ANTI-AUTOINDICAÇÃO ───────────────────────────────────────────
        // Se veio com código de afiliado, busca snapshot ANTES de qualquer
        // save e compara com dados do cadastro. Se detectar match forte
        // (email/CPF/telefone/PIX igual ao do afiliado), bloqueia com 409.
        //
        // Nada é gravado no banco em caso de bloqueio — nenhum Usuario,
        // nenhum Restaurante, nenhuma Assinatura. Fica o registro no log
        // de eventos do myafiliados-api pra o admin ver depois.
        Map<String, Object> snapAfiliado = null;
        String codigoAfilPreCheck = request.getAfiliadoCodigo();
        if (afiliadosWebhookService != null && codigoAfilPreCheck != null && !codigoAfilPreCheck.isBlank()) {
            try {
                snapAfiliado = afiliadosWebhookService.buscarSnapshot(codigoAfilPreCheck);
                if (snapAfiliado != null) {
                    var dados = new com.mydelivery.service.afiliados.AutoindicacaoDetector.DadosCadastro();
                    dados.email = request.getEmail();
                    dados.telefone = request.getTelefone();
                    // TODO: no futuro, se cadastro tiver CPF/CNPJ e chavePix, comparar também.
                    var res = com.mydelivery.service.afiliados.AutoindicacaoDetector.detectar(dados, snapAfiliado);
                    if (res.temMatchForte()) {
                        // Registra no log de eventos ANTES de lançar (fail-safe).
                        try {
                            afiliadosWebhookService.registrarAutoindicacaoBloqueada(
                                    codigoAfilPreCheck, request.getEmail(), request.getTelefone(),
                                    res.descricao(), res.paraLog());
                        } catch (Exception ignored) { /* logging não pode quebrar */ }
                        log.warn("[Auth] autoindicação BLOQUEADA codigo={} match={}",
                                codigoAfilPreCheck, res.descricao());
                        throw new com.mydelivery.service.afiliados.AutoindicacaoException(res.descricao());
                    }
                }
            } catch (com.mydelivery.service.afiliados.AutoindicacaoException fe) {
                throw fe; // deixa passar
            } catch (Exception e) {
                log.warn("[Auth] check anti-autoindicação falhou (permitindo cadastro): {}", e.getMessage());
            }
        }

        // Cria o usuário
        Usuario usuario = Usuario.builder()
                .nome(request.getNome())
                .email(request.getEmail())
                .senhaHash(passwordEncoder.encode(request.getSenha()))
                .telefone(request.getTelefone())
                .role(Usuario.Role.RESTAURANTE)
                .ativo(true)
                .build();
        usuarioRepository.save(usuario);

        // Gera slug único para o restaurante
        String slug = gerarSlug(request.getNomeRestaurante());

        // Cria o restaurante já com telefone do dono pré-preenchido
        // (evita o cliente ter que digitar de novo em "Configurações > Perfil")
        Restaurante restaurante = Restaurante.builder()
                .usuario(usuario)
                .nome(request.getNomeRestaurante())
                .slug(slug)
                .telefone(request.getTelefone()) // ← prefill do cadastro
                .status(Restaurante.Status.TRIAL)
                .trialExpiraEm(LocalDateTime.now().plusDays(trialDias))
                // Programa de afiliados: salva o codigo que veio no link (se houver)
                .afiliadoCodigo(request.getAfiliadoCodigo())
                .build();

        // ── Snapshot IMUTÁVEL do afiliado ─────────────────────────────
        // Se veio código, tenta buscar dados do afiliado NO ATO do cadastro e
        // salvar cópia local. Isso garante:
        //   (1) admin consegue ver "quem indicou" mesmo se myafiliados-api cair
        //   (2) histórico preservado se afiliado for deletado depois
        // Se a chamada falhar (offline/timeout), o snapshot fica vazio e o
        // webhook async continua tentando registrar o vínculo do outro lado.
        String codigoAfil = request.getAfiliadoCodigo();
        if (afiliadosWebhookService != null && codigoAfil != null && !codigoAfil.isBlank()) {
            try {
                // Reusa o snapshot que já foi buscado no check anti-autoindicação
                // (evita 2 round-trips pra myafiliados-api no cadastro).
                Map<String, Object> snap = snapAfiliado != null
                        ? snapAfiliado
                        : afiliadosWebhookService.buscarSnapshot(codigoAfil);
                if (snap != null && snap.get("id") != null) {
                    restaurante.setAfiliadoIdSnap(Long.valueOf(String.valueOf(snap.get("id"))));
                    restaurante.setAfiliadoNomeSnap((String) snap.get("nome"));
                    restaurante.setAfiliadoEmailSnap((String) snap.get("email"));
                    Object comOb = snap.get("comissaoPercentual");
                    if (comOb != null) {
                        try {
                            restaurante.setAfiliadoComissaoSnap(new java.math.BigDecimal(String.valueOf(comOb)));
                        } catch (Exception ignored) { /* comissão inválida — ignora */ }
                    }
                    restaurante.setAfiliadoVinculadoEm(LocalDateTime.now());
                }
            } catch (Exception e) {
                log.warn("[Auth] snapshot do afiliado {} falhou: {}", codigoAfil, e.getMessage());
            }
        }

        restauranteRepository.save(restaurante);

        // Webhook async pro myafiliados-api — só dispara se tem código
        try {
            if (afiliadosWebhookService != null) {
                afiliadosWebhookService.restauranteCriado(restaurante, request.getLinkOrigem());
            }
        } catch (Exception ignored) { /* fail-safe */ }

        // Cria a assinatura em trial.
        // trialDias = 32 (30 trial + 2 extras de configuração — definido em application.properties).
        // validaAte = trialFim pra que o frontend trate "dias restantes" uniformemente em trial e ativo.
        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime trialFim = agora.plusDays(trialDias);
        Assinatura assinatura = Assinatura.builder()
                .restaurante(restaurante)
                .status(Assinatura.Status.TRIAL)
                .plano(null) // trial não tem plano ainda
                .valor(java.math.BigDecimal.valueOf(mensalidadeValor))
                .trialInicio(agora)
                .trialFim(trialFim)
                .validaAte(trialFim)
                .proximaCobranca(trialFim)
                .build();
        assinaturaRepository.save(assinatura);

        // Cria configuração padrão do restaurante
        ConfiguracaoRestaurante config = new ConfiguracaoRestaurante();
        config.setRestaurante(restaurante);

        String accessToken = jwtUtil.gerarToken(usuario.getEmail(), usuario.getRole().name());
        String refreshToken = jwtUtil.gerarRefreshToken(usuario.getEmail());

        // ── Meta CAPI: CompleteRegistration (server-side) ──
        // Dispara em paralelo ao Pixel do front (mesmo evento "Concluir
        // inscrição"). Async + try-catch interno — nunca quebra o cadastro
        // mesmo se Meta cair.
        try {
            metaCapiService.completeRegistration(usuario.getEmail(),
                    usuario.getTelefone(), usuario.getNome());
        } catch (Exception ignored) { /* fail-safe */ }

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nome(usuario.getNome())
                .email(usuario.getEmail())
                .role(usuario.getRole().name())
                .restauranteSlug(slug)
                .build();
    }

    public LoginResponse login(LoginRequest request) {
        String identificador = request.getEmail() == null ? "" : request.getEmail().trim();

        // ── Roteamento: se tem "@" ou bate com email de dono, é fluxo dono.
        //    Senão, tenta como login de membro da equipe.
        boolean pareceEmail = identificador.contains("@");
        boolean tentouMembroPrimeiro = false;

        if (!pareceEmail && membroEquipeRepository != null) {
            LoginResponse resp = tentarLoginMembro(identificador, request.getSenha());
            if (resp != null) return resp;
            tentouMembroPrimeiro = true; // pra decidir mensagem de erro final
        }

        // Fluxo dono (comportamento original, preservado 100%)
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(identificador, request.getSenha())
            );
        } catch (RuntimeException e) {
            // Se veio SEM @ e falhou tanto membro quanto dono, mensagem só
            // diz que credenciais estão erradas — não vaza qual dos dois.
            if (tentouMembroPrimeiro) {
                throw new RuntimeException("Login ou senha inválidos");
            }
            throw e;
        }

        Usuario usuario = usuarioRepository.findByEmail(identificador)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        String slug = null;
        if (usuario.getRole() == Usuario.Role.RESTAURANTE) {
            slug = restauranteRepository.findByUsuarioEmail(usuario.getEmail())
                    .map(Restaurante::getSlug)
                    .orElse(null);
        }

        String accessToken = jwtUtil.gerarToken(usuario.getEmail(), usuario.getRole().name());
        String refreshToken = jwtUtil.gerarRefreshToken(usuario.getEmail());

        // Auditoria de LOGIN (dono). Melhor esforço — nunca bloqueia.
        if (auditoriaService != null && usuario.getRole() == Usuario.Role.RESTAURANTE) {
            try {
                Long restId = restauranteRepository.findByUsuarioEmail(usuario.getEmail())
                        .map(Restaurante::getId).orElse(null);
                if (restId != null) {
                    auditoriaService.registrar(restId,
                            com.mydelivery.equipe.AcaoAuditoria.LOGIN,
                            "Usuario", String.valueOf(usuario.getId()), null);
                }
            } catch (Exception ignore) {}
        }

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nome(usuario.getNome())
                .email(usuario.getEmail())
                .role(usuario.getRole().name())
                .restauranteSlug(slug)
                .build();
    }

    /**
     * Tenta autenticar como MEMBRO da equipe. Retorna null se:
     *   - login não existe
     *   - senha errada
     *   - membro bloqueado
     * (Todos os falses geram RuntimeException uniforme no caller pra não
     *  vazar existência de login vs senha errada.)
     */
    private LoginResponse tentarLoginMembro(String login, String senha) {
        // Usa a query com JOIN FETCH pra carregar restaurante + usuario numa
        // única transação. Sem isso, `m.getRestaurante().getUsuario().getEmail()`
        // abaixo caía em "no session" (LazyInitializationException) fora do
        // escopo transacional do repo.
        var membroOpt = membroEquipeRepository.findParaLoginByLoginIgnoreCase(login);
        if (membroOpt.isEmpty()) return null;
        var m = membroOpt.get();

        // Bloqueado nunca loga (mesmo com senha certa).
        if (m.getStatus() != com.mydelivery.equipe.StatusMembro.ATIVO) {
            throw new RuntimeException("Este acesso foi bloqueado pelo proprietário");
        }
        if (!passwordEncoder.matches(senha, m.getSenhaHash())) {
            return null; // caller vai jogar exception uniforme
        }

        // Emite JWT com sub = email do dono (compat com controllers existentes)
        // e claims extras pro filter aplicar as permissões deste membro.
        String emailDono = m.getRestaurante().getUsuario().getEmail();
        int tokenVersion = m.getTokenVersion() == null ? 0 : m.getTokenVersion();
        String accessToken = jwtUtil.gerarTokenMembro(emailDono, "RESTAURANTE", m.getId(), tokenVersion);
        String refreshToken = jwtUtil.gerarRefreshToken(emailDono);

        // Marca último login (best-effort)
        try {
            m.setUltimoLoginEm(java.time.LocalDateTime.now());
            membroEquipeRepository.save(m);
        } catch (Exception ignore) {}

        String slug = m.getRestaurante().getSlug();

        if (auditoriaService != null) {
            try {
                auditoriaService.registrar(m.getRestaurante().getId(),
                        com.mydelivery.equipe.AcaoAuditoria.LOGIN,
                        "MembroEquipe", String.valueOf(m.getId()), null);
            } catch (Exception ignore) {}
        }

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nome(m.getNomeCompleto())
                .email(emailDono)         // pra frontend saber o tenant
                .role("RESTAURANTE")
                .restauranteSlug(slug)
                .cargo(m.getCargo() == null ? null : m.getCargo().name())
                .permissoes(m.getPermissoesCsv())
                .build();
    }

    @Transactional
    public void solicitarRecuperacaoSenha(RecuperarSenhaRequest request) {
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("E-mail não encontrado"));

        // Limpeza dos tokens antigos DEPOIS do save — ordem antiga (delete→save)
        // podia colidir com flush automático do Hibernate quando havia FK
        // uniqueness em cenários específicos. Novo pattern: cria o novo,
        // deleta os anteriores em try isolado (best-effort — se falhar, não
        // bloqueia o novo). Correção jul/2026: dono reclamava de erro
        // intermitente ao pedir recuperação de senha.
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .usuario(usuario)
                .token(token)
                .expiracao(LocalDateTime.now().plusMinutes(30))
                .usado(false)
                .build();
        tokenRepository.save(resetToken);
        Long novoTokenId = resetToken.getId();
        try {
            // Marca os antigos como usados em vez de deletar — mantém histórico
            // e evita qualquer risco de FK/cascade em ambientes com constraint
            // pendente. Só marca os que NÃO são o token que acabamos de criar.
            tokenRepository.findAll().stream()
                    .filter(t -> t.getUsuario() != null
                            && t.getUsuario().getId().equals(usuario.getId())
                            && !t.getId().equals(novoTokenId)
                            && !Boolean.TRUE.equals(t.getUsado()))
                    .forEach(t -> { t.setUsado(true); tokenRepository.save(t); });
        } catch (Exception e) {
            log.warn("[Senha] falha invalidando tokens antigos do usuário {} (ignorado, novo token já salvo): {}",
                    usuario.getEmail(), e.getMessage());
        }

        try {
            emailService.enviarRecuperacaoSenha(usuario.getEmail(), usuario.getNome(), token);
        } catch (Exception e) {
            log.error("[Senha] erro enviando email pra {}: {}", usuario.getEmail(), e.getMessage());
            // Não propaga — token já foi salvo, dono pode reusar link se
            // receber o email. Se não receber, pede de novo.
        }
    }

    /**
     * Fluxo de troca de senha AUTENTICADO — usuário logado que quer trocar
     * a senha sem passar pelo email. Valida senha atual antes de aplicar
     * a nova. Retorna sem body em caso de sucesso; lança RuntimeException
     * em falha (senha atual errada, nova muito curta, etc).
     */
    @Transactional
    public void alterarSenhaAutenticado(String email, String senhaAtual, String novaSenha) {
        if (novaSenha == null || novaSenha.length() < 6) {
            throw new RuntimeException("Nova senha precisa ter no mínimo 6 caracteres");
        }
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
        if (senhaAtual == null || !passwordEncoder.matches(senhaAtual, usuario.getSenhaHash())) {
            throw new RuntimeException("Senha atual incorreta");
        }
        usuario.setSenhaHash(passwordEncoder.encode(novaSenha));
        usuarioRepository.save(usuario);
        log.info("[Senha] usuário {} alterou senha (fluxo autenticado)", email);
    }

    @Transactional
    public void redefinirSenha(RedefinirSenhaRequest request) {
        PasswordResetToken resetToken = tokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new RuntimeException("Token inválido"));

        if (resetToken.getUsado()) {
            throw new RuntimeException("Token já utilizado");
        }

        if (resetToken.getExpiracao().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token expirado");
        }

        Usuario usuario = resetToken.getUsuario();
        usuario.setSenhaHash(passwordEncoder.encode(request.getNovaSenha()));
        usuarioRepository.save(usuario);

        resetToken.setUsado(true);
        tokenRepository.save(resetToken);
    }

    private String gerarSlug(String nome) {
        String slug = nome.toLowerCase()
                .replaceAll("[áàãâä]", "a")
                .replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i")
                .replaceAll("[óòõôö]", "o")
                .replaceAll("[úùûü]", "u")
                .replaceAll("[ç]", "c")
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Garante slug único
        String slugFinal = slug;
        int contador = 1;
        while (restauranteRepository.existsBySlug(slugFinal)) {
            slugFinal = slug + "-" + contador++;
        }
        return slugFinal;
    }
}