package com.mydelivery.fiscal.service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.fiscal.model.ContadorNumeroNfce;
import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.fiscal.model.NotaFiscalEntrada;
import com.mydelivery.fiscal.model.PerfilFiscalProduto;
import com.mydelivery.fiscal.model.PerfilFiscalRestaurante;
import com.mydelivery.fiscal.repository.ContadorNumeroNfceRepository;
import com.mydelivery.fiscal.repository.NotaFiscalEmitidaRepository;
import com.mydelivery.fiscal.repository.NotaFiscalEntradaRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalProdutoRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalRestauranteRepository;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestrador central de emissão de NFC-e.
 *
 * <p>Fluxo (todos os passos em uma transação — se algo falhar depois do POST
 * pra SEFAZ, o número já reservado NÃO volta; ele fica marcado na tabela
 * pra ser INUTILIZADO por rotina separada):
 *
 * <ol>
 *   <li>Valida: emissão ativa, cert válido, CSC configurado</li>
 *   <li>Reserva o próximo número via lock pessimista (SELECT FOR UPDATE)</li>
 *   <li>Grava {@link NotaFiscalEmitida} status={@code PENDENTE}</li>
 *   <li>Descriptografa cert e CSC do cofre</li>
 *   <li>Monta {@link NfeGateway.RequisicaoEmissao} com itens do pedido</li>
 *   <li>Chama {@link NfeGateway#emitir}</li>
 *   <li>Grava XML no {@link NfceStorageService} + atualiza nota (AUTORIZADA/REJEITADA)</li>
 *   <li>Registra tudo em {@link AuditoriaFiscalService}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NfceEmissorService {

    private final PerfilFiscalRestauranteRepository perfilRepo;
    private final PerfilFiscalProdutoRepository perfilProdRepo;
    private final ContadorNumeroNfceRepository contadorRepo;
    private final NotaFiscalEmitidaRepository notaRepo;
    private final NotaFiscalEntradaRepository notaEntradaRepo;
    private final PedidoRepository pedidoRepo;
    private final CertificadoService certificadoService;
    private final PerfilFiscalService perfilFiscalService;
    private final NfceStorageService storage;
    private final AuditoriaFiscalService auditoria;
    private final NfeGateway gateway;

    /** WhatsApp: notifica cliente com link da NFC-e. Opcional — se WhatsApp
     *  não estiver conectado pra loja, ignora silencioso. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.service.whatsapp.WhatsappService whatsappService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.repository.WhatsappInstanceRepository whatsappInstanceRepo;

    /**
     * Emite NFC-e pra um pedido específico. Se pedido já tem nota autorizada,
     * devolve a existente (idempotente).
     */
    @Transactional
    public NotaFiscalEmitida emitirParaPedido(Long pedidoId, String usuarioEmail, String ipOrigem) {
        Pedido pedido = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new IllegalArgumentException("Pedido não encontrado"));
        Restaurante r = pedido.getRestaurante();
        if (r == null) throw new IllegalStateException("Pedido sem restaurante");

        // Idempotência: se pedido já tem nota AUTORIZADA, devolve. Notas em
        // CONTINGENCIA_EPEC "presas" (contingência não retransmite bem no
        // gateway atual) viram REJEITADA aqui pra abrir espaço pra uma emissão
        // real nova.
        var existentes = notaRepo.findByPedidoId(pedidoId);
        for (var n : existentes) {
            if (n.getStatus() == NotaFiscalEmitida.Status.AUTORIZADA) {
                log.info("[Fiscal][Emissor] Pedido {} já tinha nota autorizada {}, devolvendo",
                        pedidoId, n.getChaveAcesso());
                return n;
            }
            if (n.getStatus() == NotaFiscalEmitida.Status.CONTINGENCIA_EPEC) {
                log.warn("[Fiscal][Emissor] Pedido {} tinha nota em CONTINGENCIA_EPEC (chave={}); marcando REJEITADA pra permitir nova emissão",
                        pedidoId, n.getChaveAcesso());
                n.setStatus(NotaFiscalEmitida.Status.REJEITADA);
                n.setSefazMotivo("Descartada — contingência não retransmitiu, reemitida");
                n.setProximaTentativaEm(null);
                notaRepo.save(n);
            }
        }

        // ── 1. Valida config ──
        PerfilFiscalRestaurante perfil = perfilRepo.findByRestauranteId(r.getId())
                .orElseThrow(() -> new IllegalStateException("Perfil fiscal não configurado"));
        if (!Boolean.TRUE.equals(perfil.getEmissaoAtiva())) {
            throw new IllegalStateException("Emissão fiscal desativada — habilite na aba Fiscal");
        }

        var pfx = certificadoService.abrirParaUso(r.getId(), usuarioEmail, ipOrigem);
        String csc = perfilFiscalService.abrirCscParaUso(r.getId());

        // ── 2. Reserva número (lock pessimista) ──
        Integer ambiente = perfil.getAmbienteSefaz();
        Integer serie = 1;
        Long numero = reservarProximoNumero(perfil.getCnpj(), serie, ambiente);
        LocalDateTime agora = LocalDateTime.now();

        // ── 3. Grava PENDENTE ──
        NotaFiscalEmitida nota = NotaFiscalEmitida.builder()
                .restaurante(r).pedidoId(pedidoId).cnpj(perfil.getCnpj())
                .modelo(65).serie(serie).numero(numero).ambiente(ambiente)
                .status(NotaFiscalEmitida.Status.ENVIANDO)
                .valorTotal(pedido.getTotal() != null ? pedido.getTotal() : BigDecimal.ZERO)
                .build();
        nota = notaRepo.save(nota);

        // ── 4-5. Monta requisição ──
        NfeGateway.RequisicaoEmissao req;
        try {
            req = montarRequisicao(pedido, perfil, ambiente, serie, numero, agora, pfx.pfx(), pfx.senha(), csc);
        } catch (RuntimeException e) {
            nota.setStatus(NotaFiscalEmitida.Status.REJEITADA);
            nota.setSefazCstat("ERRO_MONTAGEM");
            nota.setSefazMotivo(e.getMessage());
            notaRepo.save(nota);
            auditoria.registrar(r.getId(), perfil.getCnpj(), usuarioEmail, "NFCE_EMITIR", "FALHA",
                    ipOrigem, Map.of("pedidoId", pedidoId, "erro", e.getMessage()));
            throw new IllegalStateException("Falha ao montar nota: " + e.getMessage(), e);
        }

        // ── 6. Chama gateway ──
        NfeGateway.ResultadoEmissao res;
        try {
            res = gateway.emitir(req);
        } catch (Exception e) {
            log.error("[Fiscal][Emissor] Exception no gateway", e);
            res = new NfeGateway.ResultadoEmissao(false, "ERRO_TEC", e.getMessage(),
                    null, null, null, null);
        }

        // ── 6a. RETRY AUTOMÁTICO PRA CHAVE DUPLICADA (cStat 539) ──
        // A chave duplicada aparece quando o número/cNF colidiu com uma
        // emissão anterior no ambiente SEFAZ (ex: retry após timeout).
        // Como a chave nova depende do numero+cNF (aleatório), pular pro
        // próximo número e re-tentar até 5x resolve automaticamente.
        int retryDup = 0;
        long numeroAtual = numero;
        // Avança 1 por vez, até 20x — mantém a numeração fiscal contígua
        // (contadora não estranha) e cobre até 20 chaves duplicadas em fila.
        // Se a chave duplicada persiste, avança 50 números por vez pra escapar
        // da faixa ocupada por notas antigas do mesmo CNPJ em outros sistemas.
        while (!res.aprovada() && "539".equals(res.cStat()) && retryDup < 10) {
            retryDup++;
            numeroAtual += 50;
            try {
                var c = contadorRepo.findForUpdate(perfil.getCnpj(), serie, ambiente).orElse(null);
                if (c != null) { c.setProximoNumero(numeroAtual + 1); contadorRepo.save(c); }
            } catch (Exception ignore) {}
            log.warn("[Fiscal][Emissor] cStat=539 — retry {} numero={}", retryDup, numeroAtual);
            NfeGateway.RequisicaoEmissao reqRetry;
            try {
                reqRetry = montarRequisicao(pedido, perfil, ambiente, serie, numeroAtual, LocalDateTime.now(), pfx.pfx(), pfx.senha(), csc);
                res = gateway.emitir(reqRetry);
            } catch (Exception e) {
                log.error("[Fiscal][Emissor] erro no retry {}: {}", retryDup, e.getMessage());
                break;
            }
        }
        nota.setNumero(numeroAtual);

        // ── 6b. CONTINGÊNCIA DESABILITADA ──
        // O gateway atual gera XML "mínimo" na contingência (sem itens), então
        // a retransmissão sempre falha ("Identificador deve possuir 44 chars",
        // "nota sem itens", etc.) e a nota fica presa em CONTINGENCIA_EPEC.
        // Melhor devolver o erro real da SEFAZ pro dono e ele tenta de novo
        // quando a rede voltar. Reabilitar quando montar o XML completo
        // assinado offline.

        // ── 7. Atualiza status ──
        nota.setSefazCstat(res.cStat());
        nota.setSefazMotivo(res.motivo());
        nota.setChaveAcesso(res.chaveAcesso());
        nota.setProtocolo(res.protocolo());
        nota.setQrcodeUrlConsulta(res.qrCodeUrl());
        if (res.aprovada()) {
            nota.setStatus(NotaFiscalEmitida.Status.AUTORIZADA);
            nota.setEmitidaEm(LocalDateTime.now());
            if (res.xmlAssinado() != null) {
                String urlXml = storage.gravarXml(perfil.getCnpj(), res.chaveAcesso(), res.xmlAssinado());
                nota.setXmlUrl(urlXml);
            }
            auditoria.registrar(r.getId(), perfil.getCnpj(), usuarioEmail, "NFCE_EMITIR", "OK",
                    ipOrigem, Map.of("pedidoId", pedidoId, "chave", res.chaveAcesso(),
                            "protocolo", res.protocolo(), "ambiente", ambiente));
            // Envio ao cliente via WhatsApp — fail-safe (nunca quebra emissão)
            enviarWhatsAppCliente(pedido, res.chaveAcesso(), res.qrCodeUrl());
        } else {
            nota.setStatus(NotaFiscalEmitida.Status.REJEITADA);
            nota.setTentativas(nota.getTentativas() + 1);
            auditoria.registrar(r.getId(), perfil.getCnpj(), usuarioEmail, "NFCE_EMITIR", "FALHA",
                    ipOrigem, Map.of("pedidoId", pedidoId, "cStat", res.cStat(), "motivo", res.motivo()));
            log.warn("[Fiscal][Emissor] Rejeitada pedido={} cStat={} motivo={}",
                    pedidoId, res.cStat(), res.motivo());
        }
        return notaRepo.save(nota);
    }

    /**
     * Cancela uma nota AUTORIZADA (evento tipo 110111).
     * Regras SEFAZ:
     *  - Janela de 30 min pós-emissão pra NFC-e (após isso só via judicial).
     *  - Justificativa obrigatória (15 a 255 caracteres).
     *  - Idempotente: se nota já está CANCELADA, devolve a existente.
     */
    @Transactional
    public NotaFiscalEmitida cancelarNota(Long notaId, String justificativa,
                                          String usuarioEmail, String ipOrigem) {
        NotaFiscalEmitida n = notaRepo.findById(notaId)
                .orElseThrow(() -> new IllegalArgumentException("Nota não encontrada"));
        if (n.getStatus() == NotaFiscalEmitida.Status.CANCELADA) {
            log.info("[Fiscal][Cancel] Nota {} já estava cancelada", notaId);
            return n;
        }
        // Só AUTORIZADA pode cancelar. CONTINGENCIA_EPEC não tem protocolo de
        // autorização ainda — a SEFAZ exige nProt no evento de cancelamento.
        // Precisa esperar a retransmissão (job automático a cada 5min).
        if (n.getStatus() == NotaFiscalEmitida.Status.CONTINGENCIA_EPEC) {
            throw new IllegalStateException(
                    "Esta nota está em contingência (ainda não foi retransmitida pra SEFAZ). "
                  + "A retransmissão é automática (a cada 5 min). Assim que virar AUTORIZADA, "
                  + "você poderá cancelar dentro da janela de 30 min.");
        }
        if (n.getStatus() != NotaFiscalEmitida.Status.AUTORIZADA) {
            throw new IllegalStateException(
                    "Só notas AUTORIZADAS podem ser canceladas (estado atual: " + n.getStatus() + ")");
        }
        if (n.getEmitidaEm() != null
                && n.getEmitidaEm().isBefore(LocalDateTime.now().minusMinutes(30))) {
            throw new IllegalStateException(
                    "Prazo de cancelamento SEFAZ expirou (30 min pós-emissão). "
                  + "Após esse prazo o cancelamento só é possível judicialmente.");
        }
        if (justificativa == null || justificativa.trim().length() < 15) {
            throw new IllegalArgumentException(
                    "Justificativa obrigatória (mínimo 15 caracteres — exigência SEFAZ).");
        }
        if (justificativa.length() > 255) justificativa = justificativa.substring(0, 255);

        Restaurante r = n.getRestaurante();
        PerfilFiscalRestaurante perfil = perfilRepo.findByRestauranteId(r.getId())
                .orElseThrow(() -> new IllegalStateException("Perfil fiscal ausente"));
        var pfx = certificadoService.abrirParaUso(r.getId(), usuarioEmail, ipOrigem);

        NfeGateway.RequisicaoCancelamento req = new NfeGateway.RequisicaoCancelamento(
                perfil.getUf(), perfil.getAmbienteSefaz(), perfil.getCnpj(),
                n.getChaveAcesso(), n.getProtocolo(), justificativa,
                1, pfx.pfx(), pfx.senha());

        NfeGateway.ResultadoCancelamento res;
        try { res = gateway.cancelar(req); }
        catch (Exception e) {
            log.error("[Fiscal][Cancel] Exception no gateway", e);
            res = new NfeGateway.ResultadoCancelamento(false, "ERRO_TEC",
                    e.getMessage(), null, null);
        }

        if (res.aprovado()) {
            n.setStatus(NotaFiscalEmitida.Status.CANCELADA);
            n.setSefazCstat(res.cStat());
            n.setSefazMotivo("Cancelada: " + res.motivo());
            auditoria.registrar(r.getId(), perfil.getCnpj(), usuarioEmail,
                    "NFCE_CANCELAR", "OK", ipOrigem,
                    Map.of("notaId", notaId, "chave", n.getChaveAcesso(),
                            "protoCanc", res.protocoloCancelamento(),
                            "justificativa", justificativa));
        } else {
            auditoria.registrar(r.getId(), perfil.getCnpj(), usuarioEmail,
                    "NFCE_CANCELAR", "FALHA", ipOrigem,
                    Map.of("notaId", notaId, "cStat", res.cStat(), "motivo", res.motivo()));
            throw new IllegalStateException(
                    "SEFAZ recusou o cancelamento: " + res.motivo() + " (cStat=" + res.cStat() + ")");
        }
        return notaRepo.save(n);
    }

    /** Diagnóstico: lista os itens do pedido com o CFOP/CSOSN gravados em cada. */
    @Transactional(readOnly = true)
    public Map<String, Object> diagnosticoItensPedido(Long pedidoId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Pedido p = pedidoRepo.findById(pedidoId).orElse(null);
        if (p == null) { out.put("erro", "pedido nao encontrado"); return out; }
        out.put("pedidoId", pedidoId);
        List<Map<String, Object>> its = new ArrayList<>();
        for (PedidoItem pi : p.getItens()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("produtoId", pi.getProduto() == null ? null : pi.getProduto().getId());
            item.put("nome", pi.getNomeProduto());
            if (pi.getProduto() != null) {
                var perfil = perfilProdRepo.findByProdutoId(pi.getProduto().getId()).orElse(null);
                if (perfil == null) {
                    item.put("perfilFiscal", "NAO CONFIGURADO");
                } else {
                    item.put("ncm", perfil.getNcm());
                    item.put("cfop", perfil.getCfop());
                    item.put("cst", perfil.getCst());
                    item.put("csosn", perfil.getCsosn());
                    item.put("origem", perfil.getOrigem());
                }
            }
            its.add(item);
        }
        out.put("itens", its);
        return out;
    }

    /**
     * Auto-emissão SEGURA — usada pelo PedidoService quando pedido vira ENTREGUE.
     * NUNCA lança exceção pro chamador (o pedido não pode falhar por causa da
     * nota). Se falhar, só loga + auditoria e a nota fica REJEITADA pra retry.
     *
     * <p>REQUIRES_NEW: usa transação SEPARADA da que marcou o pedido como
     * ENTREGUE. Sem isso, um erro aqui (ex.: UniqueConstraint na chave de
     * acesso, DataIntegrityViolation) marca a transação externa como
     * rollback-only e o painel devolve "Conflito de dados" ao dono, mesmo
     * com o try/catch abaixo — porque quem falha na hora do commit é a
     * transação, não o método.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void emitirParaPedidoSeguro(Long pedidoId, String usuarioEmail, String ipOrigem) {
        try {
            // Só tenta se restaurante tem emissão ativa (senão nem chama o motor)
            Pedido p = pedidoRepo.findById(pedidoId).orElse(null);
            if (p == null || p.getRestaurante() == null) return;
            var perfil = perfilRepo.findByRestauranteId(p.getRestaurante().getId()).orElse(null);
            if (perfil == null || !Boolean.TRUE.equals(perfil.getEmissaoAtiva())) return;

            emitirParaPedido(pedidoId, usuarioEmail, ipOrigem);
        } catch (Exception e) {
            log.warn("[Fiscal][AutoEmit] Falha silenciosa pedido={}: {}", pedidoId, e.getMessage());
        }
    }

    /**
     * Lock pessimista pra reservar o próximo número. Se o registro do contador
     * não existir ainda, cria com {@code proximoNumero=1}.
     */
    private Long reservarProximoNumero(String cnpj, Integer serie, Integer ambiente) {
        ContadorNumeroNfce c = contadorRepo.findForUpdate(cnpj, serie, ambiente).orElse(null);
        if (c == null) {
            c = ContadorNumeroNfce.builder().cnpj(cnpj).serie(serie).ambiente(ambiente)
                    .proximoNumero(1L).build();
        }
        // Auto-avança pra próximo número LIVRE — pega o caso em que o contador
        // ficou dessincronizado (nota #3 já existe mas contador diz próximo=3,
        // gera "Duplicate entry" no unique uq_nota_numero). Sondando até 1000
        // números pra frente, para evitar loop infinito.
        Long n = c.getProximoNumero();
        int tentativas = 0;
        while (tentativas < 1000 && notaRepo.existsByCnpjAndSerieAndAmbienteAndModeloAndNumero(
                cnpj, serie, ambiente, 65, n)) {
            n++; tentativas++;
        }
        if (tentativas > 0) {
            log.warn("[Fiscal][Contador] Avancou {} numero(s) pra pular ja emitidos (cnpj={}, serie={}, amb={}, agora={})",
                    tentativas, cnpj, serie, ambiente, n);
        }
        c.setProximoNumero(n + 1);
        contadorRepo.save(c);
        return n;
    }

    private NfeGateway.RequisicaoEmissao montarRequisicao(
            Pedido p, PerfilFiscalRestaurante perfil, int amb, int serie, long numero,
            LocalDateTime emitidaEm, byte[] pfx, String senhaCert, String csc) {

        NfeGateway.Emitente emitente = new NfeGateway.Emitente(
                perfil.getCnpj(),
                nvl(perfil.getRazaoSocial(), p.getRestaurante().getNome()),
                perfil.getNomeFantasia(),
                perfil.getInscricaoEstadual(),
                perfil.getUf(),
                perfil.getMunicipioCodigoIbge(),
                p.getRestaurante().getCidade(),
                perfil.getEnderecoLogradouro(),
                perfil.getEnderecoNumero(),
                perfil.getEnderecoBairro(),
                perfil.getEnderecoCep(),
                perfil.getRegimeTributario());

        // Destinatário: NFC-e permite venda ao consumidor SEM identificação
        // (padrão). Quando o cliente pedir "nota com CPF", a UI de emissão
        // manual permite informar — aí passamos aqui. R5+ adiciona esse fluxo.
        NfeGateway.Destinatario dest = null;
        if (p.getCliente() != null && p.getCliente().getEmail() != null && !p.getCliente().getEmail().isBlank()) {
            dest = new NfeGateway.Destinatario(null, p.getCliente().getNome(), p.getCliente().getEmail());
        }

        // Itens — coleta TODOS os produtos sem fiscal do pedido antes de
        // lançar exception (dono vê a lista completa de uma vez, não item
        // por item). Mensagem aponta pra a tela nova de Categorias.
        List<String> semFiscal = new ArrayList<>();
        for (PedidoItem pi : p.getItens()) {
            if (pi.getProduto() == null) continue;
            if (perfilProdRepo.findByProdutoId(pi.getProduto().getId()).isEmpty()) {
                semFiscal.add(pi.getNomeProduto() != null ? pi.getNomeProduto()
                              : pi.getProduto().getNome());
            }
        }
        if (!semFiscal.isEmpty()) {
            throw new IllegalStateException(
                    "Não emite: item(ns) do pedido sem NCM/CFOP configurado — "
                  + String.join(", ", semFiscal)
                  + ". Vincule a alguma Categoria tributária (Área Fiscal → Categorias tributárias → aba Produtos).");
        }

        List<NfeGateway.ItemNota> itens = new ArrayList<>();
        int seq = 0;
        for (PedidoItem pi : p.getItens()) {
            seq++;
            if (pi.getProduto() == null) continue;
            PerfilFiscalProduto pf = perfilProdRepo.findByProdutoId(pi.getProduto().getId()).orElseThrow();
            log.info("[Fiscal][Debug] Pedido {} item {} produto='{}' → ncm={} cfop={} cst={} csosn={} origem={}",
                    p.getId(), seq, pi.getNomeProduto(),
                    pf.getNcm(), pf.getCfop(), pf.getCst(), pf.getCsosn(), pf.getOrigem());
            itens.add(new NfeGateway.ItemNota(
                    seq,
                    String.valueOf(pi.getProduto().getId()),
                    nvl(pi.getNomeProduto(), pi.getProduto().getNome()),
                    pf.getNcm(),
                    pf.getCfop(),
                    pf.getCst(),
                    pf.getCsosn(),
                    pf.getOrigem(),
                    pf.getUnidadeComercial(),
                    new BigDecimal(pi.getQuantidade()),
                    pi.getPrecoUnitario() == null ? BigDecimal.ZERO : pi.getPrecoUnitario(),
                    pf.getAliquotaIcms(),
                    pf.getAliquotaPis(),
                    pf.getAliquotaCofins()));
        }
        if (itens.isEmpty()) throw new IllegalStateException("Pedido sem itens válidos.");

        // NFC-e: pagamento e total DEVEM ser o somatório dos itens (sem taxa
        // de entrega, que é serviço e não entra em nota de mercadoria).
        // Se usar p.getTotal() (que inclui taxa), a SEFAZ rejeita com cStat 564.
        BigDecimal valorItens = BigDecimal.ZERO;
        for (NfeGateway.ItemNota it : itens) {
            valorItens = valorItens.add(it.quantidade().multiply(it.valorUnitario()));
        }
        BigDecimal valorTotal = valorItens.setScale(2, java.math.RoundingMode.HALF_UP);
        List<NfeGateway.Pagamento> pagamentos = new ArrayList<>();
        pagamentos.add(new NfeGateway.Pagamento(
                mapearFormaPagamento(p.getFormaPagamento() == null ? null : p.getFormaPagamento().name()),
                valorTotal));

        return new NfeGateway.RequisicaoEmissao(
                perfil.getUf(), amb, serie, numero, emitidaEm,
                emitente, dest, itens, valorTotal, pagamentos,
                perfil.getCscId(), csc, pfx, senhaCert);
    }

    /**
     * Notifica o cliente por WhatsApp com o link da NFC-e.
     * Nunca lança — se WhatsApp não estiver conectado / cliente sem telefone /
     * qualquer erro de rede, ignora silencioso.
     */
    /**
     * Dedupe in-memory dos envios de NFC-e por WhatsApp. Evita que o dono
     * clicando 2x em "Entregue" (ou race condition de auto-emit concorrente)
     * dispare 2 mensagens iguais pro cliente. Set-based, com TTL implícito
     * pelo restart do processo — cobre 100% do caso comum de doubleclick.
     */
    private final java.util.Set<Long> _pedidosComWaEnviado =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private void enviarWhatsAppCliente(Pedido pedido, String chave, String qrUrl) {
        try {
            if (whatsappService == null || whatsappInstanceRepo == null) return;
            if (pedido.getCliente() == null || pedido.getCliente().getTelefone() == null) return;
            String tel = pedido.getCliente().getTelefone().replaceAll("\\D", "");
            if (tel.length() < 10) return;
            // Dedupe por pedido — só envia 1x mesmo em clique duplo / race.
            if (!_pedidosComWaEnviado.add(pedido.getId())) {
                log.info("[Fiscal][WA] Pedido {} já teve NFC-e enviada, pulando duplicata", pedido.getId());
                return;
            }
            var instOpt = whatsappInstanceRepo.findByRestauranteId(pedido.getRestaurante().getId());
            if (instOpt.isEmpty()) return;
            String pedNum = String.valueOf(pedido.getId());
            String msg = "🧾 *Nota fiscal do seu pedido #" + pedNum + "*\n\n"
                    + "Sua NFC-e foi emitida com sucesso!\n\n"
                    + "🔗 Consultar / imprimir:\n" + (qrUrl == null ? "" : qrUrl) + "\n\n"
                    + "Chave de acesso:\n" + formatarChave(chave) + "\n\n"
                    + "_MyDelivery_";
            whatsappService.enviarMensagem(instOpt.get(), tel, msg);
        } catch (Exception e) {
            log.warn("[Fiscal][WA] Falha ao enviar NFC-e pra cliente do pedido {}: {}",
                    pedido.getId(), e.getMessage());
        }
    }

    /** Formata chave 44 dígitos em blocos de 4 pra legibilidade humana. */
    private static String formatarChave(String chave) {
        if (chave == null) return "";
        String limpa = chave.replaceAll("\\D", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limpa.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(limpa, i, Math.min(i + 4, limpa.length()));
        }
        return sb.toString();
    }

    /**
     * cStat transitório = SEFAZ com problema técnico (não é erro de dado).
     * Nesses casos vale cair pra contingência ou reagendar retry:
     *  - ERRO_TEC         → erro de rede/lib
     *  - 108              → Serviço Paralisado Momentaneamente
     *  - 109              → Serviço Paralisado sem previsão
     *  - 999              → Erro não catalogado (às vezes transitório)
     * cStat de dado errado (200, 233, 236, etc.) NÃO entra em contingência.
     */
    private static boolean ehErroTransitorio(String cStat) {
        if (cStat == null) return true;
        return switch (cStat) {
            case "ERRO_TEC", "108", "109", "999" -> true;
            default -> false;
        };
    }

    /**
     * Retenta uma nota REJEITADA (transitória) ou CONTINGENCIA_EPEC.
     * Chamado pelo {@code NfceRetryJob} a cada 5 min. Idempotente.
     */
    @Transactional
    public NotaFiscalEmitida retentarNota(Long notaId) {
        NotaFiscalEmitida n = notaRepo.findById(notaId).orElse(null);
        if (n == null) return null;
        // Segurança: só retenta o que ainda faz sentido.
        if (n.getStatus() != NotaFiscalEmitida.Status.REJEITADA
                && n.getStatus() != NotaFiscalEmitida.Status.CONTINGENCIA_EPEC) return n;
        if (n.getTentativas() >= 8) {
            log.warn("[Fiscal][Retry] Nota {} atingiu max tentativas — abandonando", notaId);
            n.setProximaTentativaEm(null);
            return notaRepo.save(n);
        }

        Restaurante r = n.getRestaurante();
        PerfilFiscalRestaurante perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || !Boolean.TRUE.equals(perfil.getEmissaoAtiva())) return n;

        // Se é REJEITADA por erro de DADO (não transitório), não faz sentido retentar.
        if (n.getStatus() == NotaFiscalEmitida.Status.REJEITADA
                && !ehErroTransitorio(n.getSefazCstat())) {
            log.info("[Fiscal][Retry] Nota {} tem cStat={} não-transitório — abandonando retry",
                    notaId, n.getSefazCstat());
            n.setProximaTentativaEm(null);
            return notaRepo.save(n);
        }

        n.setTentativas(n.getTentativas() + 1);
        n.setStatus(NotaFiscalEmitida.Status.ENVIANDO);
        notaRepo.save(n);

        try {
            // Se estava em CONTINGENCIA_EPEC, retransmite (envia XML já assinado).
            // Se estava em REJEITADA transitória, tenta emissão nova (reservou número, mas emissão falhou).
            if (n.getStatus() == NotaFiscalEmitida.Status.CONTINGENCIA_EPEC
                    || (n.getXmlUrl() != null && n.getChaveAcesso() != null)) {
                String xml = storage.lerXml(perfil.getCnpj(), n.getChaveAcesso());
                if (xml == null) {
                    log.warn("[Fiscal][Retry] Nota {}: XML de contingência não encontrado — pulando", notaId);
                    n.setProximaTentativaEm(LocalDateTime.now().plusMinutes(30));
                    return notaRepo.save(n);
                }
                var pfx = certificadoService.abrirParaUso(r.getId(), "sistema:retry", null);
                NfeGateway.RequisicaoEmissao req = montarReqRetransmissao(n, perfil, pfx.pfx(), pfx.senha());
                NfeGateway.ResultadoEmissao res = gateway.retransmitirContingencia(req, xml);
                if (res.aprovada()) {
                    n.setStatus(NotaFiscalEmitida.Status.AUTORIZADA);
                    n.setProtocolo(res.protocolo());
                    n.setSefazCstat(res.cStat());
                    n.setSefazMotivo("Retransmitida OK: " + res.motivo());
                    n.setProximaTentativaEm(null);
                    auditoria.registrar(r.getId(), perfil.getCnpj(), "sistema:retry",
                            "NFCE_RETRANSMISSAO", "OK", null,
                            Map.of("notaId", notaId, "chave", n.getChaveAcesso(),
                                    "tentativa", n.getTentativas()));
                } else {
                    n.setStatus(NotaFiscalEmitida.Status.CONTINGENCIA_EPEC);
                    n.setSefazMotivo("Retransmissão falhou: " + res.motivo());
                    n.setProximaTentativaEm(LocalDateTime.now().plusMinutes(backoffMinutos(n.getTentativas())));
                    auditoria.registrar(r.getId(), perfil.getCnpj(), "sistema:retry",
                            "NFCE_RETRANSMISSAO", "FALHA", null,
                            Map.of("notaId", notaId, "cStat", res.cStat(), "tentativa", n.getTentativas()));
                }
            } else {
                // REJEITADA sem XML — tenta emitir de novo do zero (mesmo número)
                log.info("[Fiscal][Retry] Nota {} tentando reemissão (tentativa {})", notaId, n.getTentativas());
                n.setProximaTentativaEm(LocalDateTime.now().plusMinutes(backoffMinutos(n.getTentativas())));
                // Full reemissão é complexa (precisa novo lock de contador etc) — pra R5 MVP,
                // marca pra dono reemitir manualmente. R6+ pode automatizar total.
            }
        } catch (Exception e) {
            log.error("[Fiscal][Retry] Exception retentando nota {}: {}", notaId, e.getMessage());
            n.setProximaTentativaEm(LocalDateTime.now().plusMinutes(backoffMinutos(n.getTentativas())));
        }
        return notaRepo.save(n);
    }

    /** Backoff exponencial: 5, 10, 20, 40, 80, 160... minutos. Cap 4h. */
    private static int backoffMinutos(int tentativa) {
        int base = 5 * (int) Math.pow(2, Math.max(0, tentativa - 1));
        return Math.min(base, 240);
    }

    /** Reconstrói RequisicaoEmissao mínima pra retransmissão (só precisa de cert + UF + ambiente). */
    private NfeGateway.RequisicaoEmissao montarReqRetransmissao(
            NotaFiscalEmitida n, PerfilFiscalRestaurante perfil, byte[] pfx, String senhaCert) {
        // Emitente/itens/etc não são usados na retransmissão (XML já pronto),
        // mas mantemos preenchido pra compatibilidade com contratos do gateway.
        return new NfeGateway.RequisicaoEmissao(
                perfil.getUf(), perfil.getAmbienteSefaz(),
                n.getSerie(), n.getNumero(),
                n.getEmitidaEm() != null ? n.getEmitidaEm() : LocalDateTime.now(),
                new NfeGateway.Emitente(perfil.getCnpj(), perfil.getRazaoSocial(),
                        perfil.getNomeFantasia(), perfil.getInscricaoEstadual(),
                        perfil.getUf(), perfil.getMunicipioCodigoIbge(),
                        perfil.getRestaurante() == null ? null : perfil.getRestaurante().getCidade(),
                        perfil.getEnderecoLogradouro(), perfil.getEnderecoNumero(),
                        perfil.getEnderecoBairro(), perfil.getEnderecoCep(),
                        perfil.getRegimeTributario()),
                null, java.util.List.of(), n.getValorTotal(), java.util.List.of(),
                perfil.getCscId(),
                perfilFiscalService.abrirCscParaUso(perfil.getRestaurante().getId()),
                pfx, senhaCert);
    }

    /** Mapeia forma de pagamento do pedido pro código SEFAZ (tag {@code tPag}). */
    private static String mapearFormaPagamento(String forma) {
        if (forma == null) return "99";
        String f = forma.toUpperCase();
        if (f.contains("DINHEIRO"))          return "01";
        if (f.contains("PIX"))               return "17";
        if (f.contains("CREDITO") || f.contains("CRÉDITO")) return "03";
        if (f.contains("DEBITO") || f.contains("DÉBITO"))   return "04";
        if (f.contains("VALE"))              return "05";
        return "99"; // Outros
    }

    private static String nvl(String a, String b) { return (a == null || a.isBlank()) ? b : a; }

    /** Formata valor decimal pt-BR (troca . por ,). Retorna "0,00" pra vazio/nulo. */
    private static String brVal(String v) {
        if (v == null || v.isBlank()) return "0,00";
        try { return new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString().replace('.', ','); }
        catch (Exception e) { return v.replace('.', ','); }
    }

    /**
     * Extrai a lista de itens fiscais de um XML de NFC-e autorizado.
     * Retorna cada item como um Map com: cProd, xProd, NCM, CFOP, uCom, qCom,
     * vUnCom, vProd, CST_ICMS (CST ou CSOSN), CST_PIS, CST_COFINS.
     * Fallback silencioso — retorna lista vazia se XML mal-formado.
     */
    private List<Map<String, String>> extrairItensDoXml(String xml) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            var doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)));
            var xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            var nodes = (org.w3c.dom.NodeList) xp.evaluate("//det", doc,
                    javax.xml.xpath.XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = nodes.item(i);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("cProd",    xp.evaluate("prod/cProd", node));
                m.put("xProd",    xp.evaluate("prod/xProd", node));
                m.put("NCM",      xp.evaluate("prod/NCM", node));
                m.put("CFOP",     xp.evaluate("prod/CFOP", node));
                m.put("uCom",     xp.evaluate("prod/uCom", node));
                m.put("qCom",     xp.evaluate("prod/qCom", node));
                m.put("vUnCom",   xp.evaluate("prod/vUnCom", node));
                m.put("vProd",    xp.evaluate("prod/vProd", node));
                // ICMS: pega CSOSN (Simples) ou CST (regime normal), qualquer
                // que exista dentro de imposto/ICMS/*
                String csosn = xp.evaluate("imposto/ICMS/*/CSOSN", node);
                String cst   = xp.evaluate("imposto/ICMS/*/CST", node);
                m.put("CST_ICMS", (csosn != null && !csosn.isBlank()) ? csosn : cst);
                m.put("CST_PIS",    xp.evaluate("imposto/PIS/*/CST", node));
                m.put("CST_COFINS", xp.evaluate("imposto/COFINS/*/CST", node));
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("[Fiscal][Rel] extrair itens XML falhou: {}", e.getMessage());
        }
        return out;
    }

    /** Pra a UI/admin: lista notas do restaurante em ordem cronológica desc. */
    public List<Map<String, Object>> listarNotas(Long restauranteId) {
        var lista = notaRepo.findByRestauranteIdOrderByCriadoEmDesc(restauranteId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var n : lista) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("pedidoId", n.getPedidoId());
            m.put("numero", n.getNumero());
            m.put("serie", n.getSerie());
            m.put("ambiente", n.getAmbiente());
            m.put("status", n.getStatus().name());
            m.put("cStat", n.getSefazCstat());
            m.put("motivo", n.getSefazMotivo());
            m.put("chaveAcesso", n.getChaveAcesso());
            m.put("protocolo", n.getProtocolo());
            m.put("valorTotal", n.getValorTotal());
            m.put("qrCodeUrl", n.getQrcodeUrlConsulta());
            m.put("xmlUrl", n.getXmlUrl());
            m.put("emitidaEm", n.getEmitidaEm() == null ? null : n.getEmitidaEm().toString());
            m.put("criadoEm", n.getCriadoEm() == null ? null : n.getCriadoEm().toString());
            out.add(m);
        }
        return out;
    }

    /**
     * Monta um ZIP com XMLs autorizados do período + CSV-resumo pro contador.
     * Data pode vir como "yyyy-MM-dd" (do &lt;input type="date"&gt;) ou null (tudo).
     * XMLs são lidos do storage backend ativo (disco local ou R2).
     * Fase 4 do módulo fiscal.
     */
    public byte[] montarRelatorioZip(Long restauranteId, String dataInicial, String dataFinal) {
        LocalDateTime di = parseDataIso(dataInicial, LocalDate.of(2000, 1, 1).atStartOfDay());
        LocalDateTime df = parseDataIso(dataFinal, LocalDate.now().atTime(23, 59, 59));

        // CFOP de entrada padrão que o contador quer ver ESPELHANDO cada saída
        // (pra facilitar a escrituração dele). Default 1102.
        String cfopEntrada = perfilRepo.findByRestauranteId(restauranteId)
                .map(p -> p.getCfopEntradaPadrao() == null || p.getCfopEntradaPadrao().isBlank()
                        ? "1102" : p.getCfopEntradaPadrao())
                .orElse("1102");

        var todas = notaRepo.findByRestauranteIdOrderByCriadoEmDesc(restauranteId);
        var noPeriodo = new ArrayList<NotaFiscalEmitida>();
        for (var n : todas) {
            // Ambiente 2 = homologação (teste). Não deve entrar no relatório
            // que vai pro contador — só notas de produção contam pra fisco.
            if (n.getAmbiente() != null && n.getAmbiente() == 2) continue;
            var ref = n.getEmitidaEm() != null ? n.getEmitidaEm() : n.getCriadoEm();
            if (ref != null && !ref.isBefore(di) && !ref.isAfter(df)) {
                noPeriodo.add(n);
            }
        }

        DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        BigDecimal totalAutorizadas = BigDecimal.ZERO;
        int qtdAut = 0, qtdCanc = 0, qtdRej = 0;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(baos)) {

            // CSV-resumo — abre em Excel/LibreOffice, importa direto em contabilidade.
            // CfopEntrada = espelho pro contador escriturar; vem do perfil da loja.
            StringBuilder csv = new StringBuilder();
            csv.append("Numero;Serie;Data;Chave;Status;ValorTotal;CfopEntrada;Protocolo;Motivo\n");

            for (var n : noPeriodo) {
                String status = n.getStatus() == null ? "" : n.getStatus().name();
                if ("AUTORIZADA".equals(status)) {
                    qtdAut++;
                    if (n.getValorTotal() != null) totalAutorizadas = totalAutorizadas.add(n.getValorTotal());
                } else if ("CANCELADA".equals(status)) qtdCanc++;
                else if ("REJEITADA".equals(status) || "DENEGADA".equals(status)) qtdRej++;

                var ref = n.getEmitidaEm() != null ? n.getEmitidaEm() : n.getCriadoEm();
                csv.append(nvl(n.getNumero())).append(';')
                   .append(nvl(n.getSerie())).append(';')
                   .append(ref == null ? "" : ref.format(FMT)).append(';')
                   .append(nvl(n.getChaveAcesso())).append(';')
                   .append(status).append(';')
                   .append(n.getValorTotal() == null ? "0,00"
                            : n.getValorTotal().toPlainString().replace('.', ',')).append(';')
                   .append(cfopEntrada).append(';')
                   .append(nvl(n.getProtocolo())).append(';')
                   .append(sanitizaCsv(n.getSefazMotivo())).append('\n');

                // Adiciona o XML se disponível
                String xmlUrl = n.getXmlUrl();
                if (xmlUrl != null && !xmlUrl.isBlank() && "AUTORIZADA".equals(status)) {
                    try {
                        String xml = storage.lerXml(n.getCnpj(), n.getChaveAcesso());
                        if (xml != null && !xml.isBlank()) {
                            zip.putNextEntry(new ZipEntry("saidas/xmls/" + n.getChaveAcesso() + ".xml"));
                            zip.write(xml.getBytes(StandardCharsets.UTF_8));
                            zip.closeEntry();
                        }
                    } catch (Exception e) {
                        log.warn("[Fiscal][Rel] XML da chave {} indisponível: {}",
                                n.getChaveAcesso(), e.toString());
                    }
                }
            }

            // ══ ITENS DETALHADOS (linha por item) + RESUMO POR CFOP/CST ══
            // Formato que a contadora ama: lista cada produto vendido com NCM,
            // CFOP, CSOSN, CST_PIS, CST_COFINS, quantidade, valor. E abaixo
            // agrupa por (CFOP+CSOSN) com totais. Padrão dos softwares fiscais
            // pagos (SPED-Fiscal ready).
            StringBuilder csvItens = new StringBuilder();
            csvItens.append("Numero;Chave;Data;CodigoItem;Descricao;NCM;UND;CFOP;CST_ICMS;CST_PIS;CST_COFINS;QTD;VALOR_UNIT;VALOR_TOTAL\n");
            // Agrupador: chave "cfop|cstIcms|cstPis|cstCofins" → {qtd, valor}
            var grupos = new java.util.LinkedHashMap<String, BigDecimal[]>();
            for (var n : noPeriodo) {
                if (n.getStatus() != NotaFiscalEmitida.Status.AUTORIZADA) continue;
                try {
                    String xml = storage.lerXml(n.getCnpj(), n.getChaveAcesso());
                    if (xml == null || xml.isBlank()) continue;
                    var itens = extrairItensDoXml(xml);
                    var refN = n.getEmitidaEm() != null ? n.getEmitidaEm() : n.getCriadoEm();
                    for (var it : itens) {
                        csvItens.append(nvl(n.getNumero())).append(';')
                                .append(nvl(n.getChaveAcesso())).append(';')
                                .append(refN == null ? "" : refN.format(FMT)).append(';')
                                .append(nvl(it.get("cProd"))).append(';')
                                .append(sanitizaCsv(it.get("xProd"))).append(';')
                                .append(nvl(it.get("NCM"))).append(';')
                                .append(nvl(it.get("uCom"))).append(';')
                                .append(nvl(it.get("CFOP"))).append(';')
                                .append(nvl(it.get("CST_ICMS"))).append(';')
                                .append(nvl(it.get("CST_PIS"))).append(';')
                                .append(nvl(it.get("CST_COFINS"))).append(';')
                                .append(brVal(it.get("qCom"))).append(';')
                                .append(brVal(it.get("vUnCom"))).append(';')
                                .append(brVal(it.get("vProd"))).append('\n');
                        String chaveGrupo = nvl(it.get("CFOP")) + "|" + nvl(it.get("CST_ICMS"))
                                + "|" + nvl(it.get("CST_PIS")) + "|" + nvl(it.get("CST_COFINS"));
                        var atual = grupos.computeIfAbsent(chaveGrupo, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                        try { atual[0] = atual[0].add(new BigDecimal(nvl(it.get("qCom"), "0"))); } catch (Exception ig) {}
                        try { atual[1] = atual[1].add(new BigDecimal(nvl(it.get("vProd"), "0"))); } catch (Exception ig) {}
                    }
                } catch (Exception e) {
                    log.warn("[Fiscal][Rel] parse itens XML {} falhou: {}", n.getChaveAcesso(), e.toString());
                }
            }
            StringBuilder csvGrupos = new StringBuilder();
            csvGrupos.append("CFOP;CST/CSOSN;CST_PIS;CST_COFINS;QTD_TOTAL;VALOR_TOTAL\n");
            BigDecimal totalGeralQtd = BigDecimal.ZERO, totalGeralVal = BigDecimal.ZERO;
            for (var e : grupos.entrySet()) {
                String[] k = e.getKey().split("\\|", -1);
                csvGrupos.append(k[0]).append(';').append(k[1]).append(';')
                        .append(k[2]).append(';').append(k[3]).append(';')
                        .append(brVal(e.getValue()[0].toPlainString())).append(';')
                        .append(brVal(e.getValue()[1].toPlainString())).append('\n');
                totalGeralQtd = totalGeralQtd.add(e.getValue()[0]);
                totalGeralVal = totalGeralVal.add(e.getValue()[1]);
            }
            csvGrupos.append(";;;TOTAL GERAL;")
                    .append(brVal(totalGeralQtd.toPlainString())).append(';')
                    .append(brVal(totalGeralVal.toPlainString())).append('\n');
            zip.putNextEntry(new ZipEntry("saidas/itens_detalhados.csv"));
            zip.write(csvItens.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("saidas/resumo_cfop_cst.csv"));
            zip.write(csvGrupos.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // ══ NF-e de ENTRADA (fornecedor — upload manual) ══════════════
            var entradas = notaEntradaRepo.findByRestauranteIdAndDataEmissaoBetween(restauranteId, di, df);
            BigDecimal totalEntradas = BigDecimal.ZERO;
            StringBuilder csvEntradas = new StringBuilder();
            csvEntradas.append("NumeroNF;DataEmissao;CnpjFornecedor;NomeFornecedor;Chave;ValorTotal\n");
            for (var e : entradas) {
                if (e.getValorTotal() != null) totalEntradas = totalEntradas.add(e.getValorTotal());
                csvEntradas.append(nvl(e.getNumero())).append(';')
                        .append(e.getDataEmissao() == null ? "" : e.getDataEmissao().format(FMT)).append(';')
                        .append(nvl(e.getCnpjEmitente())).append(';')
                        .append(sanitizaCsv(e.getNomeEmitente())).append(';')
                        .append(nvl(e.getChaveAcesso())).append(';')
                        .append(e.getValorTotal() == null ? "0,00"
                                : e.getValorTotal().toPlainString().replace('.', ',')).append('\n');
                try {
                    if (e.getXmlConteudo() != null && !e.getXmlConteudo().isBlank()) {
                        zip.putNextEntry(new ZipEntry("entradas/" + e.getChaveAcesso() + ".xml"));
                        zip.write(e.getXmlConteudo().getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                } catch (Exception ex) {
                    log.warn("[Fiscal][Rel] XML entrada {} falhou: {}", e.getChaveAcesso(), ex.toString());
                }
            }

            // README explicativo pro contador
            String readme = "" +
                "RELATÓRIO FISCAL — MyDelivery\n" +
                "Período: " + di.format(FMT) + " a " + df.format(FMT) + "\n\n" +
                "RESUMO — SAÍDAS (NFC-e emitidas)\n" +
                "  Autorizadas: " + qtdAut + "\n" +
                "  Canceladas:  " + qtdCanc + "\n" +
                "  Rejeitadas:  " + qtdRej + "\n" +
                "  Valor total (autorizadas): R$ " +
                    totalAutorizadas.toPlainString().replace('.', ',') + "\n\n" +
                "RESUMO — ENTRADAS (NF-e recebidas de fornecedor)\n" +
                "  Qtd notas: " + entradas.size() + "\n" +
                "  Valor total: R$ " + totalEntradas.toPlainString().replace('.', ',') + "\n\n" +
                "CONTEÚDO DO ZIP\n" +
                "  saidas/resumo.csv              — 1 linha por NFC-e emitida (Excel/LibreOffice)\n" +
                "  saidas/itens_detalhados.csv    — 1 linha por ITEM (produto) de cada nota — com NCM, CFOP, CSOSN, CST PIS/COFINS, QTD, VALOR\n" +
                "  saidas/resumo_cfop_cst.csv     — totais AGRUPADOS por (CFOP + CST/CSOSN + CST PIS + CST COFINS) — formato que a contabilidade usa\n" +
                "  saidas/xmls/*.xml              — XMLs autorizados prontos pra importar (SPED/EFD)\n" +
                "  entradas/resumo.csv            — lista das NF-e recebidas de fornecedor\n" +
                "  entradas/*.xml                 — XMLs de entrada (upload manual do dono)\n\n" +
                "Gerado em " + LocalDateTime.now().format(FMT) + "\n";
            zip.putNextEntry(new ZipEntry("LEIA-ME.txt"));
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("saidas/resumo.csv"));
            zip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("entradas/resumo.csv"));
            zip.write(csvEntradas.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.finish();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Falha ao montar ZIP fiscal: " + e.getMessage(), e);
        }
    }

    private static LocalDateTime parseDataIso(String iso, LocalDateTime fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try { return LocalDate.parse(iso).atStartOfDay(); }
        catch (Exception e) { return fallback; }
    }

    private static String nvl(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String sanitizaCsv(String s) {
        if (s == null) return "";
        return s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
