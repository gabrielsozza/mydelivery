package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "complementos_grupo")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplementoGrupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @Column(nullable = false)
    private String nome;

    private Boolean obrigatorio = false;
    private Integer minEscolhas = 0;
    private Integer maxEscolhas = 1;

    // EAGER porque open-in-view=false: serialização do response fora da transação
    // disparava LazyInitException ao iterar getItens() → endpoint retornava 500
    // e o painel/cardápio ficavam sem complementos mesmo com dados no banco.
    @OneToMany(mappedBy = "grupo", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<ComplementoItem> itens;
}