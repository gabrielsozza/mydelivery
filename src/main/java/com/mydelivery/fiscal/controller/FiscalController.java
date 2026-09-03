package com.mydelivery.fiscal.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.fiscal.config.FiscalConfig;
import com.mydelivery.fiscal.model.ContadorNumeroNfce;
import com.mydelivery.fiscal.model.PerfilFiscalRestaurante;
import com.mydelivery.fiscal.repository.ContadorNumeroNfceRepository;
import com.mydelivery.fiscal.repository.LogAuditoriaFiscalRepository;
import com.mydelivery.fiscal.repository.PerfilFiscalRestauranteRepository;
import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.fiscal.service.CategoriaTributariaService;
import com.mydelivery.fiscal.service.CertificadoService;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.mydelivery.fiscal.service.PerfilFiscalService;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints do módulo fiscal — apenas dono logado (ROLE_RESTAURANTE).
 * Todo endpoint valida a feature flag ({@link FiscalConfig}) antes de agir:
 * enquanto o módulo estiver desligado, devolve 503 SERVICE_UNAVAILABLE.
 */
@Slf4j
@RestController
@RequestMapping("/api/restaurante/fiscal")
@RequiredArgsConstructor
public class FiscalController {

    private final FiscalConfig fiscalConfig;
    private final RestauranteRepository restauranteRepo;
    private final PerfilFiscalRestauranteRepository perfilRepo;
    private final CertificadoService certificadoService;
    private final PerfilFiscalService perfilFiscalService;
    private final NfceEmissorService emissor;
    private final LogAuditoriaFiscalRepository auditoriaRepo;
    private final CategoriaTributariaService categoriaService;
    private final ContadorNumeroNfceRepository contadorRepo;

    // ── STATUS geral do módulo (pra o front decidir se mostra a aba) ──────
    @GetMapping("/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ativo", fiscalConfig.autorizadoParaRestaurante(r));
        out.put("cofreOk", true); // se o Spring subiu com FISCAL_MASTER_KEY, tá ok
        out.put("cert", certificadoService.statusCertificado(r.getId()));
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        out.put("temPerfil", perfil != null);
        out.put("emissaoAtiva", perfil != null && Boolean.TRUE.equals(perfil.getEmissaoAtiva()));
        return ResponseEntity.ok(out);
    }

    // ── PERFIL fiscal do restaurante (config) ────────────────────────────
    @GetMapping("/perfil")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> perfil(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) {
            out.put("existe", false);
            return ResponseEntity.ok(out);
        }
        out.put("existe", true);
        out.put("cnpj", p.getCnpj());
        out.put("razaoSocial", p.getRazaoSocial());
        out.put("nomeFantasia", p.getNomeFantasia());
        out.put("inscricaoEstadual", p.getInscricaoEstadual());
        out.put("inscricaoMunicipal", p.getInscricaoMunicipal());
        out.put("regimeTributario", p.getRegimeTributario());
        out.put("ambienteSefaz", p.getAmbienteSefaz());
        out.put("uf", p.getUf());
        out.put("municipioCodigoIbge", p.getMunicipioCodigoIbge());
        out.put("enderecoLogradouro", p.getEnderecoLogradouro());
        out.put("enderecoNumero", p.getEnderecoNumero());
        out.put("enderecoBairro", p.getEnderecoBairro());
        out.put("enderecoCep", p.getEnderecoCep());
        out.put("enderecoComplemento", p.getEnderecoComplemento());
        out.put("cscId", p.getCscId());
        out.put("temCsc", p.getCscCiphertext() != null && p.getCscCiphertext().length > 0);
        out.put("emissaoAtiva", Boolean.TRUE.equals(p.getEmissaoAtiva()));
        return ResponseEntity.ok(out);
    }

    /** Salva/atualiza perfil fiscal. Emissão sempre entra desligada — dono/contador
     *  liga só depois de tudo validado. */
    @PutMapping("/perfil")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> salvarPerfil(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId())
                .orElseGet(() -> PerfilFiscalRestaurante.builder()
                        .restaurante(r).cnpj(str(body, "cnpj", "")).uf(str(body, "uf", ""))
                        .regimeTributario(1).ambienteSefaz(2).emissaoAtiva(false)
                        .build());
        // Campos aceitos (só o que veio no body — patch parcial)
        if (body.containsKey("cnpj"))                p.setCnpj(somenteDigitos(str(body, "cnpj", "")));
        if (body.containsKey("razaoSocial"))         p.setRazaoSocial(str(body, "razaoSocial", null));
        if (body.containsKey("nomeFantasia"))        p.setNomeFantasia(str(body, "nomeFantasia", null));
        if (body.containsKey("inscricaoEstadual"))   p.setInscricaoEstadual(str(body, "inscricaoEstadual", null));
        if (body.containsKey("inscricaoMunicipal"))  p.setInscricaoMunicipal(str(body, "inscricaoMunicipal", null));
        if (body.containsKey("regimeTributario"))    p.setRegimeTributario(intOr(body, "regimeTributario", 1));
        if (body.containsKey("ambienteSefaz"))       p.setAmbienteSefaz(intOr(body, "ambienteSefaz", 2));
        if (body.containsKey("uf"))                  p.setUf(str(body, "uf", "").toUpperCase());
        if (body.containsKey("municipioCodigoIbge")) p.setMunicipioCodigoIbge(str(body, "municipioCodigoIbge", null));
        if (body.containsKey("enderecoLogradouro"))  p.setEnderecoLogradouro(str(body, "enderecoLogradouro", null));
        if (body.containsKey("enderecoNumero"))      p.setEnderecoNumero(str(body, "enderecoNumero", null));
        if (body.containsKey("enderecoBairro"))      p.setEnderecoBairro(str(body, "enderecoBairro", null));
        if (body.containsKey("enderecoCep"))         p.setEnderecoCep(str(body, "enderecoCep", null));
        if (body.containsKey("enderecoComplemento")) p.setEnderecoComplemento(str(body, "enderecoComplemento", null));
        if (body.containsKey("manifestoHabilitado")) p.setManifestoHabilitado(Boolean.TRUE.equals(body.get("manifestoHabilitado")));
        if (body.containsKey("mensagemRodape"))      p.setMensagemRodape(str(body, "mensagemRodape", null));
        if (body.containsKey("cfopEntradaPadrao"))   p.setCfopEntradaPadrao(str(body, "cfopEntradaPadrao", "1102"));
        // NÃO aceita alterar emissaoAtiva por aqui — endpoint separado exige checagens.
        perfilRepo.save(p);
        log.info("[Fiscal][Perfil] Restaurante {} salvou perfil (ambiente={}, uf={})",
                r.getId(), p.getAmbienteSefaz(), p.getUf());
        return ResponseEntity.ok(Map.of("ok", true, "id", p.getId()));
    }

    // ── UPLOAD do certificado A1 (.pfx) ───────────────────────────────────
    @PostMapping(path = "/certificado", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> uploadCertificado(
            @AuthenticationPrincipal String email,
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam("senha") String senha,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        PerfilFiscalRestaurante p = perfilRepo.findByRestauranteId(r.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cadastre o CNPJ da loja em 'Perfil fiscal' antes de subir o certificado."));
        if (p.getCnpj() == null || p.getCnpj().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CNPJ do perfil fiscal vazio.");
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie o arquivo .pfx");
        }
        try {
            var res = certificadoService.subirCertificado(
                    r, p.getCnpj(),
                    arquivo.getBytes(), senha,
                    email, ipDe(req));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("cnpj", res.cnpj());
            out.put("nomeTitular", res.nomeTitular());
            out.put("validoAte", res.validoAte().toString());
            out.put("fingerprint", res.fingerprint());
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        } catch (Exception e) {
            log.error("[Fiscal][Cert] erro inesperado no upload", e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Erro interno ao processar o certificado."));
        }
    }

    // ── CSC (Código de Segurança do Contribuinte) ────────────────────────
    /** Salva/troca o CSC. Corpo: { cscId, cscValor }. */
    @PutMapping("/csc")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> salvarCsc(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        String cscId = str(body, "cscId", "");
        String csc = str(body, "cscValor", "");
        try {
            perfilFiscalService.salvarCsc(r, cscId, csc, email, ipDe(req));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        }
    }

    // ── PRODUTOS: config fiscal individual + em lote ─────────────────────
    @GetMapping("/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarProdutos(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(perfilFiscalService.listarProdutosComFiscal(r.getId()));
    }

    @PutMapping("/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> salvarPerfilProduto(
            @AuthenticationPrincipal String email,
            @PathVariable("id") Long produtoId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        try {
            perfilFiscalService.salvarPerfilProduto(r, produtoId, body, email, ipDe(req));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "erro", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        }
    }

    /** Aplica a mesma config em vários produtos. Body: { produtoIds:[...], config:{...} } */
    @PostMapping("/produtos/lote")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> aplicarLote(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        List<Object> ids = (List<Object>) body.getOrDefault("produtoIds", List.of());
        Map<String, Object> config = (Map<String, Object>) body.getOrDefault("config", Map.of());
        List<Long> longs = ids.stream()
                .map(o -> { try { return Long.valueOf(String.valueOf(o)); } catch (Exception e) { return null; } })
                .filter(o -> o != null).toList();
        int n = perfilFiscalService.aplicarEmLote(r, longs, config, email, ipDe(req));
        return ResponseEntity.ok(Map.of("ok", true, "aplicados", n, "total", longs.size()));
    }

    // ── HABILITAR / DESABILITAR EMISSÃO ──────────────────────────────────
    @GetMapping("/pre-check")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> preCheck(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(perfilFiscalService.validarProntoParaEmitir(r.getId()));
    }

    @PostMapping("/habilitar-emissao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> habilitar(
            @AuthenticationPrincipal String email, HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        try {
            perfilFiscalService.ativarEmissao(r, email, ipDe(req));
            return ResponseEntity.ok(Map.of("ok", true, "emissaoAtiva", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "erro", e.getMessage(),
                    "preCheck", perfilFiscalService.validarProntoParaEmitir(r.getId())));
        }
    }

    @PostMapping("/desabilitar-emissao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> desabilitar(
            @AuthenticationPrincipal String email,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        String motivo = body == null ? null : str(body, "motivo", null);
        perfilFiscalService.desativarEmissao(r, email, ipDe(req), motivo);
        return ResponseEntity.ok(Map.of("ok", true, "emissaoAtiva", false));
    }

    // ── EMISSÃO NFC-e ────────────────────────────────────────────────────
    /** Emite (ou reemite se rejeitada) a NFC-e do pedido informado. */
    @PostMapping("/emitir-nfce/{pedidoId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> emitirNfce(
            @AuthenticationPrincipal String email,
            @PathVariable Long pedidoId,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        try {
            NotaFiscalEmitida n = emissor.emitirParaPedido(pedidoId, email, ipDe(req));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", n.getStatus() == NotaFiscalEmitida.Status.AUTORIZADA);
            out.put("notaId", n.getId());
            out.put("status", n.getStatus().name());
            out.put("cStat", n.getSefazCstat());
            out.put("motivo", n.getSefazMotivo());
            out.put("chaveAcesso", n.getChaveAcesso());
            out.put("protocolo", n.getProtocolo());
            out.put("qrCodeUrl", n.getQrcodeUrlConsulta());
            return ResponseEntity.ok(out);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        } catch (Exception e) {
            log.error("[Fiscal] Falha inesperada ao emitir NFC-e", e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Erro interno na emissão. Consulte a aba Auditoria."));
        }
    }

    /** Lista as notas emitidas pelo restaurante (mais recentes primeiro). */
    @GetMapping("/notas")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarNotas(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(emissor.listarNotas(r.getId()));
    }

    /**
     * Dados fiscais de UM pedido — usado pelo cupom da impressora térmica
     * pra montar o bloco DANFE-NFC-e (chave, QR, protocolo, número/série).
     * Devolve {@code {temNota: false}} se pedido não tem NFC-e autorizada.
     */
    @GetMapping("/pedido/{id}/dados-fiscais")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> dadosFiscaisDoPedido(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        var notas = emissor.listarNotas(r.getId());
        Map<String, Object> autorizada = notas.stream()
                .filter(n -> String.valueOf(n.get("pedidoId")).equals(String.valueOf(id)))
                .filter(n -> "AUTORIZADA".equals(n.get("status")))
                .findFirst().orElse(null);
        if (autorizada == null) return ResponseEntity.ok(Map.of("temNota", false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("temNota", true);
        out.put("chaveAcesso", autorizada.get("chaveAcesso"));
        out.put("numero", autorizada.get("numero"));
        out.put("serie", autorizada.get("serie"));
        out.put("ambiente", autorizada.get("ambiente"));
        out.put("protocolo", autorizada.get("protocolo"));
        out.put("qrCodeUrl", autorizada.get("qrCodeUrl"));
        out.put("emitidaEm", autorizada.get("emitidaEm"));
        return ResponseEntity.ok(out);
    }

    /**
     * Cancela nota autorizada (janela SEFAZ de 30 min).
     * Body: {@code { justificativa: "min 15 chars, max 255" }}
     */
    @PostMapping("/notas/{id}/cancelar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> cancelarNota(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Restaurante r = exigirAtivo(email);
        String justif = str(body, "justificativa", "");
        try {
            NotaFiscalEmitida n = emissor.cancelarNota(id, justif, email, ipDe(req));
            // Ownership check: só o dono da loja pode cancelar
            if (n.getRestaurante() == null || !n.getRestaurante().getId().equals(r.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false));
            }
            return ResponseEntity.ok(Map.of("ok", true, "status", n.getStatus().name()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        } catch (Exception e) {
            log.error("[Fiscal] Falha no cancelamento", e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Erro interno. Consulte a Auditoria."));
        }
    }

    // ── AUDITORIA fiscal (só as 200 ultimas do restaurante) ──────────────
    @GetMapping("/auditoria")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> auditoria(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        var lista = auditoriaRepo.findTop200ByRestauranteIdOrderByCriadoEmDesc(r.getId());
        var out = lista.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("operacao", l.getOperacao());
            m.put("resultado", l.getResultado());
            m.put("usuarioEmail", l.getUsuarioEmail());
            m.put("ipOrigem", l.getIpOrigem());
            m.put("detalhesJson", l.getDetalhesJson());
            m.put("criadoEm", l.getCriadoEm() == null ? null : l.getCriadoEm().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    // ─── CONTADOR de numeração NFC-e (série + próximo número) ────────────
    /**
     * Lê contador atual do restaurante — pra o dono ver série e próximo
     * número que a próxima NFC-e vai receber. Útil pra conferência.
     * Params: {@code serie} (default 1) e {@code ambiente} (default 2 = homol).
     */
    @GetMapping("/contador")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> lerContador(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "1") Integer serie,
            @RequestParam(defaultValue = "2") Integer ambiente) {
        Restaurante r = exigirAtivo(email);
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || perfil.getCnpj() == null || perfil.getCnpj().isBlank()) {
            return ResponseEntity.ok(Map.of("serie", serie, "proximoNumero", 1,
                    "ambiente", ambiente, "temPerfil", false));
        }
        var contador = contadorRepo.findByCnpjAndSerieAndAmbiente(perfil.getCnpj(), serie, ambiente).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serie", serie);
        out.put("ambiente", ambiente);
        out.put("proximoNumero", contador == null ? 1L : contador.getProximoNumero());
        out.put("temPerfil", true);
        out.put("existe", contador != null);
        return ResponseEntity.ok(out);
    }

    /**
     * Ajusta série + próximo número da NFC-e. Útil quando o dono está migrando
     * de outro sistema e precisa retomar a numeração de onde parou (ex: já
     * emitiu 8437 notas em outro emissor — configura próximo=8438).
     * <br>Body: {@code {serie: 1, ambiente: 2, proximoNumero: 8438}}
     */
    @PutMapping("/contador")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> ajustarContador(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = exigirAtivo(email);
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || perfil.getCnpj() == null || perfil.getCnpj().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "erro", "Preencha CNPJ do perfil fiscal antes de configurar contador."));
        }
        Integer serie = intOr(body, "serie", 1);
        Integer ambiente = intOr(body, "ambiente", 2);
        long proximo = Math.max(1, Long.parseLong(String.valueOf(body.getOrDefault("proximoNumero", 1))));

        var contador = contadorRepo.findByCnpjAndSerieAndAmbiente(perfil.getCnpj(), serie, ambiente)
                .orElseGet(() -> ContadorNumeroNfce.builder()
                        .cnpj(perfil.getCnpj()).serie(serie).ambiente(ambiente)
                        .proximoNumero(proximo).build());
        contador.setProximoNumero(proximo);
        contadorRepo.save(contador);
        log.info("[Fiscal][Contador] rest={} cnpj={} serie={} amb={} proximo={}",
                r.getId(), perfil.getCnpj(), serie, ambiente, proximo);
        return ResponseEntity.ok(Map.of("ok", true, "serie", serie,
                "ambiente", ambiente, "proximoNumero", proximo));
    }

    // ─── CATEGORIAS TRIBUTÁRIAS (Fase 2) ─────────────────────────────────
    /**
     * Lista categorias tributárias do restaurante. Se ainda não existir
     * nenhuma, semeia as 5 padrão (Águas, Cervejas, etc) e devolve elas.
     */
    @GetMapping("/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String,Object>>> listarCategorias(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        // Usa listarComoMap (dentro do @Transactional) pra evitar
        // LazyInitializationException ao acessar c.getProdutos().
        return ResponseEntity.ok(categoriaService.listarComoMap(r));
    }

    /** Cria/edita categoria tributária. Se {@code id=null} no body, cria. */
    @PutMapping("/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String,Object>> salvarCategoria(
            @AuthenticationPrincipal String email, @RequestBody Map<String,Object> body) {
        Restaurante r = exigirAtivo(email);
        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        try {
            var c = categoriaService.salvar(r, id, body);
            return ResponseEntity.ok(categoriaService.toMap(c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        }
    }

    /** Exclui categoria (categorias semente não podem ser excluídas). */
    @DeleteMapping("/categorias/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String,Object>> excluirCategoria(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        try { categoriaService.excluir(r, id); return ResponseEntity.ok(Map.of("ok", true)); }
        catch (IllegalStateException e) { return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage())); }
    }

    /**
     * Substitui a lista de produtos vinculados à categoria e propaga NCM/CFOP/CSOSN
     * pros PerfilFiscalProduto correspondentes — o que a emissão realmente lê.
     * Body: {@code { "produtoIds": [1, 2, 3] }}
     */
    @PutMapping("/categorias/{id}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String,Object>> vincularProdutosCategoria(
            @AuthenticationPrincipal String email, @PathVariable Long id,
            @RequestBody Map<String,Object> body) {
        Restaurante r = exigirAtivo(email);
        @SuppressWarnings("unchecked")
        var raw = (List<Object>) body.getOrDefault("produtoIds", List.of());
        List<Long> ids = raw.stream().map(o -> Long.valueOf(String.valueOf(o))).toList();
        var c = categoriaService.vincularProdutos(r, id, ids);
        return ResponseEntity.ok(categoriaService.toMap(c));
    }

    // ─── RELATÓRIO pro contador (Fase 4) ─────────────────────────────────
    /**
     * Baixa um ZIP com os XMLs autorizados do período + um resumo CSV que o
     * contador consegue abrir em qualquer sistema (Excel, ContNet, PROSOFT).
     * A URL retornada é streamada; nenhum arquivo intermediário fica no disco.
     */
    @GetMapping(path = "/relatorio.zip", produces = "application/zip")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<byte[]> relatorioZip(
            @AuthenticationPrincipal String email,
            @RequestParam(value = "dataInicial", required = false) String dataInicial,
            @RequestParam(value = "dataFinal",   required = false) String dataFinal) {
        Restaurante r = exigirAtivo(email);
        try {
            byte[] zip = emissor.montarRelatorioZip(r.getId(), dataInicial, dataFinal);
            String nome = "nfce-" + r.getSlug() + "-" + (dataInicial == null ? "tudo" : dataInicial)
                    + "_a_" + (dataFinal == null ? "hoje" : dataFinal) + ".zip";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + nome + "\"")
                    .header("Content-Type", "application/zip")
                    .body(zip);
        } catch (Exception e) {
            log.error("[Fiscal] falha no relatorio.zip rest={}", r.getId(), e);
            return ResponseEntity.internalServerError()
                    .body(("Falha ao gerar relatório: " + e.getMessage()).getBytes());
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private Restaurante exigirAtivo(String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        if (!fiscalConfig.autorizadoParaRestaurante(r)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Módulo fiscal indisponível pra esta loja.");
        }
        return r;
    }

    private static String str(Map<String,Object> b, String k, String def) {
        Object v = b == null ? null : b.get(k);
        return v == null ? def : String.valueOf(v).trim();
    }
    private static int intOr(Map<String,Object> b, String k, int def) {
        try { Object v = b.get(k); return v == null ? def : Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }
    private static String somenteDigitos(String s) {
        return s == null ? null : s.replaceAll("\\D", "");
    }
    private static String ipDe(HttpServletRequest req) {
        String x = req.getHeader("X-Forwarded-For");
        if (x != null && !x.isBlank()) return x.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
