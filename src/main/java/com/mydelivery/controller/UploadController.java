package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CloudinaryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Upload genérico de imagens (produtos do cardápio).
 *
 * Endpoint /api/upload/imagem é o caminho que o frontend cardapio.html já usa
 * — mantém compatibilidade sem refatorar JS.
 *
 * Resposta JSON:
 *   { "url": "https://res.cloudinary.com/.../mydelivery/produtos/12/abc.jpg" }
 *
 * O frontend persiste essa URL no campo Produto.fotoUrl ao salvar.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UploadController {

    private final CloudinaryService cloudinary;
    private final RestauranteRepository restauranteRepository;

    /**
     * Aceita também "arquivo" como nome alternativo (compat com outros uploads
     * do projeto). Sem `consumes` restrito — Spring detecta multipart automaticamente.
     */
    @PostMapping("/api/upload/imagem")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, String>> upload(
            @AuthenticationPrincipal String email,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "arquivo", required = false) MultipartFile arquivo) {

        MultipartFile efetivo = file != null ? file : arquivo;
        if (efetivo == null || efetivo.isEmpty()) {
            log.warn("[Upload] Sem arquivo no request — esperado campo 'file' ou 'arquivo'");
            throw new RuntimeException("Envie o arquivo no campo 'file'.");
        }
        log.info("[Upload] Recebido — nome={}, tamanho={} bytes, content-type={}",
                efetivo.getOriginalFilename(), efetivo.getSize(), efetivo.getContentType());

        String subfolder = restauranteRepository.findByUsuarioEmail(email)
                .map(r -> "rest-" + r.getId())
                .orElse("anon");
        String url = cloudinary.upload(efetivo, subfolder);
        return ResponseEntity.ok(Map.of("url", url, "fotoUrl", url));
    }
}
