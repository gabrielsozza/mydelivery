package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.cardapio.importacao.CardapioImportConfirmService;
import com.mydelivery.service.cardapio.importacao.CardapioImportService;
import com.mydelivery.service.cardapio.importacao.ImportException;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints de importação de cardápio via link.
 *
 * Fluxo:
 *  - POST /preview: cola URL → backend tenta extrair → devolve preview (não persiste).
 *  - POST /confirm: cliente edita/desmarca o que quiser e devolve o JSON → backend cria
 *                   categorias + produtos + upload imagens pro Cloudinary.
 */
@RestController
@RequestMapping("/api/cardapio/import")
@RequiredArgsConstructor
public class CardapioImportController {

    private final CardapioImportService importService;
    private final CardapioImportConfirmService confirmService;
    private final RestauranteRepository restauranteRepo;

    @PostMapping("/preview")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<?> preview(@RequestBody Map<String, String> body) {
        String url = body == null ? null : body.get("url");
        try {
            ResultadoImport r = importService.importarPreview(url);
            return ResponseEntity.ok(r);
        } catch (ImportException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("erro", e.getMessage()));
        }
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<?> confirmar(
            @AuthenticationPrincipal String email,
            @RequestBody ResultadoImport edicao) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        try {
            var resp = confirmService.confirmar(r, edicao);
            return ResponseEntity.ok(Map.of(
                    "produtosCriados", resp.produtosCriados(),
                    "categoriasCriadas", resp.categoriasCriadas(),
                    "imagensOk", resp.imagensOk(),
                    "imagensFalha", resp.imagensFalha()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("erro", e.getMessage()));
        }
    }
}
