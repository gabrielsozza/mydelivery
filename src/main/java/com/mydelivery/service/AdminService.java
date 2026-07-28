package com.mydelivery.service;

import com.mydelivery.dto.admin.*;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final RestauranteRepository restauranteRepository;
    private final AssinaturaRepository assinaturaRepository;
    private final PedidoRepository pedidoRepository;
    private final UsuarioRepository usuarioRepository;

    public DashboardAdminResponse getDashboard() {
        List<Restaurante> todos = restauranteRepository.findAll();

        long ativos     = todos.stream().filter(r -> r.getStatus() == Restaurante.Status.ATIVO).count();
        long trial      = todos.stream().filter(r -> r.getStatus() == Restaurante.Status.TRIAL).count();
        long bloqueados = todos.stream().filter(r -> r.getStatus() == Restaurante.Status.BLOQUEADO).count();
        long cancelados = todos.stream().filter(r -> r.getStatus() == Restaurante.Status.CANCELADO).count();

        // Receita mensal = soma das assinaturas ativas
        BigDecimal receita = assinaturaRepository.findAll().stream()
                .filter(a -> a.getStatus() == Assinatura.Status.ATIVA)
                .map(Assinatura::getValor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Pedidos de hoje
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia    = inicioDia.plusDays(1);
        long pedidosHoje = pedidoRepository.findAll().stream()
                .filter(p -> p.getCriadoEm() != null &&
                             p.getCriadoEm().isAfter(inicioDia) &&
                             p.getCriadoEm().isBefore(fimDia))
                .count();

        return DashboardAdminResponse.builder()
                .totalRestaurantes(todos.size())
                .restaurantesAtivos(ativos)
                .restaurantesEmTrial(trial)
                .restaurantesBloqueados(bloqueados)
                .restaurantesCancelados(cancelados)
                .receitaMensal(receita)
                .totalPedidosHoje(pedidosHoje)
                .build();
    }

    public List<RestauranteAdminResponse> listarRestaurantes() {
        return restauranteRepository.findAll()
                .stream().map(this::toAdminResponse).toList();
    }

    public RestauranteAdminResponse buscarRestaurante(Long id) {
        Restaurante r = restauranteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        return toAdminResponse(r);
    }

    @Transactional
    public RestauranteAdminResponse bloquearRestaurante(Long id,
                                                         BloquearRestauranteRequest request) {
        Restaurante r = restauranteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        r.setStatus(Restaurante.Status.BLOQUEADO);
        r.setBloqueadoEm(LocalDateTime.now());
        r.setMotivoBloqueio(request.getMotivo());

        // Bloqueia assinatura
        assinaturaRepository.findByRestauranteId(id).ifPresent(a -> {
            a.setStatus(Assinatura.Status.INADIMPLENTE);
            assinaturaRepository.save(a);
        });

        return toAdminResponse(restauranteRepository.save(r));
    }

    @Transactional
    public RestauranteAdminResponse desbloquearRestaurante(Long id) {
        return desbloquearRestaurante(id, 30);
    }

    /**
     * Desbloqueia + concede {@code dias} de acesso a partir de agora.
     * Empurra trialExpiraEm (Restaurante), trialFim + proximaCobranca (Assinatura)
     * pra now+dias. Se essas datas ficassem no passado a loja continuava bloqueada
     * mesmo com status=ATIVO — foi o bug do incidente Monkeys #9.
     */
    @Transactional
    public RestauranteAdminResponse desbloquearRestaurante(Long id, int dias) {
        Restaurante r = restauranteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        LocalDateTime alvo = LocalDateTime.now().plusDays(dias);

        r.setStatus(Restaurante.Status.ATIVO);
        r.setBloqueadoEm(null);
        r.setMotivoBloqueio(null);
        try { r.setTrialExpiraEm(alvo); } catch (Exception ignored) {}

        assinaturaRepository.findByRestauranteId(id).ifPresent(a -> {
            a.setStatus(Assinatura.Status.ATIVA);
            a.setProximaCobranca(alvo);
            // CRÍTICO: validaAte é a fonte da verdade no obterStatus/trial-banner.
            // Se ficar no passado, overlay "Seu período gratuito acabou" aparece
            // mesmo com status ATIVO. Fix Monkeys #9.
            a.setValidaAte(alvo);
            a.setUltimaCobranca(java.time.LocalDateTime.now());
            try { a.setTrialFim(alvo); } catch (Exception ignored) {}
            assinaturaRepository.save(a);
        });

        return toAdminResponse(restauranteRepository.save(r));
    }

    @Transactional
    public RestauranteAdminResponse cancelarRestaurante(Long id) {
        Restaurante r = restauranteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        r.setStatus(Restaurante.Status.CANCELADO);

        assinaturaRepository.findByRestauranteId(id).ifPresent(a -> {
            a.setStatus(Assinatura.Status.CANCELADA);
            a.setCanceladoEm(LocalDateTime.now());
            assinaturaRepository.save(a);
        });

        // Desativa o usuário dono
        Usuario usuario = r.getUsuario();
        usuario.setAtivo(false);
        usuarioRepository.save(usuario);

        return toAdminResponse(restauranteRepository.save(r));
    }

    private RestauranteAdminResponse toAdminResponse(Restaurante r) {
        RestauranteAdminResponse.AssinaturaInfo assinaturaInfo = null;

        var assinaturaOpt = assinaturaRepository.findByRestauranteId(r.getId());
        if (assinaturaOpt.isPresent()) {
            Assinatura a = assinaturaOpt.get();
            assinaturaInfo = RestauranteAdminResponse.AssinaturaInfo.builder()
                    .status(a.getStatus().name())
                    .valor(a.getValor())
                    .proximaCobranca(a.getProximaCobranca())
                    .trialFim(a.getTrialFim())
                    .build();
        }

        return RestauranteAdminResponse.builder()
                .id(r.getId())
                .nome(r.getNome())
                .slug(r.getSlug())
                .cnpj(r.getCnpj())
                .emailDono(r.getUsuario().getEmail())
                .telefoneDono(r.getUsuario().getTelefone())
                .status(r.getStatus())
                .trialExpiraEm(r.getTrialExpiraEm())
                .bloqueadoEm(r.getBloqueadoEm())
                .motivoBloqueio(r.getMotivoBloqueio())
                .criadoEm(r.getCriadoEm())
                .assinatura(assinaturaInfo)
                .build();
    }
}