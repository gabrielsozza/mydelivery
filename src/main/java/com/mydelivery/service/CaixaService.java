package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Caixa;
import com.mydelivery.model.MovimentacaoCaixa;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CaixaRepository;
import com.mydelivery.repository.MovimentacaoCaixaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serviço do módulo Caixa.
 *
 * <p><b>Regra invariante:</b> só existe UM caixa aberto por restaurante por
 * vez. Tentar abrir com outro já aberto lança erro. Tentar registrar venda
 * sem caixa aberto — apenas SILENCIA (log info) pra não bloquear pedido
 * novo. Isso permite ao restaurante trabalhar mesmo sem usar caixa formal.
 *
 * <p><b>Cálculo do esperado:</b>
 * <pre>
 *   esperado = valorInicial
 *            + SUM(VENDA_DINHEIRO)
 *            + SUM(SUPRIMENTO)
 *            − SUM(SANGRIA)
 * </pre>
 * PIX/Crédito/Débito NÃO entram no esperado em dinheiro — ficam só no
 * resumo de vendas totais.
 *
 * <p>Todas as chamadas de {@link #registrarVenda} são idempotentes por
 * {@code (caixaId, pedidoId)}. Se falhar, log warn — nunca propaga pro
 * BalcaoService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaixaService {

    private final CaixaRepository caixaRepo;
    private final MovimentacaoCaixaRepository movRepo;

    // ═════════════════════════════════════════════════════════════════════
    // ABERTURA / STATUS
    // ═════════════════════════════════════════════════════════════════════

    /** Retorna o caixa aberto do restaurante, se existir. */
    @Transactional(readOnly = true)
    public Optional<Caixa> caixaAberto(Long restauranteId) {
        return caixaRepo.findFirstByRestauranteIdAndStatus(restauranteId, Caixa.Status.ABERTO);
    }

    /**
     * Abre novo caixa. Lança RuntimeException se já houver um aberto.
     * {@code valorInicial} default 0 se null (loja começa sem troco).
     */
    @Transactional
    public Caixa abrir(Restaurante restaurante, String operadorEmail, String operadorNome,
                        BigDecimal valorInicial) {
        if (caixaAberto(restaurante.getId()).isPresent()) {
            throw new RuntimeException("Já existe um caixa aberto. Feche-o antes de abrir outro.");
        }
        Caixa c = Caixa.builder()
                .restaurante(restaurante)
                .operadorEmail(operadorEmail)
                .operadorNome(operadorNome)
                .valorInicial(valorInicial == null ? BigDecimal.ZERO : valorInicial.max(BigDecimal.ZERO))
                .abertoEm(LocalDateTime.now())
                .status(Caixa.Status.ABERTO)
                .build();
        Caixa salvo = caixaRepo.save(c);
        log.info("[Caixa] ABERTO rest={} operador={} inicial=R${} caixaId={}",
                restaurante.getId(), operadorEmail, salvo.getValorInicial(), salvo.getId());
        return salvo;
    }

    // ═════════════════════════════════════════════════════════════════════
    // MOVIMENTAÇÕES
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Registra sangria (retirada). Valor positivo — a lógica de esperado
     * subtrai internamente. Descrição obrigatória pra auditoria.
     */
    @Transactional
    public MovimentacaoCaixa registrarSangria(Long caixaId, BigDecimal valor, String descricao,
                                                String operadorEmail) {
        return registrarAjuste(caixaId, MovimentacaoCaixa.Tipo.SANGRIA, valor, descricao, operadorEmail);
    }

    /** Registra suprimento (entrada de dinheiro). */
    @Transactional
    public MovimentacaoCaixa registrarSuprimento(Long caixaId, BigDecimal valor, String descricao,
                                                    String operadorEmail) {
        return registrarAjuste(caixaId, MovimentacaoCaixa.Tipo.SUPRIMENTO, valor, descricao, operadorEmail);
    }

    private MovimentacaoCaixa registrarAjuste(Long caixaId, MovimentacaoCaixa.Tipo tipo,
                                                BigDecimal valor, String descricao, String operadorEmail) {
        Caixa c = caixaAbertoOuErro(caixaId);
        if (valor == null || valor.signum() <= 0) {
            throw new RuntimeException("Valor precisa ser maior que zero");
        }
        if (descricao == null || descricao.isBlank()) {
            throw new RuntimeException("Descrição é obrigatória em " + tipo);
        }
        MovimentacaoCaixa m = MovimentacaoCaixa.builder()
                .caixa(c)
                .tipo(tipo)
                .valor(valor)
                .descricao(descricao.trim())
                .operadorEmail(operadorEmail)
                .build();
        MovimentacaoCaixa salvo = movRepo.save(m);
        log.info("[Caixa] {} caixaId={} valor=R${} desc='{}'",
                tipo, caixaId, valor, descricao.length() > 40 ? descricao.substring(0, 40) + "..." : descricao);
        return salvo;
    }

    /**
     * Registra automaticamente uma venda quando pedido do balcão é criado.
     * Chamado pelo BalcaoService com try/catch — nunca bloqueia o pedido.
     *
     * <p>Se pedido tem pagamento dividido ({@code pagamentosJson}), cria N
     * movimentações (uma por parte). Se não, uma única na formaPagamento.
     *
     * <p>Idempotente: se já existe VENDA_* pra o pedido no caixa, ignora.
     */
    @Transactional
    public void registrarVendaDoPedido(Long restauranteId, Pedido pedido) {
        if (pedido == null || pedido.getId() == null) return;
        Optional<Caixa> optCaixa = caixaAberto(restauranteId);
        if (optCaixa.isEmpty()) {
            log.debug("[Caixa] pedido#{} criado sem caixa aberto — vendas não vinculadas",
                    pedido.getId());
            return;
        }
        Caixa c = optCaixa.get();
        // Idempotência: nunca duplica venda no mesmo caixa
        if (movRepo.contarVendasPorPedido(c.getId(), pedido.getId()) > 0) {
            log.debug("[Caixa] pedido#{} já registrado no caixa {} — skip", pedido.getId(), c.getId());
            return;
        }
        // Split dividido tem prioridade: se veio JSON, respeita
        String json = pedido.getPagamentosJson();
        if (json != null && !json.isBlank()) {
            registrarVendasDoJson(c, pedido, json);
        } else {
            registrarVendaSingle(c, pedido, pedido.getFormaPagamento(), pedido.getTotal());
        }
    }

    /**
     * Parser LEVE do JSON de pagamentos ({@code [{"forma":"PIX","valor":20},...]}).
     * Evita depender de ObjectMapper aqui pra não injetar mais uma coisa —
     * o formato é sob nosso controle no BalcaoService.
     */
    private void registrarVendasDoJson(Caixa c, Pedido pedido, String json) {
        try {
            // Remove '[' e ']', quebra por '},{'
            String s = json.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
            for (String item : s.split("\\},\\{")) {
                String clean = item.replace("{", "").replace("}", "");
                String forma = extrairString(clean, "forma");
                String valorStr = extrairString(clean, "valor");
                if (forma == null || valorStr == null) continue;
                BigDecimal valor = new BigDecimal(valorStr);
                Pedido.FormaPagamento fp = mapearForma(forma);
                registrarVendaSingle(c, pedido, fp, valor);
            }
        } catch (Exception e) {
            log.warn("[Caixa] falha parseando pagamentosJson do pedido#{}: {} — fallback pra venda única",
                    pedido.getId(), e.getMessage());
            registrarVendaSingle(c, pedido, pedido.getFormaPagamento(), pedido.getTotal());
        }
    }

    /** Extrai valor por chave num string "forma":"PIX","valor":20 */
    private String extrairString(String s, String chave) {
        int idx = s.indexOf("\"" + chave + "\"");
        if (idx < 0) return null;
        int colon = s.indexOf(':', idx);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == '"')) start++;
        int end = start;
        while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '"' && s.charAt(end) != '}') end++;
        return s.substring(start, end).trim();
    }

    private void registrarVendaSingle(Caixa c, Pedido pedido, Pedido.FormaPagamento fp, BigDecimal valor) {
        if (fp == null || valor == null || valor.signum() <= 0) return;
        MovimentacaoCaixa.Tipo tipo = mapearTipoMov(fp);
        if (tipo == null) return; // pgto pendente ou outro não relevante
        MovimentacaoCaixa m = MovimentacaoCaixa.builder()
                .caixa(c)
                .tipo(tipo)
                .valor(valor)
                .descricao("Pedido balcão #" + pedido.getId())
                .pedidoId(pedido.getId())
                .operadorEmail(c.getOperadorEmail())
                .build();
        movRepo.save(m);
        log.debug("[Caixa] {} caixaId={} pedido#{} valor=R${}",
                tipo, c.getId(), pedido.getId(), valor);
    }

    /** Converte FormaPagamento pra Tipo da movimentação. */
    private MovimentacaoCaixa.Tipo mapearTipoMov(Pedido.FormaPagamento fp) {
        if (fp == null) return null;
        switch (fp) {
            case DINHEIRO: return MovimentacaoCaixa.Tipo.VENDA_DINHEIRO;
            case PIX: return MovimentacaoCaixa.Tipo.VENDA_PIX;
            case CARTAO_CREDITO: return MovimentacaoCaixa.Tipo.VENDA_CREDITO;
            case CARTAO_DEBITO: return MovimentacaoCaixa.Tipo.VENDA_DEBITO;
            // Fallback pra maquininha genérica → crédito (comportamento
            // conservador quando o operador não escolheu explicitamente).
            case CARTAO_MAQUININHA: return MovimentacaoCaixa.Tipo.VENDA_CREDITO;
            default: return null;
        }
    }

    /** Converte string de forma do JSON pra enum. */
    private Pedido.FormaPagamento mapearForma(String s) {
        if (s == null) return null;
        try { return Pedido.FormaPagamento.valueOf(s.toUpperCase().trim()); }
        catch (Exception e) { return null; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // FECHAMENTO
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Resumo pra tela de fechamento — sem alterar nada no BD. Serve pra o
     * operador ver antes de bater o valor encontrado.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resumoFechamento(Long caixaId) {
        Caixa c = caixaRepo.findById(caixaId)
                .orElseThrow(() -> new RuntimeException("Caixa não encontrado"));
        return montarResumo(c);
    }

    /**
     * Encerra o caixa. Recebe valor encontrado no dinheiro físico (contado
     * pelo operador) + observação opcional. Calcula esperado, diferença,
     * grava e retorna resumo final.
     */
    @Transactional
    public Map<String, Object> fechar(Long caixaId, BigDecimal valorEncontrado, String observacao) {
        Caixa c = caixaAbertoOuErro(caixaId);
        Map<String, Object> resumo = montarResumo(c);
        BigDecimal esperado = (BigDecimal) resumo.get("esperado");
        BigDecimal encontrado = valorEncontrado == null ? BigDecimal.ZERO : valorEncontrado;
        BigDecimal diferenca = encontrado.subtract(esperado);
        c.setValorEsperado(esperado);
        c.setValorEncontrado(encontrado);
        c.setDiferenca(diferenca);
        c.setFechadoEm(LocalDateTime.now());
        c.setStatus(Caixa.Status.FECHADO);
        c.setObservacaoFechamento(observacao);
        caixaRepo.save(c);
        log.info("[Caixa] FECHADO id={} esperado=R${} encontrado=R${} diferenca=R${}",
                c.getId(), esperado, encontrado, diferenca);
        resumo.put("encontrado", encontrado);
        resumo.put("diferenca", diferenca);
        resumo.put("status", c.getStatus().name());
        resumo.put("fechadoEm", c.getFechadoEm().toString());
        return resumo;
    }

    /**
     * Monta o mapa de resumo — usado tanto na consulta pra fechar quanto
     * no retorno do fechamento. Todos os valores em BigDecimal 2 casas.
     */
    private Map<String, Object> montarResumo(Caixa c) {
        Map<MovimentacaoCaixa.Tipo, BigDecimal> somas = new java.util.EnumMap<>(MovimentacaoCaixa.Tipo.class);
        for (MovimentacaoCaixa.Tipo t : MovimentacaoCaixa.Tipo.values()) somas.put(t, BigDecimal.ZERO);
        for (Object[] row : movRepo.somasPorTipo(c.getId())) {
            MovimentacaoCaixa.Tipo t = (MovimentacaoCaixa.Tipo) row[0];
            BigDecimal soma = (BigDecimal) row[1];
            somas.put(t, soma == null ? BigDecimal.ZERO : soma);
        }
        BigDecimal vDin = somas.get(MovimentacaoCaixa.Tipo.VENDA_DINHEIRO);
        BigDecimal vPix = somas.get(MovimentacaoCaixa.Tipo.VENDA_PIX);
        BigDecimal vCre = somas.get(MovimentacaoCaixa.Tipo.VENDA_CREDITO);
        BigDecimal vDeb = somas.get(MovimentacaoCaixa.Tipo.VENDA_DEBITO);
        BigDecimal sup = somas.get(MovimentacaoCaixa.Tipo.SUPRIMENTO);
        BigDecimal san = somas.get(MovimentacaoCaixa.Tipo.SANGRIA);
        BigDecimal totalVendas = vDin.add(vPix).add(vCre).add(vDeb);
        BigDecimal esperado = c.getValorInicial().add(vDin).add(sup).subtract(san);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caixaId", c.getId());
        r.put("status", c.getStatus().name());
        r.put("operadorEmail", c.getOperadorEmail());
        r.put("operadorNome", c.getOperadorNome());
        r.put("abertoEm", c.getAbertoEm() == null ? null : c.getAbertoEm().toString());
        r.put("fechadoEm", c.getFechadoEm() == null ? null : c.getFechadoEm().toString());
        r.put("valorInicial", c.getValorInicial());
        r.put("vendasDinheiro", vDin);
        r.put("vendasPix", vPix);
        r.put("vendasCredito", vCre);
        r.put("vendasDebito", vDeb);
        r.put("totalVendas", totalVendas);
        r.put("suprimentos", sup);
        r.put("sangrias", san);
        r.put("esperado", esperado);
        // Só preenche encontrado/diferença se já foi fechado
        if (c.getValorEncontrado() != null) r.put("encontrado", c.getValorEncontrado());
        if (c.getDiferenca() != null) r.put("diferenca", c.getDiferenca());
        return r;
    }

    // ═════════════════════════════════════════════════════════════════════
    // HISTÓRICO / LISTAGEM
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> historico(Long restauranteId, int dias) {
        LocalDateTime desde = LocalDateTime.now().minusDays(Math.max(1, dias));
        return caixaRepo.historicoDesde(restauranteId, desde).stream()
                .map(this::rowHistorico)
                .toList();
    }

    private Map<String, Object> rowHistorico(Caixa c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", c.getId());
        r.put("operador", c.getOperadorNome() == null ? c.getOperadorEmail() : c.getOperadorNome());
        r.put("abertoEm", c.getAbertoEm() == null ? null : c.getAbertoEm().toString());
        r.put("fechadoEm", c.getFechadoEm() == null ? null : c.getFechadoEm().toString());
        r.put("valorInicial", c.getValorInicial());
        r.put("valorEncontrado", c.getValorEncontrado());
        r.put("diferenca", c.getDiferenca());
        r.put("status", c.getStatus().name());
        return r;
    }

    /** Lista movimentações de um caixa — pra tela de detalhe/auditoria. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> movimentacoes(Long caixaId) {
        return movRepo.findByCaixaIdOrderByCriadoEmAsc(caixaId).stream().map(m -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("tipo", m.getTipo().name());
            r.put("valor", m.getValor());
            r.put("descricao", m.getDescricao());
            r.put("pedidoId", m.getPedidoId());
            r.put("criadoEm", m.getCriadoEm() == null ? null : m.getCriadoEm().toString());
            return r;
        }).toList();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Utils
    // ═════════════════════════════════════════════════════════════════════

    private Caixa caixaAbertoOuErro(Long caixaId) {
        Caixa c = caixaRepo.findById(caixaId)
                .orElseThrow(() -> new RuntimeException("Caixa não encontrado"));
        if (c.getStatus() != Caixa.Status.ABERTO) {
            throw new RuntimeException("Caixa não está aberto");
        }
        return c;
    }
}
