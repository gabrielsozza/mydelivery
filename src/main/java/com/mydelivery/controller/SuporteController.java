package com.mydelivery.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.SuporteAnexo;
import com.mydelivery.model.SuporteMensagem;
import com.mydelivery.model.SuporteTicket;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.SuporteAnexoRepository;
import com.mydelivery.repository.SuporteTicketRepository;
import com.mydelivery.service.SuporteService;
import com.mydelivery.service.SuporteService.AnexoDownload;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints da central de suporte. Todos exigem ROLE_RESTAURANTE.
 *
 * Multipart: /tickets e /tickets/{id}/mensagens recebem form-data com:
 *  - "texto" (obrigatório)
 *  - "assunto" (apenas no POST /tickets)
 *  - "anexos" (até 3 arquivos)
 *
 * Tenant: TODO endpoint que recebe ID de ticket usa findByIdAndRestauranteId
 * — impede que um restaurante leia/escreva no ticket de outro (IDOR).
 */
@RestController
@RequestMapping("/api/suporte")
@RequiredArgsConstructor
public class SuporteController {

    private final SuporteService suporteService;
    private final SuporteTicketRepository ticketRepository;
    private final SuporteAnexoRepository anexoRepository;
    private final RestauranteRepository restauranteRepository;

    @GetMapping("/tickets")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listar(@AuthenticationPrincipal String email) {
        // @Transactional aberto pra resolver as mensagens LAZY no loop abaixo —
        // com open-in-view=false (padrão), acessar t.getMensagens() fora de
        // transação dispara LazyInitException e o endpoint retorna 500,
        // fazendo a lista do painel ficar "Não foi possível carregar".
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        List<SuporteTicket> tickets = ticketRepository.findByRestauranteIdOrderByAtualizadoEmDesc(r.getId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SuporteTicket t : tickets) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("assunto", t.getAssunto());
            m.put("status", t.getStatus().name());
            m.put("categoria", t.getCategoria());
            m.put("criadoEm", t.getCriadoEm() != null ? t.getCriadoEm().toString() : null);
            m.put("atualizadoEm", t.getAtualizadoEm() != null ? t.getAtualizadoEm().toString() : null);
            m.put("totalMensagens", t.getMensagens() != null ? t.getMensagens().size() : 0);
            // Preview da última mensagem (até 80 chars, sem anexos)
            if (t.getMensagens() != null && !t.getMensagens().isEmpty()) {
                SuporteMensagem ult = t.getMensagens().get(t.getMensagens().size() - 1);
                String preview = ult.getTexto() != null ? ult.getTexto() : "";
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                m.put("ultimoTexto", preview);
                m.put("ultimoAutor", ult.getAutor().name());
            }
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> detalhar(@AuthenticationPrincipal String email,
                                                         @PathVariable Long id) {
        // @Transactional aberto pra resolver mensagens + anexos (ambos LAZY) durante
        // serializarTicket(). Sem isso: LazyInit no abrir → 500 → frontend não exibe
        // a conversa apesar do ticket existir.
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        SuporteTicket t = ticketRepository.findByIdAndRestauranteId(id, r.getId())
                .orElseThrow(() -> new RuntimeException("Ticket não encontrado"));
        return ResponseEntity.ok(serializarTicket(t));
    }

    @PostMapping(value = "/tickets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criar(@AuthenticationPrincipal String email,
                                                      @RequestParam("texto") String texto,
                                                      @RequestParam(value = "assunto", required = false) String assunto,
                                                      @RequestParam(value = "anexos", required = false) MultipartFile[] anexos) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        SuporteTicket t = suporteService.criarTicket(r, assunto, texto, anexos);
        return ResponseEntity.ok(serializarTicket(t));
    }

    @PostMapping(value = "/tickets/{id}/mensagens", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> enviarMensagem(@AuthenticationPrincipal String email,
                                                                @PathVariable Long id,
                                                                @RequestParam("texto") String texto,
                                                                @RequestParam(value = "anexos", required = false) MultipartFile[] anexos) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        SuporteTicket t = ticketRepository.findByIdAndRestauranteId(id, r.getId())
                .orElseThrow(() -> new RuntimeException("Ticket não encontrado"));
        suporteService.responder(t, r, texto, anexos);
        // Recarrega pra retornar com a mensagem nova
        SuporteTicket recarregado = ticketRepository.findById(t.getId()).orElseThrow();
        return ResponseEntity.ok(serializarTicket(recarregado));
    }

    /**
     * Serve o anexo. Tenant verificado anexo → mensagem → ticket → restaurante.
     *
     * Comportamento:
     *  - Anexo novo (Cloudinary): retorna 302 redirect pra secure_url. Cliente
     *    baixa direto do CDN, sem passar pelo backend.
     *  - Anexo legado (filesystem): stream do disco como antes (compatibilidade
     *    com anexos pré-migração).
     */
    @GetMapping("/anexos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<?> baixarAnexo(@AuthenticationPrincipal String email,
                                          @PathVariable Long id) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        SuporteAnexo anexo = anexoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Anexo não encontrado"));
        Long restauranteDono = anexo.getMensagem().getTicket().getRestaurante().getId();
        if (!restauranteDono.equals(r.getId())) {
            throw new RuntimeException("Acesso negado");
        }

        // Caminho novo: anexo no Cloudinary — redireciona pro CDN.
        if (anexo.getUrl() != null && !anexo.getUrl().isBlank()) {
            HttpHeaders h = new HttpHeaders();
            h.setLocation(java.net.URI.create(anexo.getUrl()));
            return new ResponseEntity<>(h, HttpStatus.FOUND);
        }

        // Caminho legado: anexo no filesystem — stream do disco.
        AnexoDownload dl = suporteService.abrirAnexoLegado(anexo);
        MediaType mt = MediaType.APPLICATION_OCTET_STREAM;
        try { if (dl.mimeType() != null) mt = MediaType.parseMediaType(dl.mimeType()); } catch (Exception ignored) {}
        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(org.springframework.http.ContentDisposition.inline()
                .filename(dl.nomeOriginal() != null ? dl.nomeOriginal() : "anexo")
                .build());
        if (dl.tamanhoBytes() != null) h.setContentLength(dl.tamanhoBytes());
        return ResponseEntity.ok().contentType(mt).headers(h).body(new InputStreamResource(dl.stream()));
    }

    // ── helpers ──

    private Map<String, Object> serializarTicket(SuporteTicket t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("assunto", t.getAssunto());
        m.put("status", t.getStatus().name());
        m.put("categoria", t.getCategoria());
        m.put("criadoEm", t.getCriadoEm() != null ? t.getCriadoEm().toString() : null);
        m.put("atualizadoEm", t.getAtualizadoEm() != null ? t.getAtualizadoEm().toString() : null);

        List<Map<String, Object>> msgs = new ArrayList<>();
        if (t.getMensagens() != null) {
            for (SuporteMensagem msg : t.getMensagens()) {
                Map<String, Object> mm = new HashMap<>();
                mm.put("id", msg.getId());
                mm.put("autor", msg.getAutor().name());
                mm.put("autorNome", msg.getAutorNome());
                mm.put("texto", msg.getTexto());
                mm.put("criadoEm", msg.getCriadoEm() != null ? msg.getCriadoEm().toString() : null);

                List<Map<String, Object>> anexos = new ArrayList<>();
                if (msg.getAnexos() != null) {
                    for (SuporteAnexo a : msg.getAnexos()) {
                        Map<String, Object> am = new HashMap<>();
                        am.put("id", a.getId());
                        am.put("nomeOriginal", a.getNomeOriginal());
                        am.put("mimeType", a.getMimeType());
                        am.put("tamanhoBytes", a.getTamanhoBytes());
                        // URL Cloudinary quando disponível. Frontend pode usar direto
                        // (CDN) e cair no endpoint /anexos/{id} apenas como fallback.
                        if (a.getUrl() != null) am.put("url", a.getUrl());
                        anexos.add(am);
                    }
                }
                mm.put("anexos", anexos);
                msgs.add(mm);
            }
        }
        m.put("mensagens", msgs);
        return m;
    }
}
