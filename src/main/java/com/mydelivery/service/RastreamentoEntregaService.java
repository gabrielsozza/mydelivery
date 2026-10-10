package com.mydelivery.service;

import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.WhatsappBotService;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Regras do rastreamento temporário. A localização atual substitui a anterior; não mantemos trilha de GPS. */
@Service
@RequiredArgsConstructor
public class RastreamentoEntregaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TENTATIVAS = 5;
    private final PedidoRepository pedidos;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WhatsappBotService whatsappBot;

    public record Inicio(String token, String codigo) {}

    public void prepararLinkCliente(Pedido pedido) {
        if (pedido.getTipo() != Pedido.Tipo.DELIVERY || pedido.getOrigem() == Pedido.Origem.IFOOD
                || !Boolean.TRUE.equals(pedido.getRestaurante().getRastreamentoEntregaAtivo())) return;
        String token = randomToken();
        pedido.setRastreamentoTokenHash(hash(token));
        pedido.setRastreamentoTokenTemporario(token);
        pedido.setRastreamentoExpiraEm(LocalDateTime.now().plusHours(24));
    }

    @Transactional
    public Inicio iniciar(Pedido pedido, boolean consentimentoInformado) {
        Restaurante loja = pedido.getRestaurante();
        boolean gpsAtivo = Boolean.TRUE.equals(loja.getRastreamentoEntregaAtivo());
        boolean codigoAtivo = Boolean.TRUE.equals(loja.getEntregaCodigoConfirmacaoAtivo());
        if (!gpsAtivo && !codigoAtivo) throw new ResponseStatusException(HttpStatus.CONFLICT, "Recursos de entrega avançados estão desativados nesta loja");
        if (gpsAtivo && !consentimentoInformado) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A autorização de localização é obrigatória");
        if (pedido.getTipo() != Pedido.Tipo.DELIVERY || pedido.getOrigem() == Pedido.Origem.IFOOD)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este recurso está disponível para deliveries próprios");
        if (pedido.getStatus() != Pedido.Status.SAIU_ENTREGA || pedido.getEntregador() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido sem entregador em rota");
        if (gpsAtivo && pedido.getRastreamentoConsentidoEm() != null && !expirado(pedido)
                && (!codigoAtivo || (pedido.getEntregaCodigoHash() != null && !expiradoCodigo(pedido)))) return new Inicio(null, null);
        if (!gpsAtivo && codigoAtivo && pedido.getEntregaCodigoHash() != null && !expiradoCodigo(pedido)) return new Inicio(null, null);
        LocalDateTime agora = LocalDateTime.now();
        String token = null;
        boolean tokenNovo = false;
        if (gpsAtivo) {
            if (pedido.getRastreamentoTokenHash() != null && !expirado(pedido))
                pedido.setRastreamentoTokenAnteriorHash(pedido.getRastreamentoTokenHash());
            token = randomToken();
            pedido.setRastreamentoTokenHash(hash(token));
            pedido.setRastreamentoExpiraEm(agora.plusHours(12));
            tokenNovo = true;
            pedido.setRastreamentoConsentidoEm(agora);
            pedido.setEntregadorLatitude(null);
            pedido.setEntregadorLongitude(null);
            pedido.setEntregadorLocalizacaoEm(null);
        }
        String codigo = null;
        if (Boolean.TRUE.equals(loja.getEntregaCodigoConfirmacaoAtivo())) {
            codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
            pedido.setEntregaCodigoHash(hash(codigo));
            pedido.setEntregaCodigoExpiraEm(agora.plusHours(12));
            pedido.setEntregaCodigoTentativas(0);
            pedido.setEntregaCodigoConfirmadoEm(null);
        } else {
            limparCodigo(pedido);
        }
        pedido.setRastreamentoNotificacaoTentadaEm(agora); // idempotência: uma tentativa por início de rota.
        pedidos.save(pedido);
        auditoria(loja.getId(), pedido.getId(), pedido.getEntregador().getId(), "ROTA_INICIADA", null);
        if (whatsappBot != null && pedido.getCliente() != null) {
            String link = tokenNovo && token != null ? "https://mydeliveryfood.com.br/acompanhar.html?token=" + token : null;
            whatsappBot.notificarRastreamentoAsync(loja, pedido.getId(), pedido.getCliente().getTelefone(), link, codigo);
        }
        return new Inicio(token, codigo);
    }

    @Transactional
    public void atualizarPosicao(Pedido pedido, Long entregadorId, double lat, double lng, Double precisao, long capturadoEmMs) {
        verificarCoordenadas(lat, lng);
        if (precisao == null || !Double.isFinite(precisao) || precisao < 0 || precisao > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Precisão do GPS insuficiente");
        long idadeMs = System.currentTimeMillis() - capturadoEmMs;
        if (idadeMs > 60_000 || idadeMs < -10_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Posição GPS desatualizada");
        Restaurante r = pedido.getRestaurante();
        if (!Boolean.TRUE.equals(r.getRastreamentoEntregaAtivo()) || pedido.getStatus() != Pedido.Status.SAIU_ENTREGA
                || pedido.getEntregador() == null || !pedido.getEntregador().getId().equals(entregadorId)
                || pedido.getRastreamentoConsentidoEm() == null || expirado(pedido))
            throw new ResponseStatusException(HttpStatus.GONE, "Rastreamento indisponível para esta entrega");
        LocalDateTime agora = LocalDateTime.now();
        if (pedido.getEntregadorLocalizacaoEm() != null && pedido.getEntregadorLocalizacaoEm().isAfter(agora.minusSeconds(5)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Aguarde antes de enviar outra posição");
        pedido.setEntregadorLatitude(BigDecimal.valueOf(lat));
        pedido.setEntregadorLongitude(BigDecimal.valueOf(lng));
        pedido.setEntregadorLocalizacaoEm(agora);
        pedidos.save(pedido);
    }

    @Transactional
    public boolean confirmarCodigo(Pedido pedido, Long entregadorId, String codigo) {
        if (pedido.getStatus() != Pedido.Status.SAIU_ENTREGA || pedido.getEntregador() == null
                || !pedido.getEntregador().getId().equals(entregadorId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pedido não atribuído a este entregador");
        if (pedido.getEntregaCodigoHash() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmação por código desativada");
        if (!Boolean.TRUE.equals(pedido.getRestaurante().getEntregaCodigoConfirmacaoAtivo())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmação por código desativada");
        if (pedido.getEntregaCodigoConfirmadoEm() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Código já utilizado");
        if (expiradoCodigo(pedido) || pedido.getEntregaCodigoTentativas() >= MAX_TENTATIVAS)
            throw new ResponseStatusException(HttpStatus.GONE, "Código expirado ou limite de tentativas atingido");
        if (codigo == null || !MessageDigest.isEqual(hash(codigo.trim()).getBytes(StandardCharsets.US_ASCII), pedido.getEntregaCodigoHash().getBytes(StandardCharsets.US_ASCII))) {
            pedido.setEntregaCodigoTentativas(pedido.getEntregaCodigoTentativas() + 1);
            pedidos.save(pedido);
            auditoria(pedido.getRestaurante().getId(), pedido.getId(), entregadorId, "CODIGO_INVALIDO", null);
            return false;
        }
        pedido.setEntregaCodigoConfirmadoEm(LocalDateTime.now());
        pedidos.save(pedido);
        auditoria(pedido.getRestaurante().getId(), pedido.getId(), entregadorId, "CODIGO_CONFIRMADO", null);
        return true;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consultar(String token) {
        if (token == null || token.length() < 32 || token.length() > 100)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Acompanhamento indisponível");
        Pedido p = pedidos.findByRastreamentoToken(hash(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Acompanhamento indisponível"));
        if (expirado(p)) throw new ResponseStatusException(HttpStatus.GONE, "Link de acompanhamento expirado");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("pedido", p.getId()); out.put("status", p.getStatus()); out.put("loja", p.getRestaurante().getNome());
        out.put("lojaLatitude", p.getRestaurante().getEnderecoLatitude()); out.put("lojaLongitude", p.getRestaurante().getEnderecoLongitude());
        out.put("destinoLatitude", p.getDestinoLatitude()); out.put("destinoLongitude", p.getDestinoLongitude());
        boolean ativo = Boolean.TRUE.equals(p.getRestaurante().getRastreamentoEntregaAtivo())
                && p.getStatus() == Pedido.Status.SAIU_ENTREGA && !expirado(p);
        out.put("rastreamentoAtivo", ativo);
        out.put("entregadorLatitude", ativo ? p.getEntregadorLatitude() : null);
        out.put("entregadorLongitude", ativo ? p.getEntregadorLongitude() : null);
        out.put("ultimaLocalizacao", ativo ? p.getEntregadorLocalizacaoEm() : null);
        out.put("destino", p.getEnderecoEntrega());
        out.put("distanciaLinhaRetaKm", ativo && p.getEntregadorLatitude() != null && p.getDestinoLatitude() != null
                ? distanciaKm(p.getEntregadorLatitude().doubleValue(),p.getEntregadorLongitude().doubleValue(),p.getDestinoLatitude().doubleValue(),p.getDestinoLongitude().doubleValue()) : null);
        out.put("codigoConfirmacaoAtivo", Boolean.TRUE.equals(p.getRestaurante().getEntregaCodigoConfirmacaoAtivo()));
        return out;
    }

    @Transactional
    public void encerrar(Pedido pedido, String evento, String motivo) {
        pedido.setEntregadorLatitude(null); pedido.setEntregadorLongitude(null); pedido.setEntregadorLocalizacaoEm(null);
        pedido.setRastreamentoConsentidoEm(null); pedido.setRastreamentoExpiraEm(LocalDateTime.now().plusHours(2));
        limparCodigo(pedido); pedidos.save(pedido);
        auditoria(pedido.getRestaurante().getId(), pedido.getId(), pedido.getEntregador() == null ? null : pedido.getEntregador().getId(), evento, motivo);
    }

    public void revogarPorDesativacao(Restaurante loja) {
        if (Boolean.TRUE.equals(loja.getRastreamentoEntregaAtivo())) return;
        pedidos.findRastreamentosAtivosPorRestaurante(loja.getId())
                .forEach(p -> {
                    p.setEntregadorLatitude(null); p.setEntregadorLongitude(null); p.setEntregadorLocalizacaoEm(null);
                    p.setRastreamentoConsentidoEm(null); p.setRastreamentoExpiraEm(LocalDateTime.now().plusHours(2));
                    pedidos.save(p);
                    auditoria(loja.getId(), p.getId(), p.getEntregador() == null ? null : p.getEntregador().getId(), "RASTREAMENTO_DESATIVADO", null);
                });
    }

    @Transactional
    public void registrarOverride(Pedido p, String motivo, String administrador) {
        p.setEntregaCodigoConfirmadoEm(LocalDateTime.now());
        pedidos.save(p);
        jdbc.update("INSERT INTO entrega_rastreamento_auditoria (restaurante_id,pedido_id,entregador_id,evento,motivo,ator) VALUES (?,?,?,?,?,?)",
                p.getRestaurante().getId(), p.getId(), null, "ENTREGA_ADMIN_OVERRIDE", motivo, administrador);
    }

    private void auditoria(Long loja, Long pedido, Long entregador, String evento, String motivo) {
        jdbc.update("INSERT INTO entrega_rastreamento_auditoria (restaurante_id,pedido_id,entregador_id,evento,motivo,ator) VALUES (?,?,?,?,?,?)",
                loja, pedido, entregador, evento, motivo, entregador == null ? null : "entregador:" + entregador);
    }
    private static void limparCodigo(Pedido p) { p.setEntregaCodigoHash(null); p.setEntregaCodigoExpiraEm(null); p.setEntregaCodigoTentativas(0); }
    private static boolean expirado(Pedido p) { return p.getRastreamentoExpiraEm() == null || p.getRastreamentoExpiraEm().isBefore(LocalDateTime.now()); }
    private static boolean expiradoCodigo(Pedido p) { return p.getEntregaCodigoExpiraEm() == null || p.getEntregaCodigoExpiraEm().isBefore(LocalDateTime.now()); }
    private static String randomToken() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String hash(String s) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static void verificarCoordenadas(double lat,double lng) { EntregaService.validarCoordenadas(lat,lng); }
    private static double distanciaKm(double a,double b,double c,double d) { double r=6371, x=Math.toRadians(c-a),y=Math.toRadians(d-b); double h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(y/2)*Math.sin(y/2); return 2*r*Math.asin(Math.sqrt(h)); }
}
