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
                Map<String, Object> snap = afiliadosWebhookService.buscarSnapshot(codigoAfil);
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
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getSenha())
        );

        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        String slug = null;
        if (usuario.getRole() == Usuario.Role.RESTAURANTE) {
            slug = restauranteRepository.findByUsuarioEmail(usuario.getEmail())
                    .map(Restaurante::getSlug)
                    .orElse(null);
        }

        String accessToken = jwtUtil.gerarToken(usuario.getEmail(), usuario.getRole().name());
        String refreshToken = jwtUtil.gerarRefreshToken(usuario.getEmail());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nome(usuario.getNome())
                .email(usuario.getEmail())
                .role(usuario.getRole().name())
                .restauranteSlug(slug)
                .build();
    }

    @Transactional
    public void solicitarRecuperacaoSenha(RecuperarSenhaRequest request) {
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("E-mail não encontrado"));

        // Remove tokens antigos
        tokenRepository.deleteByUsuarioId(usuario.getId());

        // Gera novo token
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .usuario(usuario)
                .token(token)
                .expiracao(LocalDateTime.now().plusMinutes(30))
                .usado(false)
                .build();
        tokenRepository.save(resetToken);

        emailService.enviarRecuperacaoSenha(usuario.getEmail(), usuario.getNome(), token);
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