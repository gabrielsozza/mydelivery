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

    @OneToMany(mappedBy = "grupo", cascade = CascadeType.ALL)
    private List<ComplementoItem> itens;
}