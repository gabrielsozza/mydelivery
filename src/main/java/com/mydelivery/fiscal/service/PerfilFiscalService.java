package com.mydelivery.fiscal.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.fiscal.model.CertificadoDigital;
import com.mydelivery.fiscal.model.PerfilFiscalProduto;
import com.mydelivery.fiscal.model.PerfilFiscalRestaurante;
import com.mydelivery.fiscal.repository.CertificadoDigitalRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalProdutoRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalRestauranteRepository;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ProdutoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serviço central de configuração fiscal: CSC, perfil por produto e o
 * pre-flight que decide se a loja pode ligar {@code emissaoAtiva=true}.
 *
 * <p>Emissão nunca é ligada por um único clique — sempre passa por
 * {@link #validarProntoParaEmitir(Long)} que devolve uma lista clara de
 * pendências pro dono/contador resolver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerfilFiscalService {

    private final PerfilFiscalRestauranteRepository perfilRepo;
    private final PerfilFiscalProdutoRepository perfilProdutoRepo;
    private final CertificadoDigitalRepository certRepo;
    private final ProdutoRepository produtoRepo;
    private final CofreCertificadoService cofre;
    private final AuditoriaFiscalService auditoria;

    // ═════ CSC (Código de Segurança do Contribuinte) ═════

    /** Salva/troca o CSC do CNPJ. Grava criptografado. */
    @Transactional
    public void salvarCsc(Restaurante r, String cscId, String cscValor,
                          String usuarioEmail, String ipOrigem) {
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cadastre o perfil fiscal antes de gravar o CSC."));
        if (cscId == null || cscId.isBlank())
            throw new IllegalArgumentException("cscId obrigatório (ex: 000001)");
        if (cscValor == null || cscValor.length() < 20)
            throw new IllegalArgumentException("csc valor inválido (mínimo 20 chars)");
        if (p.getCnpj() == null || p.getCnpj().isBlank())
            throw new IllegalStateException("CNPJ do perfil vazio — preencha primeiro.");

        CofreCertificadoService.Cifrado cif = cofre.criptografar(cscValor, p.getCnpj());
        p.setCscId(cscId.trim());
        p.setCscCiphertext(cif.ciphertext());
        p.setCscIv(cif.iv());
        p.setCscTag(cif.tag());
        perfilRepo.save(p);

        auditoria.registrar(r.getId(), p.getCnpj(), usuarioEmail, "CSC_UPLOAD", "OK", ipOrigem,
                Map.of("cscId", cscId));
        log.info("[Fiscal][CSC] Restaurante {} CSC gravado (id={})", r.getId(), cscId);
    }

    /** Descriptografa CSC pra uso interno na assinatura NFC-e. */
    public String abrirCscParaUso(Long restauranteId) {
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(restauranteId)
                .orElseThrow(() -> new IllegalStateException("Perfil fiscal ausente"));
        if (p.getCscCiphertext() == null)
            throw new IllegalStateException("CSC não configurado");
        return cofre.descriptografarString(
                p.getCscCiphertext(), p.getCscIv(), p.getCscTag(), p.getCnpj());
    }

    // ═════ Config fiscal por PRODUTO ═════

    /** Lista todos os produtos do restaurante com seus perfis fiscais (se houver). */
    public List<Map<String, Object>> listarProdutosComFiscal(Long restauranteId) {
        List<Produto> produtos = produtoRepo.findByRestauranteId(restauranteId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Produto prod : produtos) {
            PerfilFiscalProduto pf = perfilProdutoRepo.findByProdutoId(prod.getId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("produtoId", prod.getId());
            m.put("nome", prod.getNome());
            m.put("preco", prod.getPreco());
            m.put("temFiscal", pf != null);
            if (pf != null) {
                m.put("ncm", pf.getNcm());
                m.put("cfop", pf.getCfop());
                m.put("cst", pf.getCst());
                m.put("csosn", pf.getCsosn());
                m.put("origem", pf.getOrigem());
                m.put("unidadeComercial", pf.getUnidadeComercial());
                m.put("aliquotaIcms", pf.getAliquotaIcms());
                m.put("aliquotaPis", pf.getAliquotaPis());
                m.put("aliquotaCofins", pf.getAliquotaCofins());
            }
            out.add(m);
        }
        return out;
    }

    /** Salva/atualiza perfil fiscal de UM produto. Body é PATCH parcial. */
    @Transactional
    public void salvarPerfilProduto(Restaurante r, Long produtoId, Map<String, Object> body,
                                    String usuarioEmail, String ipOrigem) {
        Produto prod = produtoRepo.findById(produtoId)
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado"));
        if (prod.getRestaurante() == null || !prod.getRestaurante().getId().equals(r.getId())) {
            throw new SecurityException("Produto não pertence à sua loja");
        }
        PerfilFiscalProduto pf = perfilProdutoRepo.findByProdutoId(produtoId)
                .orElseGet(() -> PerfilFiscalProduto.builder().produto(prod).build());
        if (body.containsKey("ncm"))              pf.setNcm(str(body, "ncm"));
        if (body.containsKey("cfop"))             pf.setCfop(str(body, "cfop"));
        if (body.containsKey("cst"))              pf.setCst(strOrNull(body, "cst"));
        if (body.containsKey("csosn"))            pf.setCsosn(strOrNull(body, "csosn"));
        if (body.containsKey("origem"))           pf.setOrigem(intOr(body, "origem", 0));
        if (body.containsKey("unidadeComercial")) pf.setUnidadeComercial(str(body, "unidadeComercial"));
        if (body.containsKey("aliquotaIcms"))     pf.setAliquotaIcms(dec(body, "aliquotaIcms"));
        if (body.containsKey("aliquotaPis"))      pf.setAliquotaPis(dec(body, "aliquotaPis"));
        if (body.containsKey("aliquotaCofins"))   pf.setAliquotaCofins(dec(body, "aliquotaCofins"));
        perfilProdutoRepo.save(pf);
        auditoria.registrar(r.getId(), null, usuarioEmail, "PERFIL_PRODUTO_SAVE", "OK", ipOrigem,
                Map.of("produtoId", produtoId, "ncm", pf.getNcm(), "cfop", pf.getCfop()));
    }

    /** Aplica config em lote — útil pra copiar mesma config em vários produtos. */
    @Transactional
    public int aplicarEmLote(Restaurante r, List<Long> produtoIds, Map<String, Object> config,
                             String usuarioEmail, String ipOrigem) {
        int n = 0;
        for (Long id : produtoIds) {
            try { salvarPerfilProduto(r, id, config, usuarioEmail, ipOrigem); n++; }
            catch (Exception e) { log.warn("[Fiscal][BulkPerfil] falhou id={}: {}", id, e.getMessage()); }
        }
        return n;
    }

    // ═════ Pre-flight de emissão ═════

    /**
     * Devolve mapa {@code { pronto: bool, pendencias: [strings], resumo: {...} }}.
     * Só quando {@code pronto=true} o dono pode ligar {@link #ativarEmissao}.
     */
    public Map<String, Object> validarProntoParaEmitir(Long restauranteId) {
        List<String> pend = new ArrayList<>();
        Map<String, Object> resumo = new LinkedHashMap<>();

        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(restauranteId).orElse(null);
        if (p == null) {
            pend.add("Cadastre o perfil fiscal da loja (CNPJ, regime, UF).");
            resumo.put("temPerfil", false);
            return Map.of("pronto", false, "pendencias", pend, "resumo", resumo);
        }
        resumo.put("temPerfil", true);
        resumo.put("ambiente", p.getAmbienteSefaz());
        resumo.put("uf", p.getUf());
        resumo.put("cnpj", p.getCnpj());

        if (p.getCnpj() == null || p.getCnpj().length() != 14) pend.add("CNPJ inválido no perfil fiscal.");
        if (p.getUf() == null || p.getUf().length() != 2) pend.add("Informe a UF da loja.");
        if (p.getMunicipioCodigoIbge() == null || p.getMunicipioCodigoIbge().isBlank())
            pend.add("Informe o código IBGE do município.");
        if (p.getInscricaoEstadual() == null || p.getInscricaoEstadual().isBlank())
            pend.add("Informe a Inscrição Estadual (ou 'ISENTO').");
        if (p.getRegimeTributario() == null) pend.add("Selecione o regime tributário.");
        if (p.getEnderecoCep() == null || p.getEnderecoCep().isBlank()) pend.add("Informe o CEP.");
        if (p.getEnderecoLogradouro() == null || p.getEnderecoLogradouro().isBlank())
            pend.add("Informe o endereço (logradouro).");
        if (p.getEnderecoNumero() == null || p.getEnderecoNumero().isBlank())
            pend.add("Informe o número do endereço.");
        if (p.getEnderecoBairro() == null || p.getEnderecoBairro().isBlank()) pend.add("Informe o bairro.");

        // CSC (obrigatório pra NFC-e)
        boolean temCsc = p.getCscCiphertext() != null && p.getCscCiphertext().length > 0
                && p.getCscId() != null && !p.getCscId().isBlank();
        resumo.put("temCsc", temCsc);
        if (!temCsc) pend.add("Cadastre o CSC (Código de Segurança do Contribuinte) — o contador pega no Portal SEFAZ.");

        // Certificado
        CertificadoDigital cert = certRepo.findByRestauranteIdAndAtivoTrue(restauranteId).orElse(null);
        resumo.put("temCertificado", cert != null);
        if (cert == null) {
            pend.add("Suba o certificado digital A1 (.pfx) da loja.");
        } else {
            resumo.put("certValidoAte", cert.getValidoAte().toString());
            if (cert.getValidoAte().isBefore(java.time.LocalDateTime.now())) {
                pend.add("Certificado A1 está EXPIRADO. Suba um novo.");
            }
        }

        // Produtos sem perfil
        List<Produto> produtos = produtoRepo.findByRestauranteId(restauranteId);
        int semFiscal = 0;
        for (Produto pr : produtos) {
            if (perfilProdutoRepo.findByProdutoId(pr.getId()).isEmpty()) semFiscal++;
        }
        resumo.put("totalProdutos", produtos.size());
        resumo.put("produtosSemFiscal", semFiscal);
        if (semFiscal > 0) {
            pend.add(semFiscal + " produto(s) sem NCM/CFOP configurado. Configure na aba 'Produtos'.");
        }

        boolean pronto = pend.isEmpty();
        return Map.of("pronto", pronto, "pendencias", pend, "resumo", resumo);
    }

    /**
     * Liga a emissão. Falha se pré-check não passar.
     * O dono pode a qualquer momento chamar {@link #desativarEmissao} pra desligar.
     */
    @Transactional
    public void ativarEmissao(Restaurante r, String usuarioEmail, String ipOrigem) {
        Map<String, Object> check = validarProntoParaEmitir(r.getId());
        if (!Boolean.TRUE.equals(check.get("pronto"))) {
            auditoria.registrar(r.getId(), null, usuarioEmail, "EMISSAO_ATIVAR", "NEGADO",
                    ipOrigem, Map.of("pendencias", check.get("pendencias")));
            throw new IllegalStateException("Existem pendências. Resolva antes de ativar.");
        }
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId()).orElseThrow();
        p.setEmissaoAtiva(true);
        perfilRepo.save(p);
        auditoria.registrar(r.getId(), p.getCnpj(), usuarioEmail, "EMISSAO_ATIVAR", "OK",
                ipOrigem, Map.of("ambiente", p.getAmbienteSefaz()));
        log.info("[Fiscal] Emissão ATIVADA — restaurante {} ambiente {}", r.getId(), p.getAmbienteSefaz());
    }

    @Transactional
    public void desativarEmissao(Restaurante r, String usuarioEmail, String ipOrigem, String motivo) {
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId()).orElseThrow();
        p.setEmissaoAtiva(false);
        perfilRepo.save(p);
        auditoria.registrar(r.getId(), p.getCnpj(), usuarioEmail, "EMISSAO_DESATIVAR", "OK",
                ipOrigem, Map.of("motivo", motivo == null ? "não informado" : motivo));
        log.info("[Fiscal] Emissão DESATIVADA — restaurante {} motivo={}", r.getId(), motivo);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static String str(Map<String,Object> b, String k) {
        Object v = b.get(k); return v == null ? "" : String.valueOf(v).trim();
    }
    private static String strOrNull(Map<String,Object> b, String k) {
        Object v = b.get(k); return v == null ? null : String.valueOf(v).trim();
    }
    private static int intOr(Map<String,Object> b, String k, int def) {
        try { Object v = b.get(k); return v == null ? def : Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }
    private static java.math.BigDecimal dec(Map<String,Object> b, String k) {
        try { Object v = b.get(k); return v == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(String.valueOf(v)); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }
}
