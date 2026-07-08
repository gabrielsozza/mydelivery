package com.mydelivery.equipe;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MembroEquipeRepository extends JpaRepository<MembroEquipe, Long> {

    /** Login usado no /auth/login quando não é email. Multi-tenant scan. */
    Optional<MembroEquipe> findByLoginIgnoreCase(String login);

    /**
     * Versão pro fluxo de login: JOIN FETCH em restaurante + usuario pra o
     * AuthService conseguir ler {@code m.getRestaurante().getUsuario().getEmail()}
     * SEM cair em LazyInitializationException — o service não é @Transactional
     * (não pode ser: side effects como emitir JWT/registrar auditoria) e a
     * sessão já fechou quando ele acessa as relações.
     */
    @org.springframework.data.jpa.repository.Query("""
        select m from MembroEquipe m
          join fetch m.restaurante r
          join fetch r.usuario
         where lower(m.login) = lower(?1)
        """)
    Optional<MembroEquipe> findParaLoginByLoginIgnoreCase(String login);

    List<MembroEquipe> findByRestauranteIdOrderByNomeCompletoAsc(Long restauranteId);

    boolean existsByLoginIgnoreCase(String login);

    /** Só carrega tokenVersion — usado pelo filter no path quente. */
    @org.springframework.data.jpa.repository.Query(
        "select m.tokenVersion from MembroEquipe m where m.id = ?1")
    Optional<Integer> findTokenVersionById(Long id);
}
