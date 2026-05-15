package com.mydelivery.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.dto.carrinho.CarrinhoAbandonadoRequest;
import com.mydelivery.dto.carrinho.CarrinhoAbandonadoResponse;
import com.mydelivery.model.CarrinhoAbandonado;
import com.mydelivery.model.Cliente;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CarrinhoAbandonadoRepository;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarrinhoAbandonadoService {

    private final CarrinhoAbandonadoRepository carrinhoRepository;
    private final RestauranteRepository restauranteRepository;
    private final ClienteRepository clienteRepository;
    private final ConfiguracaoRestauranteRepository configuracaoRepository;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    // ── CARDÁPIO PÚBLICO ─────────────────────────────────────────────────────
    @Transactional
    public void salvarOuAtualizar(CarrinhoAbandonadoRequest req) {
        Restaurante restaurante = restauranteRepository.findBySlug(req.getSlugRestaurante())
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        // Sempre salva telefone normalizado (só dígitos)
        req.setTelefoneCliente(com.mydelivery.util.TelefoneUtil.normalizar(req.getTelefoneCliente()));

        String itensJson;
        try {
            itensJson = objectMapper.writeValueAsString(req.getItens());
        } catch (Exception e) {
            throw new RuntimeException("Erro ao serializar itens do carrinho");
        }

        Optional<CarrinhoAbandonado> existente = carrinhoRepository
                .findBySessionIdAndStatus(req.getSessionId(), CarrinhoAbandonado.Status.ATIVO);

        if (existente.isPresent()) {
            CarrinhoAbandonado carrinho = existente.get();
            carrinho.setItensJson(itensJson);
            carrinho.setNomeCliente(req.getNomeCliente());
            carrinho.setTelefoneCliente(req.getTelefoneCliente());
            carrinhoRepository.save(carrinho);
        } else {
            Cliente cliente = null;
            if (req.getTelefoneCliente() != null && !req.getTelefoneCliente().isBlank()) {
                cliente = clienteRepository
                        .findByTelefoneAndRestauranteId(req.getTelefoneCliente(), restaurante.getId())
                        .orElse(null);
            }

            CarrinhoAbandonado novo = CarrinhoAbandonado.builder()
                    .restaurante(restaurante)
                    .cliente(cliente)
                    .nomeCliente(req.getNomeCliente())
                    .telefoneCliente(req.getTelefoneCliente())
                    .itensJson(itensJson)
                    .sessionId(req.getSessionId())
                    .status(CarrinhoAbandonado.Status.ATIVO)
                    .build();
            carrinhoRepository.save(novo);
        }
    }

    @Transactional
    public void marcarComoRecuperado(String sessionId) {
        carrinhoRepository.findBySessionIdAndStatus(sessionId, CarrinhoAbandonado.Status.ATIVO)
                .ifPresent(c -> {
                    c.setStatus(CarrinhoAbandonado.Status.RECUPERADO);
                    carrinhoRepository.save(c);
                });
        carrinhoRepository.findBySessionIdAndStatus(sessionId, CarrinhoAbandonado.Status.NOTIFICADO)
                .ifPresent(c -> {
                    c.setStatus(CarrinhoAbandonado.Status.RECUPERADO);
                    carrinhoRepository.save(c);
                });
    }

    /**
     * Marca como RECUPERADO todos os carrinhos abandonados (ATIVO ou NOTIFICADO)
     * de um mesmo telefone + restaurante. Chamado quando um pedido é criado, pra
     * cobrir o caso em que o cliente voltou pelo cardápio direto (sessionId novo)
     * ao invés de pelo link de recuperação do WhatsApp.
     */
    @Transactional
    public void marcarRecuperadoPorTelefone(Long restauranteId, String telefone) {
        // Normaliza pra bater com o que o cliente preencheu no carrinho (também normalizado)
        telefone = com.mydelivery.util.TelefoneUtil.normalizar(telefone);
        if (restauranteId == null || telefone == null) return;

        List<CarrinhoAbandonado> abertos = carrinhoRepository
                .findByRestauranteIdAndTelefoneClienteAndStatusIn(
                        restauranteId,
                        telefone,
                        List.of(CarrinhoAbandonado.Status.ATIVO, CarrinhoAbandonado.Status.NOTIFICADO));

        if (abertos.isEmpty()) return;

        for (CarrinhoAbandonado c : abertos) {
            c.setStatus(CarrinhoAbandonado.Status.RECUPERADO);
        }
        carrinhoRepository.saveAll(abertos);
        log.info("✅ {} carrinho(s) marcado(s) como RECUPERADO para telefone {} no restaurante {}",
                abertos.size(), telefone, restauranteId);
    }

    // ── PAINEL DO RESTAURANTE ─────────────────────────────────────────────────
    public List<CarrinhoAbandonadoResponse> listarPorRestaurante(String slug) {
        return carrinhoRepository.findByRestauranteSlugOrderByCriadoEmDesc(slug)
                .stream()
                .map(CarrinhoAbandonadoResponse::new)
                .collect(Collectors.toList());
    }

    @Transactional
    public void notificar(Long id) {
        CarrinhoAbandonado c = carrinhoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Carrinho não encontrado"));
        c.setStatus(CarrinhoAbandonado.Status.NOTIFICADO);
        c.setNotificadoEm(LocalDateTime.now());
        carrinhoRepository.save(c);
    }

    @Transactional
    public void arquivar(Long id) {
        CarrinhoAbandonado c = carrinhoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Carrinho não encontrado"));
        c.setStatus(CarrinhoAbandonado.Status.IGNORADO);
        carrinhoRepository.save(c);
    }

    // ── JOBS AGENDADOS ────────────────────────────────────────────────────────
    // Roda a cada minuto. A janela é controlada por `tempoAbandonoMinutos` na
    // ConfiguracaoRestaurante (padrão 5min). Aqui só pré-filtramos os ATIVOS
    // criados há pelo menos 1 minuto pra evitar varrer carrinhos novíssimos.
    @Scheduled(fixedDelay = 60 * 1000)
    @Transactional
    public void verificarCarrinhosAbandonados() {
        log.info("Verificando carrinhos abandonados...");

        List<CarrinhoAbandonado> ativos = carrinhoRepository
                .findByStatusAndCriadoEmBefore(CarrinhoAbandonado.Status.ATIVO,
                        LocalDateTime.now().minusMinutes(1));

        for (CarrinhoAbandonado carrinho : ativos) {
            int tempoAbandono = configuracaoRepository
                    .findByRestauranteId(carrinho.getRestaurante().getId())
                    .map(c -> c.getTempoAbandonoMinutos() != null ? c.getTempoAbandonoMinutos() : 5)
                    .orElse(5);

            LocalDateTime limiteAbandono = carrinho.getCriadoEm().plusMinutes(tempoAbandono);
            if (LocalDateTime.now().isAfter(limiteAbandono)) {
                dispararNotificacao(carrinho);
            }
        }
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void arquivarCarrinhosAntigos() {
        List<CarrinhoAbandonado> notificados = carrinhoRepository
                .findByStatusAndCriadoEmBefore(CarrinhoAbandonado.Status.NOTIFICADO,
                        LocalDateTime.now().minusHours(24));
        notificados.forEach(c -> c.setStatus(CarrinhoAbandonado.Status.IGNORADO));
        carrinhoRepository.saveAll(notificados);
        log.info("Arquivados {} carrinhos antigos", notificados.size());
    }

    // ── NOTIFICAÇÃO POR E-MAIL ────────────────────────────────────────────────
    private void dispararNotificacao(CarrinhoAbandonado carrinho) {
        String email = carrinho.getCliente() != null ? carrinho.getCliente().getEmail() : null;
        if (email == null || email.isBlank()) {
            carrinho.setStatus(CarrinhoAbandonado.Status.NOTIFICADO);
            carrinho.setNotificadoEm(LocalDateTime.now());
            carrinhoRepository.save(carrinho);
            return;
        }

        try {
            String linkRecuperacao = "https://seusite.com/loja/"
                    + carrinho.getRestaurante().getSlug()
                    + "?carrinho=" + carrinho.getSessionId();

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(email);
            msg.setSubject("Você esqueceu algo no carrinho! 🛒");
            msg.setText(
                    "Oi " + (carrinho.getNomeCliente() != null ? carrinho.getNomeCliente() : "") + "!\n\n"
                    + "Você deixou itens no carrinho de " + carrinho.getRestaurante().getNome() + ".\n\n"
                    + "Clique para finalizar seu pedido:\n" + linkRecuperacao + "\n\n"
                    + "Equipe " + carrinho.getRestaurante().getNome()
            );

            mailSender.send(msg);

            carrinho.setStatus(CarrinhoAbandonado.Status.NOTIFICADO);
            carrinho.setNotificadoEm(LocalDateTime.now());
            carrinhoRepository.save(carrinho);

            log.info("Notificação enviada para carrinho {}", carrinho.getId());
        } catch (Exception e) {
            log.error("Erro ao enviar notificação para carrinho {}: {}", carrinho.getId(), e.getMessage());
        }
    }
}
