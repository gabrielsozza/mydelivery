package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "pedido_itens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PedidoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "pedido_id", nullable = false)
    private Pedido pedido;

    // produto AGORA é nullable: quando o restaurante exclui um produto, o histórico
    // de pedidos mantém o nome via campo `nomeProduto` (snapshot), mas a FK
    // pode ser zerada pra liberar o delete da Categoria/Produto.
    @ManyToOne
    @JoinColumn(name = "produto_id")
    private Produto produto;

    /** Snapshot do nome do produto no momento do pedido — sobrevive a exclusões. */
    @Column(name = "nome_produto", length = 200)
    private String nomeProduto;

    @Column(nullable = false)
    private Integer quantidade;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitario;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    /** TEXT (não VARCHAR 255). Combos geram obs hierárquica longa:
     *  "📦 Combo: Combo Inauguração ▸ Açaí 500ml #1 ▸ Cobertura: leite condensado
     *   ▸ Complementos: leite em pó, granola, frozen ▸ Açaí 500ml #2 ▸ ..."
     *  passa de 255 fácil — antes estourava "Data too long for column". */
    @Column(columnDefinition = "TEXT")
    private String observacao;

    @OneToMany(mappedBy = "pedidoItem", cascade = CascadeType.ALL)
    private List<PedidoItemComplemento> complementos;
}