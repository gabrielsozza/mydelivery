package com.mydelivery.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.dto.publico.CategoriaPublicResponse;
import com.mydelivery.dto.publico.ProdutoPublicResponse;
import com.mydelivery.dto.publico.RestaurantePublicResponse;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.PedidoService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicController {

    private final RestauranteRepository restauranteRepo;
    private final CategoriaRepository categoriaRepo;
    private final ProdutoRepository produtoRepo;
    private final PedidoService pedidoService;
    private final ConfiguracaoRestauranteRepository configRepo;

    @GetMapping("/restaurante/{slug}")
    public ResponseEntity<RestaurantePublicResponse> getRestaurante(@PathVariable String slug) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));

        // Public Key do MP é segura de expor (designada como pública pelo MP) —
        // usada pelo SDK no browser pra tokenizar cartão sem expor o access token.
        String mpPublicKey = configRepo.findByRestauranteId(r.getId())
                .map(cfg -> cfg.getMpPublicKey())
                .orElse(null);

        RestaurantePublicResponse response = RestaurantePublicResponse.builder()
                .nome(r.getNome())
                .descricao(r.getDescricao())
                .logoUrl(r.getLogoUrl())
                .capaUrl(r.getCapaUrl())
                .corPrimaria(r.getCorPrimaria())
                .aberto(r.getAberto())
                .tempoEntrega(r.getTempoEntrega())
                .taxaEntrega(r.getTaxaEntrega())
                .pedidoMinimo(r.getPedidoMinimo())
                .modos(r.getModos())
                .pagamentos(r.getPagamentos())
                .bairrosAtendidos(r.getBairrosAtendidos())
                .mpPublicKey(mpPublicKey)
                .build();

        // no-cache: cliente final sempre recebe dados atuais (logo, capa, status)
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(response);
    }

    @GetMapping("/cardapio/{slug}")
    public ResponseEntity<List<CategoriaPublicResponse>> getCardapio(@PathVariable String slug) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));

        List<CategoriaPublicResponse> cardapio = categoriaRepo
                .findByRestauranteAndAtivoTrueOrderByOrdem(r)
                .stream()
                .map(cat -> {
                    List<ProdutoPublicResponse> produtos = produtoRepo
                            .findByCategoriaAndDisponivelTrueOrderByOrdem(cat)
                            .stream()
                            .map(p -> ProdutoPublicResponse.builder()
                            .id(p.getId())
                            .nome(p.getNome())
                            .desc(p.getDescricao())
                            .preco(p.getPreco())
                            .precoOriginal(p.getPrecoOriginal())
                            .imgUrl(p.getFotoUrl())
                            .destaque(Boolean.TRUE.equals(p.getDestaque()))
                            .build())
                            .toList();

                    return CategoriaPublicResponse.builder()
                            .id(cat.getId())
                            .nome(cat.getNome())
                            .produtos(produtos)
                            .build();
                })
                .toList();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(cardapio);
    }

    @PostMapping("/pedido")
    public ResponseEntity<Void> novoPedido(@RequestBody NovoPedidoRequest request) {
        pedidoService.criarPedido(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
