package com.mydelivery.service.myhelp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mydelivery.model.Categoria;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.ComplementoItemRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;

class MyHelpMarkdownTableTest {
    @Test
    @SuppressWarnings("unchecked")
    void headingAndSixProductRowsAreOneReviewableAction() {
        var service = new MyHelpService(mock(ProdutoRepository.class), mock(RestauranteRepository.class),
                mock(CategoriaRepository.class), mock(ComplementoGrupoRepository.class),
                mock(ComplementoItemRepository.class), mock(MyHelpDominio.class),
                mock(MyHelpMemoriaService.class), mock(CacheManager.class));
        var restaurant = new Restaurante(); restaurant.setId(61L); restaurant.setSlug("xis-gordao-o-verdadeiro-x-gaucho");
        String message = "### Destaques Da Casa\n\n"
                + "| Produto | Preço | Descrição |\n| --- | --- | --- |\n"
                + "| 4 Xis Chama a Piazada | **R$ 149,90** | Escolha 4 Xis do nosso cardápio e monte o combo da piazada. Mais Xis, mais sabor e todo mundo satisfeito! |\n"
                + "| 2 Xis + Batata + 2 Refri Fechou o Rolê | **R$ 95,90** | Escolha 2 Xis + uma porção de batata + 2 Coca-Cola 350 ml. Chama a companhia e fecha o rolê! |\n"
                + "| 2 Xis Bacon em Dobro | **R$ 84,90** | Dobre o sabor com 2 Xis Bacon: pão redondo de 16 cm, maionese verde da casa, hambúrguer artesanal de 100 g, alface, tomate... |\n"
                + "| Xis Bacon | **R$ 46,90** | Pão redondo de 16 cm, maionese verde da casa, hambúrguer artesanal de 100 g, alface, tomate, milho, queijo muçarela... |\n"
                + "| Xis Gordão | **R$ 59,90** | Pão redondo de 16 cm, maionese verde da casa, hambúrguer artesanal de 100 g, alface, tomate, milho, queijo muçarela, bacon... |\n"
                + "| Xis Picanha | **R$ 59,90** | Pão redondo de 16 cm, maionese verde da casa, hambúrguer artesanal de 100 g, alface, tomate, milho, queijo muçarela... |";
        var preview = service.responder(restaurant, message);
        assertEquals("lote", preview.get("tipo"));
        var payload = (Map<String, Object>) preview.get("payload");
        assertEquals("novaCategoriaProdutos", payload.get("acao"));
        assertEquals("Destaques Da Casa", payload.get("categoriaNome"));
        var products = (List<Map<String, Object>>) payload.get("produtos");
        assertEquals(6, products.size());
        assertEquals(new BigDecimal("95.90"), products.get(1).get("preco"));
        assertEquals("Escolha 2 Xis + uma porção de batata + 2 Coca-Cola 350 ml. Chama a companhia e fecha o rolê!", products.get(1).get("descricao"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pastedSpreadsheetRowsKeepTheirLineBreaksAndFlattenedCopyIsRejected() {
        var service = new MyHelpService(mock(ProdutoRepository.class), mock(RestauranteRepository.class),
                mock(CategoriaRepository.class), mock(ComplementoGrupoRepository.class),
                mock(ComplementoItemRepository.class), mock(MyHelpDominio.class),
                mock(MyHelpMemoriaService.class), mock(CacheManager.class));
        var restaurant = new Restaurante(); restaurant.setId(61L);
        var valid = service.responder(restaurant, "Destaques Da Casa\nProduto\tPreço\tDescrição\nXis Bacon\tR$ 46,90\tDescrição completa...");
        assertEquals("lote", valid.get("tipo"));
        assertEquals("Destaques Da Casa", ((Map<String, Object>) valid.get("payload")).get("categoriaNome"));
        var flattened = service.responder(restaurant, "Crie a categoria Xis Gordão e nela adicione produtos: Produto\tPreço\tDescrição Xis Bacon\tR$ 46,90\tDescrição completa...");
        assertEquals("texto", flattened.get("tipo"));
        var incomplete = service.responder(restaurant, "### Destaques Da Casa\n| Produto | Preço | Descrição |\n| Xis Bacon | R$ 46,90 | Descrição completa |\n| Xis Picanha | R$ 59,90 |");
        assertEquals("texto", incomplete.get("tipo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsCategoryAndProductsFromOneMarkdownTableWithoutMixingDescriptions() {
        var products = mock(ProdutoRepository.class);
        var restaurants = mock(RestauranteRepository.class);
        var categories = mock(CategoriaRepository.class);
        var cache = mock(CacheManager.class);
        var service = new MyHelpService(products, restaurants, categories,
                mock(ComplementoGrupoRepository.class), mock(ComplementoItemRepository.class),
                mock(MyHelpDominio.class), mock(MyHelpMemoriaService.class), cache);
        var restaurant = new Restaurante(); restaurant.setId(1L); restaurant.setSlug("xis-gordao");
        String message = "Crie a categoria Xis Gordão O Verdadeiro X Gaúcho e nela adicione os produtos preço e descrição conforme esses itens:\n"
                + "| Produto | Preço | Descrição |\n| --- | --- | --- |\n"
                + "| Xis Gordão | **R$ 59,90** | Pão redondo de 16 cm, bacon... |\n"
                + "| Xis Burgao | **R$ 32,90** | Pão redondo de 16 cm, cebola... |";

        var preview = service.responder(restaurant, message);
        assertEquals("lote", preview.get("tipo"));
        assertEquals(3, ((List<?>) preview.get("itens")).size());
        var payload = (Map<String, Object>) preview.get("payload");
        assertEquals("novaCategoriaProdutos", payload.get("acao"));

        when(restaurants.findById(1L)).thenReturn(Optional.of(restaurant));
        when(categories.findByRestauranteIdOrderByOrdemAsc(1L)).thenReturn(List.of());
        when(categories.save(any())).thenAnswer(call -> {
            Categoria category = call.getArgument(0); category.setId(7L); return category;
        });
        when(products.findByRestauranteId(1L)).thenReturn(List.of());
        var result = service.confirmar(restaurant, payload);
        assertTrue(Boolean.TRUE.equals(result.get("alterado")));
        var captured = ArgumentCaptor.forClass(Produto.class);
        verify(products, org.mockito.Mockito.times(2)).save(captured.capture());
        assertEquals("Xis Gordão", captured.getAllValues().get(0).getNome());
        assertEquals(new BigDecimal("59.90"), captured.getAllValues().get(0).getPreco());
        assertEquals("Pão redondo de 16 cm, bacon...", captured.getAllValues().get(0).getDescricao());
        assertEquals("Xis Burgao", captured.getAllValues().get(1).getNome());
        assertEquals(new BigDecimal("32.90"), captured.getAllValues().get(1).getPreco());
        assertEquals("Pão redondo de 16 cm, cebola...", captured.getAllValues().get(1).getDescricao());
        assertEquals(7L, captured.getAllValues().get(1).getCategoria().getId());
    }
}
