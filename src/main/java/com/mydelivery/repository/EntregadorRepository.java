package com.mydelivery.repository;

import com.mydelivery.model.Entregador;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EntregadorRepository extends JpaRepository<Entregador, Long> {
    List<Entregador> findByRestauranteIdAndAtivoTrue(Long restauranteId);

    /** Login PIN multi-tenant: escopo pelo restaurante + PIN exato + ativo. */
    Optional<Entregador> findByRestauranteIdAndPinAndAtivoTrue(Long restauranteId, String pin);

    /** Checa colisão de PIN dentro do mesmo restaurante (PIN é único por loja, não global). */
    boolean existsByRestauranteIdAndPin(Long restauranteId, String pin);

    /**
     * Candidatos pra atribuição automática round-robin: ativos e disponíveis
     * (não em entrega no momento, não inativos). Ordem é só estável — quem
     * decide qual escolher é o service contando pedidos em andamento.
     */
    List<Entregador> findByRestauranteIdAndAtivoTrueAndStatus(Long restauranteId, Entregador.Status status);
}
