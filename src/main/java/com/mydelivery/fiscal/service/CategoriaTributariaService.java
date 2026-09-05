package com.mydelivery.fiscal.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.fiscal.model.CategoriaTributaria;
import com.mydelivery.fiscal.model.PerfilFiscalProduto;
import com.mydelivery.fiscal.repository.CategoriaTributariaRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalProdutoRepository;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ProdutoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD de categorias tributárias + vínculo com produtos + propagação
 * pro {@link PerfilFiscalProduto} (que é o que a emissão da NFC-e consome).
 *
 * <p>Seed inicial: na primeira listagem do restaurante, se ele ainda não tem
 * nenhuma categoria, criamos as 5 padrão (Águas, Cervejas, Refrigerantes,
 * Sucos, Produtos produzidos) — o dono só precisa arrastar produtos pra dentro.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoriaTributariaService {

    private final CategoriaTributariaRepository catRepo;
    private final ProdutoRepository produtoRepo;
    private final PerfilFiscalProdutoRepository perfilProdutoRepo;

    @Transactional
    public List<CategoriaTributaria> listar(Restaurante r) {
        var lista = catRepo.findByRestauranteIdOrderByNomeAsc(r.getId());
        if (lista.isEmpty()) {
            lista = criarSeedPadrao(r);
        } else {
            // Auto-fix idempotente: se a semente "Produtos produzidos" foi
            // criada com CFOP 5102 (venda de mercadoria de terceiros — errado
            // pra quem PRODUZ), corrige pra 5101 (venda de produção do
            // estabelecimento). Roda uma vez, sem impacto se já tá certo.
            for (var c : lista) {
                if (Boolean.TRUE.equals(c.getSemente())
                        && "Produtos produzidos".equalsIgnoreCase(c.getNome())
                        && "5102".equals(c.getCfop())) {
                    c.setCfop("5101");
                    catRepo.save(c);
                    log.info("[Fiscal][Cat] Auto-fix CFOP 5102→5101 em 'Produtos produzidos' rest={}", r.getId());
                }
            }
        }
        return lista;
    }

    /**
     * Lista já convertida em Map — evita LazyInitializationException quando
     * o controller mapeia fora do @Transactional (produtos é lazy). Chame ESTE
     * do controller pra listagem web.
     */
    @Transactional
    public List<Map<String, Object>> listarComoMap(Restaurante r) {
        var lista = catRepo.findByRestauranteIdOrderByNomeAsc(r.getId());
        if (lista.isEmpty()) {
            lista = criarSeedPadrao(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : lista) {
            // Força inicialização da coleção lazy DENTRO da sessão
            c.getProdutos().size();
            out.add(toMap(c));
        }
        return out;
    }

    @Transactional
    public CategoriaTributaria salvar(Restaurante r, Long id, Map<String, Object> body) {
        CategoriaTributaria c = id == null
                ? CategoriaTributaria.builder().restaurante(r).build()
                : catRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada"));
        if (!c.getRestaurante().getId().equals(r.getId())) {
            throw new IllegalArgumentException("Categoria não pertence a esta loja");
        }
        c.setNome(str(body, "nome", c.getNome()));
        c.setCfop(str(body, "cfop", c.getCfop() == null ? "5102" : c.getCfop()));
        c.setNcm(str(body, "ncm", c.getNcm() == null ? "21069090" : c.getNcm()));
        c.setCest(strNull(body, "cest"));
        c.setOrigem(intOr(body, "origem", c.getOrigem() == null ? 0 : c.getOrigem()));
        c.setCsosnSN(str(body, "csosnSN", c.getCsosnSN() == null ? "102" : c.getCsosnSN()));
        c.setCstNormal(strNull(body, "cstNormal"));
        c.setAliquotaIcms(bd(body, "aliquotaIcms", BigDecimal.ZERO));
        c.setAliquotaPis(bd(body, "aliquotaPis", BigDecimal.ZERO));
        c.setAliquotaCofins(bd(body, "aliquotaCofins", BigDecimal.ZERO));
        c.setAliquotaIbs(bd(body, "aliquotaIbs", BigDecimal.ZERO));
        c.setAliquotaCbs(bd(body, "aliquotaCbs", BigDecimal.ZERO));
        c.setCstIbsCbs(strNull(body, "cstIbsCbs"));
        c.setCClassTrib(strNull(body, "cClassTrib"));
        CategoriaTributaria salvo = catRepo.save(c);
        // Propaga TODAS as mudanças pros produtos já vinculados a essa
        // categoria — antes só rodava no vincularProdutos, e editar a
        // categoria depois deixava o PerfilFiscalProduto com valores antigos
        // (dono trocava CFOP mas nota continuava saindo com CFOP velho).
        if (salvo.getProdutos() != null) {
            for (var p : salvo.getProdutos()) {
                try { propagarParaPerfil(p, salvo); } catch (Exception ignore) {}
            }
            log.info("[Fiscal][Cat] Salvou categoria {} — propagou pra {} produto(s) vinculado(s)",
                    salvo.getId(), salvo.getProdutos().size());
        }
        return salvo;
    }

    @Transactional
    public void excluir(Restaurante r, Long id) {
        var c = catRepo.findById(id).orElseThrow();
        if (!c.getRestaurante().getId().equals(r.getId())) throw new IllegalArgumentException("Não pertence");
        if (Boolean.TRUE.equals(c.getSemente())) {
            throw new IllegalStateException("Categoria padrão não pode ser excluída — só editada");
        }
        catRepo.delete(c);
    }

    /**
     * Vincula produtos à categoria e PROPAGA os valores fiscais pro perfil
     * de cada produto (que é o que a emissão consulta). Assim mudar a
     * categoria já reflete em novas emissões.
     */
    @Transactional
    public CategoriaTributaria vincularProdutos(Restaurante r, Long catId, List<Long> produtoIds) {
        var c = catRepo.findById(catId).orElseThrow();
        if (!c.getRestaurante().getId().equals(r.getId())) throw new IllegalArgumentException("Não pertence");
        Set<Produto> novos = new HashSet<>();
        for (Long pid : produtoIds == null ? Collections.<Long>emptyList() : produtoIds) {
            var p = produtoRepo.findById(pid).orElse(null);
            if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId())) continue;
            novos.add(p);
            propagarParaPerfil(p, c);
        }
        c.setProdutos(novos);
        return catRepo.save(c);
    }

    /**
     * Vincula a categoria a UM produto específico por ID. Uso direto: o
     * modal de correção rápida na tela de emissão pega o ID da mensagem
     * de erro do backend e chama aqui — garantido, sem depender de match
     * por nome (o item do pedido guarda snapshot que pode divergir do
     * nome atual do produto).
     */
    @Transactional
    public boolean vincularPorProdutoId(Restaurante r, Long catId, Long produtoId) {
        var c = catRepo.findById(catId).orElseThrow();
        if (!c.getRestaurante().getId().equals(r.getId())) throw new IllegalArgumentException("Nao pertence");
        var p = produtoRepo.findById(produtoId).orElse(null);
        if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId())) return false;
        Set<Produto> conjunto = c.getProdutos() == null ? new HashSet<>() : new HashSet<>(c.getProdutos());
        boolean novo = conjunto.add(p);
        c.setProdutos(conjunto);
        catRepo.save(c);
        propagarParaPerfil(p, c);
        log.info("[Fiscal][Cat] vincularPorProdutoId produto={} → categoria '{}' (novo={})",
                produtoId, c.getNome(), novo);
        return true;
    }

    /**
     * Vincula a categoria a TODOS os produtos ativos do restaurante que tem
     * o nome informado (case-insensitive). Cobre o caso comum onde existem
     * versoes duplicadas do mesmo produto (ex: "X-Tudo" do delivery e outro
     * "X-Tudo" da mesa) e o dono só ligou 1 na tela — a emissao rejeita
     * porque o outro ficou sem NCM. Retorna quantos foram vinculados agora.
     */
    @Transactional
    public int vincularPorNome(Restaurante r, Long catId, String nome) {
        var c = catRepo.findById(catId).orElseThrow();
        if (!c.getRestaurante().getId().equals(r.getId())) throw new IllegalArgumentException("Nao pertence");
        if (nome == null || nome.isBlank()) return 0;
        String alvo = nome.trim().toLowerCase();
        var todos = produtoRepo.findByRestauranteId(r.getId());
        int n = 0;
        Set<Produto> conjunto = c.getProdutos() == null ? new HashSet<>() : new HashSet<>(c.getProdutos());
        for (var p : todos) {
            if (p.getNome() == null) continue;
            if (!p.getNome().trim().toLowerCase().equals(alvo)) continue;
            if (conjunto.add(p)) {
                propagarParaPerfil(p, c);
                n++;
            }
        }
        c.setProdutos(conjunto);
        catRepo.save(c);
        log.info("[Fiscal][Cat] vincularPorNome '{}' → categoria '{}' vinculou {} novo(s)", nome, c.getNome(), n);
        return n;
    }

    /** Reaplica todas as categorias do restaurante nos produtos vinculados. */
    @Transactional
    public int repropagarTodas(Restaurante r) {
        var todas = catRepo.findByRestauranteIdOrderByNomeAsc(r.getId());
        int n = 0;
        for (var c : todas) {
            if (c.getProdutos() == null || c.getProdutos().isEmpty()) continue;
            for (var p : c.getProdutos()) {
                try { propagarParaPerfil(p, c); } catch (Exception ignore) {}
            }
            n++;
            log.info("[Fiscal][Cat] Re-propagou categoria '{}' → {} produto(s)", c.getNome(), c.getProdutos().size());
        }
        return n;
    }

    /** Diagnóstico do perfil fiscal de um produto — mostra o que está GRAVADO no banco. */
    public Map<String, Object> diagnosticoPerfilProduto(Long produtoId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        var pOpt = produtoRepo.findById(produtoId);
        if (pOpt.isEmpty()) { out.put("erro", "produto nao encontrado"); return out; }
        out.put("produtoId", produtoId);
        out.put("produtoNome", pOpt.get().getNome());
        var perfilOpt = perfilProdutoRepo.findByProdutoId(produtoId);
        if (perfilOpt.isEmpty()) { out.put("perfilFiscal", "NAO CONFIGURADO"); return out; }
        var pf = perfilOpt.get();
        out.put("ncm", pf.getNcm());
        out.put("cfop", pf.getCfop());
        out.put("cst", pf.getCst());
        out.put("csosn", pf.getCsosn());
        out.put("origem", pf.getOrigem());
        out.put("aliquotaIcms", pf.getAliquotaIcms());
        out.put("aliquotaPis", pf.getAliquotaPis());
        out.put("aliquotaCofins", pf.getAliquotaCofins());
        return out;
    }

    private void propagarParaPerfil(Produto p, CategoriaTributaria c) {
        var perfil = perfilProdutoRepo.findByProdutoId(p.getId())
                .orElseGet(() -> PerfilFiscalProduto.builder().produto(p).build());
        perfil.setNcm(c.getNcm());
        perfil.setCfop(c.getCfop());
        // NOTA: PerfilFiscalProduto ainda não tem CEST — só armazenado na
        // categoria por enquanto (usado quando gerarmos ST no futuro).
        perfil.setCsosn(c.getCsosnSN());
        perfil.setCst(c.getCstNormal());
        perfil.setOrigem(c.getOrigem() == null ? 0 : c.getOrigem());
        perfil.setAliquotaIcms(c.getAliquotaIcms());
        perfil.setAliquotaPis(c.getAliquotaPis());
        perfil.setAliquotaCofins(c.getAliquotaCofins());
        perfilProdutoRepo.save(perfil);
    }

    private List<CategoriaTributaria> criarSeedPadrao(Restaurante r) {
        log.info("[Fiscal][Cat] Semeando 5 categorias padrão pro restaurante {}", r.getId());
        List<CategoriaTributaria> seeds = new ArrayList<>();
        seeds.add(cat(r, "Águas", "5405", "22011000", "1701100", "500"));
        seeds.add(cat(r, "Cervejas", "5405", "22030000", "0302100", "500"));
        seeds.add(cat(r, "Refrigerantes", "5405", "22021000", "0301100", "500"));
        seeds.add(cat(r, "Sucos", "5101", "22011000", "1701300", "102"));
        seeds.add(cat(r, "Produtos produzidos", "5101", "21069090", null, "102"));
        for (var c : seeds) { c.setSemente(true); catRepo.save(c); }
        return catRepo.findByRestauranteIdOrderByNomeAsc(r.getId());
    }

    private CategoriaTributaria cat(Restaurante r, String nome, String cfop, String ncm, String cest, String csosn) {
        return CategoriaTributaria.builder()
                .restaurante(r).nome(nome).cfop(cfop).ncm(ncm).cest(cest)
                .csosnSN(csosn).origem(0)
                .aliquotaIcms(BigDecimal.ZERO)
                .aliquotaPis(BigDecimal.ZERO)
                .aliquotaCofins(BigDecimal.ZERO)
                .build();
    }

    public Map<String, Object> toMap(CategoriaTributaria c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("nome", c.getNome());
        m.put("cfop", c.getCfop());
        m.put("ncm", c.getNcm());
        m.put("cest", c.getCest());
        m.put("origem", c.getOrigem());
        m.put("csosnSN", c.getCsosnSN());
        m.put("cstNormal", c.getCstNormal());
        m.put("aliquotaIcms", c.getAliquotaIcms());
        m.put("aliquotaPis", c.getAliquotaPis());
        m.put("aliquotaCofins", c.getAliquotaCofins());
        m.put("aliquotaIbs", c.getAliquotaIbs());
        m.put("aliquotaCbs", c.getAliquotaCbs());
        m.put("cstIbsCbs", c.getCstIbsCbs());
        m.put("cClassTrib", c.getCClassTrib());
        m.put("semente", c.getSemente());
        List<Map<String, Object>> prods = new ArrayList<>();
        try {
            // Acesso lazy — se estivermos fora de @Transactional, pode falhar.
            // Nesse caso devolve lista vazia em vez de propagar exception.
            Set<Produto> ps = c.getProdutos() == null ? Collections.emptySet() : c.getProdutos();
            for (var p : ps) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", p.getId()); pm.put("nome", p.getNome()); pm.put("ncm", c.getNcm());
                prods.add(pm);
            }
        } catch (Exception e) {
            log.debug("[Cat] Lazy load produtos falhou (ok — retorno vazio): {}", e.toString());
        }
        m.put("produtos", prods);
        m.put("qtdProdutos", prods.size());
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private static String str(Map<String,Object> b, String k, String def) {
        Object v = b == null ? null : b.get(k);
        return v == null ? def : String.valueOf(v).trim();
    }
    private static String strNull(Map<String,Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
    private static int intOr(Map<String,Object> b, String k, int def) {
        try { Object v = b.get(k); return v == null ? def : Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }
    private static BigDecimal bd(Map<String,Object> b, String k, BigDecimal def) {
        try { Object v = b.get(k); return v == null ? def : new BigDecimal(String.valueOf(v).replace(',', '.').trim()); }
        catch (Exception e) { return def; }
    }
}
