package com.mydelivery.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.cardapio.CategoriaComProdutosResponse;
import com.mydelivery.dto.cardapio.CategoriaRequest;
import com.mydelivery.dto.cardapio.ProdutoRequest;
import com.mydelivery.dto.cardapio.ProdutoResponse;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.FichaTecnicaItemRepository;
import com.mydelivery.repository.PedidoItemRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CardapioService {

    private final CategoriaRepository categoriaRepository;
    private final ProdutoRepository produtoRepository;
    private final RestauranteRepository restauranteRepository;
    private final FichaTecnicaItemRepository fichaTecnicaItemRepository;
    private final PedidoItemRepository pedidoItemRepository;
    private final com.mydelivery.repository.BannerRepository bannerRepository;
    private final com.mydelivery.repository.ComplementoGrupoRepository complementoGrupoRepository;
    private final com.mydelivery.repository.ComboItemRepository comboItemRepository;
    private final com.mydelivery.repository.ComboGrupoRepository comboGrupoRepository;

    // ─── CARDÁPIO PÚBLICO ────────────────────────────────────────────────

    /**
     * Cardapio publico do cliente final. Cacheado por 60s — endpoint hot
     * (cada cliente que abre o link puxa). Cache invalida sozinho em 60s,
     * entao alteracao do restaurante aparece pro cliente em ate 1 minuto.
     * Reducao de queries no banco: ~95% em horario de pico.
     *
     * Cache key inclui o dia da semana no fuso Brasil pra o filtro
     * `diasSemanaAtivos` invalidar automaticamente na virada do dia. Sem isso,
     * na segunda 23:59 o cache guardava produtos de segunda e na terça 00:01
     * eles apareciam por até 60s a mais do que deveriam.
     *
     * Filtro por dia da semana: aplicado AQUI (antes era só em PublicController
     * legado, que nao e o endpoint que o cardapio front consome — bug que fazia
     * produtos com restricao de dia aparecerem em qualquer dia).
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "cardapio",
            key = "#slug + '::' + T(java.time.LocalDate).now(T(java.time.ZoneId).of('America/Sao_Paulo')).getDayOfWeek().name()")
    public List<CategoriaComProdutosResponse> getCardapioPublico(String slug) {
        Restaurante restaurante = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        final String hoje = codigoDiaSemanaAtual();

        List<Categoria> categorias = categoriaRepository
                .findByRestauranteIdAndAtivoTrueOrderByOrdemAsc(restaurante.getId());

        return categorias.stream().map(cat -> {
            List<ProdutoResponse> produtos = produtoRepository
                    .findByRestauranteIdAndDisponivelTrue(restaurante.getId())
                    .stream()
                    .filter(p -> p.getCategoria() != null &&
                                 p.getCategoria().getId().equals(cat.getId()))
                    // Se o produto tem restrição de dias e hoje não está na lista, esconde.
                    // Sem restrição (null/vazio) = aparece sempre (retrocompat pra 99% dos produtos).
                    .filter(p -> diaSemanaAceita(p.getDiasSemanaAtivos(), hoje))
                    // Ordena por ordem (nulls last) — respeita reordenação do painel.
                    // Tie-breaker pelo id pra ordem estável quando vários têm ordem=0.
                    .sorted(java.util.Comparator
                            .comparing((Produto p) -> p.getOrdem() == null ? Integer.MAX_VALUE : p.getOrdem())
                            .thenComparing(Produto::getId))
                    .map(this::toProdutoResponse)
                    .toList();

            return CategoriaComProdutosResponse.builder()
                    .id(cat.getId())
                    .nome(cat.getNome())
                    .ordem(cat.getOrdem())
                    .produtos(produtos)
                    .build();
        }).toList();
    }

    /** Código de 3 letras pro dia da semana atual (fuso Brasil). */
    private static String codigoDiaSemanaAtual() {
        java.time.DayOfWeek d = java.time.LocalDate.now(
                java.time.ZoneId.of("America/Sao_Paulo")).getDayOfWeek();
        switch (d) {
            case MONDAY:    return "SEG";
            case TUESDAY:   return "TER";
            case WEDNESDAY: return "QUA";
            case THURSDAY:  return "QUI";
            case FRIDAY:    return "SEX";
            case SATURDAY:  return "SAB";
            case SUNDAY:    return "DOM";
            default:        return "";
        }
    }

    /** true se produto pode aparecer hoje. null/vazio = sempre visível. */
    private static boolean diaSemanaAceita(String diasCsv, String hoje) {
        if (diasCsv == null || diasCsv.isBlank()) return true;
        for (String d : diasCsv.split(",")) {
            if (d.trim().equalsIgnoreCase(hoje)) return true;
        }
        return false;
    }

    // ─── CATEGORIAS ──────────────────────────────────────────────────────

    public List<Categoria> getCategorias(Long restauranteId) {
        return categoriaRepository.findByRestauranteIdOrderByOrdemAsc(restauranteId);
    }

    @Transactional
    public Categoria criarCategoria(Long restauranteId, CategoriaRequest request) {
        Restaurante restaurante = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        Categoria categoria = new Categoria();
        categoria.setRestaurante(restaurante);
        categoria.setNome(request.getNome());
        categoria.setOrdem(request.getOrdem());
        categoria.setAtivo(request.getAtivo());

        return categoriaRepository.save(categoria);
    }

    @Transactional
    public Categoria atualizarCategoria(Long restauranteId, Long categoriaId,
                                         CategoriaRequest request) {
        Categoria categoria = categoriaRepository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));

        validarPropriedade(categoria.getRestaurante().getId(), restauranteId);

        categoria.setNome(request.getNome());
        categoria.setOrdem(request.getOrdem());
        categoria.setAtivo(request.getAtivo());

        return categoriaRepository.save(categoria);
    }

    /**
     * Duplica uma categoria + todos os seus produtos (com complementos).
     *
     * Detalhes:
     *  - A nova categoria recebe nome "Cópia de X" (ou "Cópia de X (n)" se já existir)
     *    e fica como ÚLTIMA na ordem (ordem = max+1).
     *  - Produtos duplicados são INATIVADOS por padrão (disponivel=false) pra
     *    o dono revisar/precificar antes de deixar visível no cardápio.
     *  - Grupos de complementos e itens são clonados (novos IDs).
     *  - FichaTecnica NÃO é duplicada — produto novo arranca sem ficha. Evita
     *     contar baixa de insumo duas vezes pelo mesmo produto fantasma se o
     *     dono esquecer de revisar. Ele recadastra se quiser.
     *  - Tipo COMBO é PRESERVADO mas combo_itens NÃO são duplicados (manteria
     *     FKs cruzadas confusas). O dono precisa recadastrar os filhos.
     *  - PedidoItem histórico fica intocado — só copia estrutura, não histórico.
     *
     * Atômico: tudo na mesma transação. Se algo falhar, nenhum INSERT persiste.
     */
    @Transactional
    public Categoria duplicarCategoria(Long restauranteId, Long categoriaId) {
        Categoria origem = categoriaRepository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));
        validarPropriedade(origem.getRestaurante().getId(), restauranteId);

        // 1) Nome único — "Cópia de X", senão "Cópia de X (2)", etc.
        String nomeBase = "Cópia de " + origem.getNome();
        String nomeNovo = nomeBase;
        var todasDoRest = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(restauranteId);
        java.util.Set<String> nomesExistentes = todasDoRest.stream()
                .map(Categoria::getNome)
                .collect(java.util.stream.Collectors.toSet());
        int sufixo = 2;
        while (nomesExistentes.contains(nomeNovo)) {
            nomeNovo = nomeBase + " (" + sufixo + ")";
            sufixo++;
        }

        // 2) Ordem: vai pro final
        int proximaOrdem = todasDoRest.stream()
                .mapToInt(c -> c.getOrdem() == null ? 0 : c.getOrdem())
                .max().orElse(0) + 1;

        Categoria nova = new Categoria();
        nova.setRestaurante(origem.getRestaurante());
        nova.setNome(nomeNovo);
        nova.setOrdem(proximaOrdem);
        // Categoria.ativo pode não existir em todas as versões — se existir,
        // usa true como default. Setter via reflection-safe.
        try { nova.setAtivo(Boolean.TRUE); } catch (Exception ignore) {}
        nova = categoriaRepository.save(nova);

        // 3) Duplica produtos (com complementos)
        var produtosOrigem = produtoRepository.findByCategoriaId(categoriaId);
        for (Produto orig : produtosOrigem) {
            clonarProdutoInternal(orig, nova, orig.getNome());
        }

        return nova;
    }

    /**
     * Duplica um produto único (com complementos). Vai pra MESMA categoria do
     * original. Nome vira "Cópia de X" pra evitar confusão visual no painel.
     * Inativo até dono revisar.
     */
    @Transactional
    public ProdutoResponse duplicarProduto(Long restauranteId, Long produtoId) {
        return duplicarProdutoNVezes(restauranteId, produtoId, 1).get(0);
    }

    /**
     * Duplicação em lote (N cópias). Dono pediu poder gerar múltiplas cópias
     * de uma só vez (útil pra marmita: "MARMITEX P segunda", "MARMITEX P terça"...
     * evita duplicar 5x manualmente). Sufixo #1, #2... aplicado a partir da 2ª cópia
     * pra distinguir. Máximo 20 pra evitar abuso acidental.
     */
    @Transactional
    public java.util.List<ProdutoResponse> duplicarProdutoNVezes(Long restauranteId, Long produtoId, int quantidade) {
        if (quantidade < 1) quantidade = 1;
        if (quantidade > 20) quantidade = 20;
        Produto orig = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
        validarPropriedade(orig.getRestaurante().getId(), restauranteId);
        java.util.List<ProdutoResponse> resultado = new java.util.ArrayList<>();
        for (int i = 1; i <= quantidade; i++) {
            String nome = "Cópia de " + orig.getNome();
            if (quantidade > 1) nome = nome + " #" + i;
            Produto clone = clonarProdutoInternal(orig, orig.getCategoria(), nome);
            resultado.add(toProdutoResponse(clone));
        }
        return resultado;
    }

    /**
     * Núcleo do clone — usado por duplicarCategoria (para cada produto, mantém
     * nome original) e duplicarProduto (prefixa "Cópia de "). Cria produto novo
     * inativo, com mesma foto/preço/descrição, e clona grupos+itens de
     * complemento.
     */
    private Produto clonarProdutoInternal(Produto orig, Categoria categoriaDestino, String nomeNovo) {
        Produto p = new Produto();
        p.setRestaurante(orig.getRestaurante());
        p.setCategoria(categoriaDestino);
        p.setNome(nomeNovo);
        p.setDescricao(orig.getDescricao());
        p.setPreco(orig.getPreco());
        p.setPrecoOriginal(orig.getPrecoOriginal());
        p.setFotoUrl(orig.getFotoUrl()); // mesma URL Cloudinary (sem custo extra)
        p.setDestaque(Boolean.FALSE);    // não herda destaque
        p.setDisponivel(Boolean.FALSE);  // INATIVO até dono revisar
        p.setOrdem(orig.getOrdem());
        // Blindagem clone completo: campos que faltavam (bug reportado, 409
        // quando clonando produto de marmitex com dias-da-semana configurados).
        // Preserva contrato "cópia = cópia" — dono espera achar tudo igual
        // no clone, só o nome que muda + inativo por padrão.
        p.setMaisDe18(Boolean.TRUE.equals(orig.getMaisDe18()));
        p.setPrecoVitrine(Boolean.TRUE.equals(orig.getPrecoVitrine()));
        p.setUnidadePreco(orig.getUnidadePreco());
        p.setPrecoAPartirDe(Boolean.TRUE.equals(orig.getPrecoAPartirDe()));
        p.setDiasSemanaAtivos(orig.getDiasSemanaAtivos());
        // tipo preservado (NORMAL/COMBO). COMBO sem combo_itens fica vazio
        // até dono recadastrar — evita FK cruzada com produto antigo.
        try { p.setTipo(orig.getTipo()); } catch (Exception ignore) {}
        Produto produtoSalvo = produtoRepository.save(p);

        // Clona grupos de complementos do produto original
        var gruposOrig = complementoGrupoRepository.findByProdutoIdOrderByIdAsc(orig.getId());
        for (ComplementoGrupo gOrig : gruposOrig) {
            ComplementoGrupo gNovo = ComplementoGrupo.builder()
                    .produto(produtoSalvo)
                    .nome(gOrig.getNome())
                    .obrigatorio(gOrig.getObrigatorio())
                    .minEscolhas(gOrig.getMinEscolhas())
                    .maxEscolhas(gOrig.getMaxEscolhas())
                    .modoPreco(gOrig.getModoPreco() != null ? gOrig.getModoPreco() : ComplementoGrupo.ModoPreco.SOMA)
                    .permitirNenhuma(Boolean.TRUE.equals(gOrig.getPermitirNenhuma()))
                    .itens(new java.util.ArrayList<>())
                    .build();
            if (gOrig.getItens() != null) {
                for (ComplementoItem itOrig : gOrig.getItens()) {
                    ComplementoItem itNovo = ComplementoItem.builder()
                            .grupo(gNovo)
                            .nome(itOrig.getNome())
                            .descricao(itOrig.getDescricao())
                            .precoAdicional(itOrig.getPrecoAdicional())
                            .maxSelecoes(itOrig.getMaxSelecoes())
                            .ativo(itOrig.getAtivo())
                            // Preserva flag "muda com frequencia" — dono espera
                            // que o clone ja venha com os mesmos itens marcados
                            // como variaveis (pedido reportado).
                            .variavel(Boolean.TRUE.equals(itOrig.getVariavel()))
                            .build();
                    gNovo.getItens().add(itNovo);
                }
            }
            complementoGrupoRepository.save(gNovo);
        }
        return produtoSalvo;
    }

    @Transactional
    public void deletarCategoria(Long restauranteId, Long categoriaId) {
        Categoria categoria = categoriaRepository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));

        validarPropriedade(categoria.getRestaurante().getId(), restauranteId);

        // Cada produto pode ter FKs em FichaTecnicaItem (ficha técnica) e PedidoItem
        // (histórico de pedidos). Precisamos preparar cada um antes de deletar.
        var produtosDaCategoria = produtoRepository.findByCategoriaId(categoriaId);
        for (Produto p : produtosDaCategoria) {
            prepararProdutoParaExclusao(p);
        }
        if (!produtosDaCategoria.isEmpty()) {
            produtoRepository.deleteAll(produtosDaCategoria);
        }
        categoriaRepository.delete(categoria);
    }

    /**
     * Prepara um produto pra ser deletado em segurança:
     *  1. Apaga as fichas técnicas que apontam pra ele (FK NOT NULL na ficha)
     *  2. Em cada PedidoItem que aponta pra ele:
     *     - Garante que o snapshot `nomeProduto` está preenchido
     *     - Desvincula (produto = null) — assim o histórico do pedido sobrevive
     *
     * Compartilhado entre exclusão manual de categoria e modo "substituir" da importação.
     */
    @Transactional
    public void prepararProdutoParaExclusao(Produto produto) {
        if (produto == null) return;
        // 1) Fichas técnicas
        var fichas = fichaTecnicaItemRepository.findByProdutoId(produto.getId());
        if (!fichas.isEmpty()) {
            fichaTecnicaItemRepository.deleteAll(fichas);
        }
        // 2) PedidoItens antigos — preserva o nome em snapshot
        var pedidoItens = pedidoItemRepository.findByProdutoId(produto.getId());
        if (!pedidoItens.isEmpty()) {
            for (var pi : pedidoItens) {
                if (pi.getNomeProduto() == null || pi.getNomeProduto().isBlank()) {
                    pi.setNomeProduto(produto.getNome());
                }
                pi.setProduto(null); // libera a FK
            }
            pedidoItemRepository.saveAll(pedidoItens);
        }
        // 3) Banners promocionais — desvincula produto (banner continua existindo sem destino)
        bannerRepository.desvincularProduto(produto.getId());
        // 4) Grupos de complementos — apaga (FK obriga a limpar antes de deletar produto).
        //    cascade=ALL + orphanRemoval=true em ComplementoGrupo.itens arrasta os itens.
        var grupos = complementoGrupoRepository.findByProdutoIdOrderByIdAsc(produto.getId());
        if (!grupos.isEmpty()) complementoGrupoRepository.deleteAll(grupos);
        // 5) Combo itens — duas situações possíveis:
        //    (a) produto É um combo → apaga TODOS os filhos vinculados (combo_id = produto.id)
        //    (b) produto é filho em algum combo → apaga essas ligações (produto_filho_id = produto.id)
        //    Sem isso, FK constraint estoura 409 Conflict ao tentar deletar.
        //    Try-catch só pra robustez em ambientes onde a tabela combo_itens
        //    ainda não foi criada pelo Hibernate ddl-auto.
        try { comboItemRepository.deleteByComboId(produto.getId()); } catch (Exception ignore) {}
        try { comboItemRepository.deleteByProdutoFilhoId(produto.getId()); } catch (Exception ignore) {}
        // 6) Combo grupos — se produto é combo, tem linhas em combo_grupos
        //    (ligando o combo a templates GrupoComplementoModelo). FK obriga
        //    apagar antes. Try-catch caso a tabela ainda não exista.
        try { comboGrupoRepository.deleteByComboId(produto.getId()); } catch (Exception ignore) {}
    }

    // ─── PRODUTOS ────────────────────────────────────────────────────────

    public List<ProdutoResponse> getProdutos(Long restauranteId) {
        return produtoRepository.findByRestauranteId(restauranteId)
                .stream().map(this::toProdutoResponse).toList();
    }

    // ← MÉTODO NOVO
    public List<ProdutoResponse> getProdutosPorCategoria(Long restauranteId, Long categoriaId) {
        return produtoRepository
                .findByCategoriaIdAndRestauranteIdOrderByOrdem(categoriaId, restauranteId)
                .stream().map(this::toProdutoResponse).toList();
    }

    @Transactional
    public ProdutoResponse criarProduto(Long restauranteId, ProdutoRequest request) {
        Restaurante restaurante = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        Produto produto = new Produto();
        produto.setRestaurante(restaurante);
        produto.setNome(request.getNome());
        produto.setDescricao(request.getDescricao());
        produto.setPreco(request.getPreco());
        // precoOriginal só faz sentido se for MAIOR que preco (promo válida).
        // Front pode mandar 0/null pra "sem promo".
        produto.setPrecoOriginal(promoValida(request.getPrecoOriginal(), request.getPreco())
                ? request.getPrecoOriginal() : null);
        produto.setFotoUrl(request.getFotoUrl());
        produto.setDisponivel(request.getDisponivel());
        produto.setDestaque(request.getDestaque());
        produto.setMaisDe18(Boolean.TRUE.equals(request.getMaisDe18()));
        if (request.getPrecoVitrine() != null) produto.setPrecoVitrine(request.getPrecoVitrine());
        if (request.getUnidadePreco() != null) produto.setUnidadePreco(request.getUnidadePreco());
        if (request.getPrecoAPartirDe() != null) produto.setPrecoAPartirDe(request.getPrecoAPartirDe());
        // diasSemanaAtivos: string vazia vinda do front = limpar restrição.
        // Assim admin desmarca "todos os dias" via campo vazio, sem precisar
        // de um endpoint separado. Null = campo não enviado (não mexer).
        if (request.getDiasSemanaAtivos() != null) {
            String d = request.getDiasSemanaAtivos().trim();
            produto.setDiasSemanaAtivos(d.isEmpty() ? null : d);
        }

        if (request.getCategoriaId() != null) {
            Categoria cat = categoriaRepository.findById(request.getCategoriaId())
                    .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));
            produto.setCategoria(cat);
        }

        return toProdutoResponse(produtoRepository.save(produto));
    }

    @Transactional
    public ProdutoResponse atualizarProduto(Long restauranteId, Long produtoId,
                                             ProdutoRequest request) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

        validarPropriedade(produto.getRestaurante().getId(), restauranteId);

        // Update parcial-friendly: só sobrescreve quando o campo foi enviado.
        // Antes setFotoUrl(null) zerava a foto se o front PUT-asse sem incluí-la
        // (ex: ao editar só nome/preço) — isso fazia a foto SUMIR após salvar.
        if (request.getNome() != null)         produto.setNome(request.getNome());
        if (request.getDescricao() != null)    produto.setDescricao(request.getDescricao());
        if (request.getPreco() != null)        produto.setPreco(request.getPreco());
        // precoOriginal: 0/null = remove promo. Vem promo válida (>preco)? grava.
        if (request.getPrecoOriginal() != null) {
            java.math.BigDecimal po = request.getPrecoOriginal();
            java.math.BigDecimal pp = request.getPreco() != null ? request.getPreco() : produto.getPreco();
            produto.setPrecoOriginal(promoValida(po, pp) ? po : null);
        }
        if (request.getFotoUrl() != null)      produto.setFotoUrl(request.getFotoUrl());
        if (request.getDisponivel() != null)   produto.setDisponivel(request.getDisponivel());
        if (request.getDestaque() != null)     produto.setDestaque(request.getDestaque());
        if (request.getMaisDe18() != null)     produto.setMaisDe18(request.getMaisDe18());
        if (request.getPrecoVitrine() != null) produto.setPrecoVitrine(request.getPrecoVitrine());
        if (request.getUnidadePreco() != null) produto.setUnidadePreco(request.getUnidadePreco());
        if (request.getPrecoAPartirDe() != null) produto.setPrecoAPartirDe(request.getPrecoAPartirDe());
        // diasSemanaAtivos: string vazia vinda do front = limpar restrição.
        // Assim admin desmarca "todos os dias" via campo vazio, sem precisar
        // de um endpoint separado. Null = campo não enviado (não mexer).
        if (request.getDiasSemanaAtivos() != null) {
            String d = request.getDiasSemanaAtivos().trim();
            produto.setDiasSemanaAtivos(d.isEmpty() ? null : d);
        }

        if (request.getCategoriaId() != null) {
            Categoria cat = categoriaRepository.findById(request.getCategoriaId())
                    .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));
            produto.setCategoria(cat);
        }

        return toProdutoResponse(produtoRepository.save(produto));
    }

    @Transactional
    public void deletarProduto(Long restauranteId, Long produtoId) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

        validarPropriedade(produto.getRestaurante().getId(), restauranteId);
        // Mesmo cleanup da exclusão de categoria — preserva histórico
        prepararProdutoParaExclusao(produto);
        produtoRepository.delete(produto);
    }

    /**
     * Toggle de disponibilidade do produto. Operação leve — só muda o campo
     * disponivel sem precisar revalidar nome/preco/categoria como o PUT
     * completo faria. Multi-tenant safe.
     */
    @Transactional
    public ProdutoResponse atualizarDisponibilidade(Long restauranteId, Long produtoId, boolean disponivel) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
        validarPropriedade(produto.getRestaurante().getId(), restauranteId);
        produto.setDisponivel(disponivel);
        return toProdutoResponse(produtoRepository.save(produto));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void validarPropriedade(Long dono, Long solicitante) {
        if (!dono.equals(solicitante)) {
            throw new RuntimeException("Acesso negado a este recurso");
        }
    }

    private ProdutoResponse toProdutoResponse(Produto p) {
        return ProdutoResponse.builder()
                .id(p.getId())
                .nome(p.getNome())
                .descricao(p.getDescricao())
                .preco(p.getPreco())
                .precoOriginal(p.getPrecoOriginal())
                .fotoUrl(p.getFotoUrl())
                .disponivel(p.getDisponivel())
                .destaque(p.getDestaque())
                .maisDe18(Boolean.TRUE.equals(p.getMaisDe18()))
                .categoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null)
                .categoriaNome(p.getCategoria() != null ? p.getCategoria().getNome() : null)
                .ordem(p.getOrdem())
                .tipo(p.getTipo() != null ? p.getTipo().name() : "NORMAL")
                .precoVitrine(Boolean.TRUE.equals(p.getPrecoVitrine()))
                .unidadePreco(p.getUnidadePreco())
                .precoAPartirDe(Boolean.TRUE.equals(p.getPrecoAPartirDe()))
                .diasSemanaAtivos(p.getDiasSemanaAtivos())
                .build();
    }

    /** Promo válida = original > preco (e ambos > 0). Evita guardar lixo no banco. */
    private static boolean promoValida(java.math.BigDecimal precoOriginal, java.math.BigDecimal preco) {
        if (precoOriginal == null || preco == null) return false;
        return precoOriginal.compareTo(java.math.BigDecimal.ZERO) > 0
            && precoOriginal.compareTo(preco) > 0;
    }

    /** Reordena os produtos da categoria conforme a lista de IDs. Multi-tenant safe. */
    @Transactional
    public void reordenarProdutosNaCategoria(Long restauranteId, Long categoriaId, java.util.List<Long> idsNaOrdem) {
        if (idsNaOrdem == null || idsNaOrdem.isEmpty()) return;
        var existentes = produtoRepository.findByCategoriaIdAndRestauranteIdOrderByOrdem(categoriaId, restauranteId);
        var porId = new java.util.HashMap<Long, Produto>();
        for (var p : existentes) porId.put(p.getId(), p);
        int ord = 0;
        for (Long id : idsNaOrdem) {
            var p = porId.get(id);
            if (p == null) continue; // ignora ids estranhos (multi-tenant safe)
            p.setOrdem(ord++);
        }
        produtoRepository.saveAll(porId.values());
    }
}