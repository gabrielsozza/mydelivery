package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.model.*;

/** Preços do catálogo por grupo; nomes e preços da observação nunca definem a regra. */
final class PrecoComplementosMaior {
    private PrecoComplementosMaior() {}
    private record Escolha(ComplementoGrupo grupo, ComplementoItem item, int quantidade) {}
    private static final Pattern PRECO = Pattern.compile("\\(\\s*\\+?\\s*R\\$\\s*[0-9]+(?:[.,][0-9]{1,2})?\\s*\\)");
    private static final Pattern QUANTIDADE = Pattern.compile("\\s*[×x]\\s*(\\d+)\\s*$");

    static BigDecimal calcular(Produto produto, NovoPedidoRequest.ItemDto pedido,
                               List<ComplementoGrupo> grupos, BigDecimal base) {
        if (grupos == null || grupos.isEmpty()
                || (!Boolean.TRUE.equals(produto.getPrecoVitrine()) && grupos.stream().noneMatch(g ->
                    g.getModoPreco() == ComplementoGrupo.ModoPreco.MAIOR
                    || g.getModoPreco() == ComplementoGrupo.ModoPreco.MEDIA))
                || produto.getTipo() == Produto.Tipo.COMBO) return null;
        List<Escolha> escolhas = new ArrayList<>();
        if (pedido.getComplementos() != null) {
            for (var dto : pedido.getComplementos()) {
                if (dto == null) throw invalido();
                var grupo = grupos.stream().filter(g -> Objects.equals(g.getId(), dto.getGrupoId())).findFirst().orElseThrow(PrecoComplementosMaior::invalido);
                var item = grupo.getItens().stream().filter(i -> Objects.equals(i.getId(), dto.getItemId())).findFirst().orElseThrow(PrecoComplementosMaior::invalido);
                escolhas.add(new Escolha(grupo, item, dto.getQuantidade() == null ? 1 : dto.getQuantidade()));
            }
        } else {
            // Compatibilidade com carrinhos já abertos: apenas linhas de adicionais
            // geradas pelo cardápio, resolvidas pelo nome cadastrado, sem usar seu preço.
            String obs = pedido.getObs() == null ? "" : pedido.getObs();
            for (String linha : obs.split("\\R")) {
                if (!linha.stripLeading().startsWith("+ ")) continue;
                String texto = linha.stripLeading().substring(2);
                // O cardápio retira vírgulas dos nomes antes de separar os itens.
                // Remova primeiro preços (que contêm vírgula decimal), incluindo
                // os itens gratuitos que não possuem anotação de preço.
                for (String nome : PRECO.matcher(texto).replaceAll("").split(",")) {
                    if (!nome.isBlank()) adicionarLegado(escolhas, grupos, nome.trim());
                }
            }
        }
        Map<Long,Integer> quantidades = new HashMap<>();
        Map<Long,BigDecimal> valores = new HashMap<>();
        Set<Long> selecionados = new HashSet<>();
        boolean temGrupoMedia = false;
        for (var e : escolhas) {
            if (e.quantidade() < 1 || e.quantidade() > 1000 || Boolean.FALSE.equals(e.item().getAtivo())
                    || !selecionados.add(e.item().getId())
                    || (e.grupo().getModoPreco() == ComplementoGrupo.ModoPreco.MEDIA && e.quantidade() != 1)
                    || (e.item().getMaxSelecoes() != null && e.item().getMaxSelecoes() > 0 && e.quantidade() > e.item().getMaxSelecoes())) throw invalido();
            quantidades.merge(e.grupo().getId(), e.quantidade(), Integer::sum);
            if (e.grupo().getModoPreco() == ComplementoGrupo.ModoPreco.MEDIA) temGrupoMedia = true;
            BigDecimal valor = Objects.requireNonNullElse(e.item().getPrecoAdicional(), BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(e.quantidade()));
            if (e.grupo().getModoPreco() == ComplementoGrupo.ModoPreco.MAIOR)
                valores.merge(e.grupo().getId(), valor, BigDecimal::max);
            else valores.merge(e.grupo().getId(), valor, BigDecimal::add);
        }
        for (var g : grupos) {
            int qty = quantidades.getOrDefault(g.getId(), 0);
            int min = Boolean.TRUE.equals(g.getObrigatorio()) ? Math.max(1, Objects.requireNonNullElse(g.getMinEscolhas(), 0)) : Objects.requireNonNullElse(g.getMinEscolhas(), 0);
            if (qty == 0 && Boolean.TRUE.equals(g.getPermitirNenhuma())) min = 0;
            if (qty < min || qty > Objects.requireNonNullElse(g.getMaxEscolhas(), 1)) throw invalido();
        }
        BigDecimal total = Boolean.TRUE.equals(produto.getPrecoVitrine()) || temGrupoMedia ? BigDecimal.ZERO : base;
        for (var g : grupos) {
            BigDecimal valor = valores.get(g.getId());
            if (valor == null) continue;
            if (g.getModoPreco() == ComplementoGrupo.ModoPreco.MEDIA)
                valor = valor.divide(BigDecimal.valueOf(quantidades.get(g.getId()) > 1 ? 2 : 1), 2, RoundingMode.HALF_UP);
            total = total.add(valor);
        }
        return total;
    }
    private static void adicionarLegado(List<Escolha> escolhas, List<ComplementoGrupo> grupos, String nome) {
        int qty = 1;
        var quantidade = QUANTIDADE.matcher(nome);
        if (quantidade.find()) {
            try { qty = Integer.parseInt(quantidade.group(1)); } catch (NumberFormatException ex) { throw invalido(); }
            nome = nome.substring(0, quantidade.start()).trim();
        }
        String chave = normalizar(nome);
        List<Escolha> matches = new ArrayList<>();
        for (var g : grupos) for (var item : g.getItens())
            if (normalizar(item.getNome()).equals(chave)) matches.add(new Escolha(g,item,qty));
        if (matches.size() != 1) throw invalido();
        escolhas.add(matches.getFirst());
    }
    private static String normalizar(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace(',', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
    private static ResponseStatusException invalido() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Os sabores ou adicionais deste produto mudaram. Abra o produto novamente e confira as opções antes de pedir.");
    }
}
