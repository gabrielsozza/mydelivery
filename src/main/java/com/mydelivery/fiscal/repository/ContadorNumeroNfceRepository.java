package com.mydelivery.fiscal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.fiscal.model.ContadorNumeroNfce;

import jakarta.persistence.LockModeType;

public interface ContadorNumeroNfceRepository
        extends JpaRepository<ContadorNumeroNfce, Long> {

    Optional<ContadorNumeroNfce> findByCnpjAndSerieAndAmbiente(String cnpj, Integer serie, Integer ambiente);

    /**
     * Lock pessimista pra reservar o próximo número sem race entre pedidos
     * simultâneos. Deve ser chamado DENTRO de uma @Transactional — o lock
     * dura até o commit.
     *
     * Fluxo: findForUpdate → incrementa proximoNumero → save → emite.
     * Se emitir falhar, ainda vale numeração (evita gap na SEFAZ).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ContadorNumeroNfce c "
         + " WHERE c.cnpj = :cnpj AND c.serie = :serie AND c.ambiente = :ambiente")
    Optional<ContadorNumeroNfce> findForUpdate(@Param("cnpj") String cnpj,
                                                @Param("serie") Integer serie,
                                                @Param("ambiente") Integer ambiente);
}
