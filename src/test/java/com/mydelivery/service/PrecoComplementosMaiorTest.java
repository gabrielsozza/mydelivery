package com.mydelivery.service;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class PrecoComplementosMaiorTest {
    static ComplementoItem item(long id,String nome,String preco) {
        return ComplementoItem.builder().id(id).nome(nome).precoAdicional(new BigDecimal(preco)).ativo(true).build();
    }
    static ComplementoGrupo grupo(long id, ComplementoGrupo.ModoPreco modo,int min,int max,ComplementoItem... itens) {
        return ComplementoGrupo.builder().id(id).modoPreco(modo).minEscolhas(min).maxEscolhas(max).itens(List.of(itens)).build();
    }
    static List<ComplementoGrupo> grupos() {
        return List.of(grupo(1681,ComplementoGrupo.ModoPreco.MAIOR,2,2,
            item(62937,"BACON COM MUÇARELA","40.90"),item(62947,"BANANA NEVADA","33.90")),
            grupo(1682,ComplementoGrupo.ModoPreco.SOMA,0,5,item(62950,"COCA COLA 1,5L","10.90"),item(62951,"Sem gelo","0")));
    }
    static NovoPedidoRequest.ComplementoDto escolha(long grupo,long item,int qty) {
        var d=new NovoPedidoRequest.ComplementoDto();d.setGrupoId(grupo);d.setItemId(item);d.setQuantidade(qty);return d;
    }
    static Produto pizza() { var p=new Produto();p.setPrecoVitrine(true);p.setPreco(new BigDecimal("33.90"));return p; }
    static NovoPedidoRequest.ItemDto pedido() {
        var p=new NovoPedidoRequest.ItemDto();p.setPreco(new BigDecimal("85.70"));
        p.setObs("+ BACON COM MUÇARELA (R$ 40,90), BANANA NEVADA (R$ 33,90), COCA COLA 1 5L (R$ 10,90)");return p;
    }
    @Test void corrigeCarrinhoLegadoDaLeticiaSemSomarSabores() {
        assertThat(PrecoComplementosMaior.calcular(pizza(),pedido(),grupos(),new BigDecimal("33.90"))).isEqualByComparingTo("51.80");
    }
    @Test void usaCatalogoMesmoQuandoClienteEnviaPrecoIncorreto() {
        var p=pedido();p.setComplementos(List.of(escolha(1681,62937,1),escolha(1681,62947,1),escolha(1682,62950,2)));
        assertThat(PrecoComplementosMaior.calcular(pizza(),p,grupos(),BigDecimal.ZERO)).isEqualByComparingTo("62.70");
    }
    @Test void preservaBaseECalculaMaiorSeparadamenteEmCadaGrupo() {
        var prod=pizza();prod.setPrecoVitrine(false);
        var gs=new ArrayList<>(grupos());gs.add(grupo(3,ComplementoGrupo.ModoPreco.MAIOR,0,2,item(8,"Borda A","5"),item(9,"Borda B","7")));
        var p=pedido();p.setComplementos(List.of(escolha(1681,62937,1),escolha(1681,62947,1),escolha(3,8,1),escolha(3,9,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,gs,new BigDecimal("2"))).isEqualByComparingTo("49.90");
    }
    @Test void aceitaAdicionaisGratuitosEQuantidadeNaObservacaoAntiga() {
        var p=pedido();p.setObs(p.getObs()+", Sem gelo");
        p.setObs(p.getObs().replace("COCA COLA 1 5L (R$ 10,90)","COCA COLA 1 5L ×2 (R$ 21,80)"));
        assertThat(PrecoComplementosMaior.calcular(pizza(),p,grupos(),BigDecimal.ZERO)).isEqualByComparingTo("62.70");
    }
    @Test void rejeitaItemDeOutroGrupoOuQuantidadeInvalida() {
        for(var selecao: List.of(escolha(1681,62950,1),escolha(1681,62937,0),escolha(1681,62937,3))) {
            var p=pedido();p.setComplementos(List.of(selecao));
            assertThatThrownBy(()->PrecoComplementosMaior.calcular(pizza(),p,grupos(),BigDecimal.ZERO)).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void naoAlteraProdutosQueSomamAdicionais() {
        var prod=pizza();prod.setPrecoVitrine(false);
        assertThat(PrecoComplementosMaior.calcular(prod,pedido(),List.of(grupos().get(1)),BigDecimal.ZERO)).isNull();
    }
    @Test void precoVitrineCobraSomenteAsCervejasEscolhidasMesmoComPrecoAntigoNoCarrinho() {
        var cervejas=grupo(1913,ComplementoGrupo.ModoPreco.SOMA,1,5,
                item(63626,"Skol 330 ml","8.00"),item(63627,"Long neck","8.00"));
        var prod=new Produto();prod.setPreco(new BigDecimal("8.00"));prod.setPrecoVitrine(true);
        var p=new NovoPedidoRequest.ItemDto();p.setPreco(new BigDecimal("16.00"));
        p.setComplementos(List.of(escolha(1913,63626,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(cervejas),new BigDecimal("8.00")))
                .isEqualByComparingTo("8.00");
        p.setComplementos(List.of(escolha(1913,63626,1),escolha(1913,63627,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(cervejas),new BigDecimal("8.00")))
                .isEqualByComparingTo("16.00");
    }
    @Test void cobraMetadeDeCadaSaborMesmoQuandoSaoTres() {
        var sabores = grupo(10, ComplementoGrupo.ModoPreco.MEDIA, 1, 3,
                item(101,"Mussarela","60"), item(102,"Calabresa","80"), item(103,"Frango","90"));
        var bebida = grupo(11, ComplementoGrupo.ModoPreco.SOMA, 0, 2, item(104,"Refrigerante","10.90"));
        var prod = pizza(); prod.setPrecoVitrine(false);
        var p = pedido();
        p.setComplementos(List.of(escolha(10,101,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(sabores,bebida),new BigDecimal("25")))
                .isEqualByComparingTo("60.00");
        p.setComplementos(List.of(escolha(10,101,1),escolha(10,102,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(sabores,bebida),new BigDecimal("25")))
                .isEqualByComparingTo("70.00");
        p.setComplementos(List.of(escolha(10,101,1),escolha(10,102,1),escolha(10,103,1),escolha(11,104,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(sabores,bebida),new BigDecimal("25")))
                .isEqualByComparingTo("125.90");
    }
    @Test void tresSaboresDaImagemCustamMetadeDeCadaUm() {
        var sabores = grupo(10, ComplementoGrupo.ModoPreco.MEDIA, 1, 3,
                item(101,"Americano","69.90"), item(102,"Atum","99.00"), item(103,"Banana","66.00"));
        var prod = pizza(); prod.setPrecoVitrine(false);
        var p = pedido();
        p.setComplementos(List.of(escolha(10,101,1),escolha(10,102,1),escolha(10,103,1)));
        assertThat(PrecoComplementosMaior.calcular(prod,p,List.of(sabores),new BigDecimal("33.90")))
                .isEqualByComparingTo("117.45");
    }
    @Test void rejeitaRepeticaoDoMesmoSaborNaMedia() {
        var sabores = grupo(10, ComplementoGrupo.ModoPreco.MEDIA, 1, 3, item(101,"Mussarela","60"));
        var p = pedido(); p.setComplementos(List.of(escolha(10,101,2)));
        assertThatThrownBy(() -> PrecoComplementosMaior.calcular(pizza(),p,List.of(sabores),BigDecimal.ZERO))
                .isInstanceOf(ResponseStatusException.class);
    }
}
