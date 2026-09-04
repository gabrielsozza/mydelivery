package com.mydelivery.fiscal.controller;

import java.util.ArrayList;
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
import com.mydelivery.equipe.Permissao;
import com.mydelivery.equipe.PermissaoRequerida;
import com.mydelivery.fiscal.repository.NotaFiscalEntradaRepository;
import com.mydelivery.fiscal.service.CategoriaTributariaService;
import com.mydelivery.fiscal.service.CertificadoService;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.mydelivery.fiscal.service.NfceStorageService;
import com.mydelivery.fiscal.service.NfeEntradaService;
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
    private final NfeEntradaService nfeEntradaService;
    private final NotaFiscalEntradaRepository nfeEntradaRepo;
    private final NfceStorageService storage;
    private final com.mydelivery.fiscal.repository.NotaFiscalEmitidaRepository notaRepo;
    private final com.mydelivery.fiscal.service.NfeGateway gateway;

    @org.springframework.beans.factory.annotation.Value("${mydelivery.fiscal.gateway:simulador}")
    private String gatewayNome;

    // ── STATUS geral do módulo (pra o front decidir se mostra a aba) ──────
    @GetMapping("/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
        out.put("cscConfigurado", p.getCscCiphertext() != null && p.getCscCiphertext().length > 0);
        out.put("emissaoAtiva", Boolean.TRUE.equals(p.getEmissaoAtiva()));
        out.put("manifestoHabilitado", Boolean.TRUE.equals(p.getManifestoHabilitado()));
        out.put("mensagemRodape", p.getMensagemRodape());
        out.put("cfopEntradaPadrao", p.getCfopEntradaPadrao() == null ? "1102" : p.getCfopEntradaPadrao());
        // Anexa metadata do certificado (validade, se subiu) — evita chamada
        // extra no front pra montar a linha da tabela "Vencimento cert."
        var certStatus = certificadoService.statusCertificado(r.getId());
        if (certStatus != null) {
            out.put("temCertificado", certStatus.get("temCertificado"));
            out.put("validadeCertificado", certStatus.get("validoAte"));
            out.put("certificadoValido", Boolean.TRUE.equals(certStatus.get("temCertificado")));
        }
        return ResponseEntity.ok(out);
    }

    /** Salva/atualiza perfil fiscal. Emissão sempre entra desligada — dono/contador
     *  liga só depois de tudo validado. */
    @PutMapping("/perfil")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<List<Map<String, Object>>> listarProdutos(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(perfilFiscalService.listarProdutosComFiscal(r.getId()));
    }

    @PutMapping("/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> preCheck(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(perfilFiscalService.validarProntoParaEmitir(r.getId()));
    }

    @PostMapping("/habilitar-emissao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
            // Devolve a raiz da exception pro dono ver o motivo real (sem
            // mandar ele pra 'aba Auditoria' que não existe no painel).
            String raiz = e.getMessage();
            Throwable cur = e.getCause();
            while (cur != null && cur.getMessage() != null) { raiz = cur.getMessage(); cur = cur.getCause(); }
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Erro na emissão: " + (raiz == null ? e.getClass().getSimpleName() : raiz)));
        }
    }

    /**
     * Diagnóstico: qual gateway NFC-e está ativo neste boot. Usado pra confirmar
     * que o FISCAL_GATEWAY=real do Railway pegou. Se a env vier com whitespace
     * ou não estiver setada, aqui aparece 'simulador' e a nota sai com QR fake.
     */
    @GetMapping("/gateway/info")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> gatewayInfo(@AuthenticationPrincipal String email) {
        exigirAtivo(email);
        String prop = gatewayNome == null ? "" : gatewayNome.trim();
        String classe = gateway == null ? "null" : gateway.getClass().getSimpleName();
        boolean real = "WmixvideoNfeGateway".equals(classe);
        return ResponseEntity.ok(Map.of(
                "propertyValue", gatewayNome == null ? "" : gatewayNome,
                "propertyTrimmed", prop,
                "gatewayClasse", classe,
                "modoReal", real,
                "aviso", real ? "Gateway REAL — notas emitidas vão pra SEFAZ."
                              : "Gateway SIMULADOR — QR fake. Ative FISCAL_GATEWAY=real (sem espaços) no Railway."
        ));
    }

    /** Lista as notas emitidas pelo restaurante (mais recentes primeiro). */
    @GetMapping("/notas")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<List<Map<String, Object>>> listarNotas(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        return ResponseEntity.ok(emissor.listarNotas(r.getId()));
    }

    /**
     * Baixa o XML da NFC-e autorizada. O {@code xmlUrl} salvo na nota é um
     * caminho interno do storage (ex.: {@code r2://…}) e não é acessível pelo
     * navegador — este endpoint lê do storage e devolve como {@code text/xml}.
     */
    @GetMapping("/notas/{id}/xml")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<byte[]> baixarXml(@AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        var nota = notaRepo.findById(id).orElse(null);
        if (nota == null || nota.getRestaurante() == null
                || !nota.getRestaurante().getId().equals(r.getId())
                || nota.getChaveAcesso() == null) {
            return ResponseEntity.notFound().build();
        }
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || perfil.getCnpj() == null) return ResponseEntity.notFound().build();
        String xml = storage.lerXml(perfil.getCnpj(), nota.getChaveAcesso());
        if (xml == null || xml.isBlank()) return ResponseEntity.notFound().build();
        byte[] body = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"NFCe-" + nota.getChaveAcesso() + ".xml\"")
                .body(body);
    }

    /**
     * Deleta permanentemente todas as notas REJEITADAS do restaurante. Serve
     * pra limpar visualmente a listagem quando o dono estava depurando o
     * fluxo. NÃO afeta AUTORIZADAS ou CANCELADAS.
     */
    @DeleteMapping("/notas/rejeitadas")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> deletarRejeitadas(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        var todas = notaRepo.findByRestauranteIdOrderByCriadoEmDesc(r.getId());
        int removidas = 0;
        for (var n : todas) {
            if (n.getStatus() == com.mydelivery.fiscal.model.NotaFiscalEmitida.Status.REJEITADA) {
                notaRepo.delete(n);
                removidas++;
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "removidas", removidas));
    }

    /**
     * Descarta uma nota em CONTINGENCIA_EPEC (marca como REJEITADA). Útil pra
     * limpar notas presas quando o gateway de contingência quebrou e nunca vão
     * ser retransmitidas. Só funciona em CONTINGENCIA_EPEC — não afeta notas
     * autorizadas.
     */
    @PostMapping("/notas/{id}/descartar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> descartarNota(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        var nota = notaRepo.findById(id).orElse(null);
        if (nota == null || nota.getRestaurante() == null
                || !nota.getRestaurante().getId().equals(r.getId())) {
            return ResponseEntity.notFound().build();
        }
        if (nota.getStatus() != com.mydelivery.fiscal.model.NotaFiscalEmitida.Status.CONTINGENCIA_EPEC) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "erro", "Só notas em CONTINGENCIA_EPEC podem ser descartadas (estado atual: " + nota.getStatus() + ")"));
        }
        nota.setStatus(com.mydelivery.fiscal.model.NotaFiscalEmitida.Status.REJEITADA);
        nota.setSefazMotivo("Descartada manualmente pelo dono — contingência não retransmitida");
        nota.setProximaTentativaEm(null);
        notaRepo.save(nota);
        return ResponseEntity.ok(Map.of("ok", true, "status", nota.getStatus().name()));
    }

    /**
     * Diagnóstico do QR: retorna todos os campos usados no cálculo do hash
     * SHA-1 pra dono conferir com o portal SEFAZ. Ajuda a identificar quando
     * o CSC ou o cscId no perfil está diferente do cadastrado na SEFAZ.
     */
    @GetMapping("/notas/{id}/qr-debug")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> qrDebug(@AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        var nota = notaRepo.findById(id).orElse(null);
        if (nota == null || nota.getRestaurante() == null
                || !nota.getRestaurante().getId().equals(r.getId())
                || nota.getChaveAcesso() == null) {
            return ResponseEntity.notFound().build();
        }
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null) return ResponseEntity.notFound().build();
        String csc = perfilFiscalService.abrirCscParaUso(r.getId());
        int cscLen = csc == null ? 0 : csc.length();
        String cscMascara = cscLen == 0 ? "" :
                csc.substring(0, Math.min(4, cscLen)) + "…" + csc.substring(Math.max(0, cscLen - 4));
        // Recalcula o hash aqui — se der diferente do qrCodeUrl salvo, o CSC
        // no perfil foi ATUALIZADO depois da emissão da nota (fica desalinhado).
        String chave = nota.getChaveAcesso();
        Integer tpAmb = nota.getAmbiente();
        String cscId = perfil.getCscId() == null ? "1" : perfil.getCscId().trim();
        String dadosHash = chave + "|2|" + tpAmb + "|" + cscId + "|" + csc;
        String hashRecalculado = "";
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(dadosHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02X", b));
            hashRecalculado = sb.toString();
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chaveAcesso", chave);
        out.put("versaoQR", 2);
        out.put("ambiente", tpAmb);
        out.put("cscId", cscId);
        out.put("cscValorLength", cscLen);
        out.put("cscValorMascara", cscMascara);
        out.put("dadosParaHash", chave + "|2|" + tpAmb + "|" + cscId + "|<CSC-oculto>");
        out.put("hashRecalculado", hashRecalculado);
        out.put("qrUrlSalvoNoBanco", nota.getQrcodeUrlConsulta());
        return ResponseEntity.ok(out);
    }

    /**
     * Dados fiscais de UM pedido — usado pelo cupom da impressora térmica
     * pra montar o bloco DANFE-NFC-e (chave, QR, protocolo, número/série).
     * Devolve {@code {temNota: false}} se pedido não tem NFC-e autorizada.
     */
    @GetMapping("/pedido/{id}/dados-fiscais")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
            String raiz = e.getMessage();
            Throwable cur = e.getCause();
            while (cur != null && cur.getMessage() != null) { raiz = cur.getMessage(); cur = cur.getCause(); }
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Erro no cancelamento: " + (raiz == null ? e.getClass().getSimpleName() : raiz)));
        }
    }

    // ── AUDITORIA fiscal (só as 200 ultimas do restaurante) ──────────────
    @GetMapping("/auditoria")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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

    // ─── CATÁLOGO cClassTrib (reforma tributária IBS/CBS) ────────────────
    /**
     * Serve o catálogo oficial da Fazenda pra IBS/CBS filtrado só pra NFC-e
     * (43 códigos do universo de 1000+). Usado pelo front pra autocomplete/
     * dropdown na tela de categorias tributárias. Estático — muda quando a
     * Fazenda publicar nova versão do XLSX.
     */
    @GetMapping("/cclass-trib")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<org.springframework.core.io.Resource> cClassTrib() {
        var res = new org.springframework.core.io.ClassPathResource("fiscal/cclass-trib-nfce.json");
        return ResponseEntity.ok()
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cache-Control", "public, max-age=86400")   // 24h
                .body(res);
    }

    // ─── CATEGORIAS TRIBUTÁRIAS (Fase 2) ─────────────────────────────────
    /**
     * Lista categorias tributárias do restaurante. Se ainda não existir
     * nenhuma, semeia as 5 padrão (Águas, Cervejas, etc) e devolve elas.
     */
    @GetMapping("/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<List<Map<String,Object>>> listarCategorias(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        // Usa listarComoMap (dentro do @Transactional) pra evitar
        // LazyInitializationException ao acessar c.getProdutos().
        return ResponseEntity.ok(categoriaService.listarComoMap(r));
    }

    /** Cria/edita categoria tributária. Se {@code id=null} no body, cria. */
    @PutMapping("/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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
    @PermissaoRequerida(Permissao.VER_FISCAL)
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

    // ─── NF-e DE ENTRADA (fornecedor — upload manual) ─────────────────────
    /**
     * Upload de XML de NF-e recebida de fornecedor. Extrai chave, emitente,
     * valor, data emissão. Idempotente (mesma chave = mesma nota).
     */
    @PostMapping(path = "/notas-entrada/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> uploadNotaEntrada(
            @AuthenticationPrincipal String email,
            @RequestParam("arquivo") MultipartFile arquivo) {
        Restaurante r = exigirAtivo(email);
        try {
            var salva = nfeEntradaService.salvarUpload(r, arquivo.getBytes(), email);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", salva.getId());
            out.put("chaveAcesso", salva.getChaveAcesso());
            out.put("cnpjEmitente", salva.getCnpjEmitente());
            out.put("nomeEmitente", salva.getNomeEmitente());
            out.put("valorTotal", salva.getValorTotal());
            out.put("dataEmissao", salva.getDataEmissao() == null ? null : salva.getDataEmissao().toString());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "erro", e.getMessage()));
        } catch (Exception e) {
            log.error("[Fiscal][Entrada] Falha no upload rest={}", r.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false,
                    "erro", "Falha interna ao processar XML."));
        }
    }

    /** Lista NF-e recebidas do restaurante — mais recentes primeiro. */
    @GetMapping("/notas-entrada")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<List<Map<String, Object>>> listarNotasEntrada(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        var lista = nfeEntradaRepo.findByRestauranteIdOrderByDataEmissaoDesc(r.getId());
        var out = new ArrayList<Map<String, Object>>();
        for (var n : lista) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("chaveAcesso", n.getChaveAcesso());
            m.put("cnpjEmitente", n.getCnpjEmitente());
            m.put("nomeEmitente", n.getNomeEmitente());
            m.put("numero", n.getNumero());
            m.put("valorTotal", n.getValorTotal());
            m.put("dataEmissao", n.getDataEmissao() == null ? null : n.getDataEmissao().toString());
            m.put("criadoEm", n.getCriadoEm() == null ? null : n.getCriadoEm().toString());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Remove uma NF-e de entrada — só do próprio restaurante. */
    @DeleteMapping("/notas-entrada/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> removerNotaEntrada(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = exigirAtivo(email);
        var n = nfeEntradaRepo.findById(id).orElse(null);
        if (n == null || n.getRestaurante() == null || !r.getId().equals(n.getRestaurante().getId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false));
        }
        nfeEntradaRepo.delete(n);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── RELATÓRIO pro contador (Fase 4) ─────────────────────────────────
    /**
     * Baixa um ZIP com os XMLs autorizados do período + um resumo CSV que o
     * contador consegue abrir em qualquer sistema (Excel, ContNet, PROSOFT).
     * A URL retornada é streamada; nenhum arquivo intermediário fica no disco.
     */
    @GetMapping(path = "/relatorio.zip", produces = "application/zip")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
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

    // ─── FECHAMENTOS MENSAIS pré-gerados (cron dia 1º) ────────────────────
    /** Lista os "yyyy-MM" disponíveis pra download já pré-gerados. */
    @GetMapping("/fechamentos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<Map<String, Object>> listarFechamentos(@AuthenticationPrincipal String email) {
        Restaurante r = exigirAtivo(email);
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || perfil.getCnpj() == null) {
            return ResponseEntity.ok(Map.of("mesesDisponiveis", List.of()));
        }
        var meses = storage.listarRelatorios(perfil.getCnpj());
        return ResponseEntity.ok(Map.of("mesesDisponiveis", meses));
    }

    /** Baixa o ZIP mensal pré-gerado. {@code ym} = "yyyy-MM". */
    @GetMapping(path = "/fechamentos/{ym}", produces = "application/zip")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @PermissaoRequerida(Permissao.VER_FISCAL)
    public ResponseEntity<byte[]> baixarFechamento(
            @AuthenticationPrincipal String email, @PathVariable String ym) {
        Restaurante r = exigirAtivo(email);
        var perfil = perfilRepo.findByRestauranteId(r.getId()).orElse(null);
        if (perfil == null || perfil.getCnpj() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] zip = storage.lerRelatorio(perfil.getCnpj(), ym);
        if (zip == null) {
            // Fallback: gera on-demand se ainda não tem pré-gerado (comum no
            // mês corrente ou antes do 1º cron ter rodado).
            try {
                var yr = Integer.parseInt(ym.substring(0, 4));
                var mo = Integer.parseInt(ym.substring(5, 7));
                var ini = java.time.LocalDate.of(yr, mo, 1);
                var fim = ini.withDayOfMonth(ini.lengthOfMonth());
                zip = emissor.montarRelatorioZip(r.getId(), ini.toString(), fim.toString());
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        }
        String nome = "nfe-" + r.getSlug() + "-" + ym + ".zip";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + nome + "\"")
                .header("Content-Type", "application/zip")
                .body(zip);
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
