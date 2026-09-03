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
        }
        return lista;
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
        return catRepo.save(c);
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
        seeds.add(cat(r, "Produtos produzidos", "5102", "21069090", null, "102"));
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
        m.put("semente", c.getSemente());
        Set<Produto> ps = c.getProdutos() == null ? Collections.emptySet() : c.getProdutos();
        List<Map<String, Object>> prods = new ArrayList<>();
        for (var p : ps) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId()); pm.put("nome", p.getNome()); pm.put("ncm", c.getNcm());
            prods.add(pm);
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
