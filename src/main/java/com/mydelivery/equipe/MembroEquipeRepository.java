package com.mydelivery.equipe;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MembroEquipeRepository extends JpaRepository<MembroEquipe, Long> {

    /** Login usado no /auth/login quando não é email. Multi-tenant scan. */
    Optional<MembroEquipe> findByLoginIgnoreCase(String login);

    List<MembroEquipe> findByRestauranteIdOrderByNomeCompletoAsc(Long restauranteId);

    boolean existsByLoginIgnoreCase(String login);

    /** Só carrega tokenVersion — usado pelo filter no path quente. */
    @org.springframework.data.jpa.repository.Query(
        "select m.tokenVersion from MembroEquipe m where m.id = ?1")
    Optional<Integer> findTokenVersionById(Long id);
}
