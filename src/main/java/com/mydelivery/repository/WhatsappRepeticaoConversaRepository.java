package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.WhatsappRepeticaoConversa;

import jakarta.persistence.LockModeType;

public interface WhatsappRepeticaoConversaRepository extends JpaRepository<WhatsappRepeticaoConversa, Long> {
    Optional<WhatsappRepeticaoConversa> findTopByRestauranteIdAndTelefoneHashAndExpiraEmAfterOrderByIdDesc(
            Long restauranteId, String telefoneHash, LocalDateTime agora);

    Optional<WhatsappRepeticaoConversa> findTopByRestauranteIdAndTelefoneHashOrderByIdDesc(
            Long restauranteId, String telefoneHash);

    Optional<WhatsappRepeticaoConversa> findByRestauranteIdAndTelefoneHashAndPedidoReferenciaId(
            Long restauranteId, String telefoneHash, Long pedidoReferenciaId);

    @Query("select c from WhatsappRepeticaoConversa c where c.linkTokenHash = :hash "
            + "and c.linkTokenExpiraEm > :agora and c.restaurante.id = :restauranteId")
    Optional<WhatsappRepeticaoConversa> findByLinkToken(@Param("hash") String hash,
            @Param("agora") LocalDateTime agora, @Param("restauranteId") Long restauranteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WhatsappRepeticaoConversa c where c.id = :id")
    Optional<WhatsappRepeticaoConversa> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(c) from WhatsappRepeticaoConversa c where c.restaurante.id = :restauranteId "
            + "and c.ofertadoEm between :inicio and :fim")
    long contarOfertas(@Param("restauranteId") Long restauranteId,
            @Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    @Query("select count(distinct c.telefoneHash) from WhatsappRepeticaoConversa c where c.restaurante.id = :restauranteId "
            + "and c.aceitoEm between :inicio and :fim")
    long contarAceites(@Param("restauranteId") Long restauranteId,
            @Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    @Query("select count(distinct c.telefoneHash) from WhatsappRepeticaoConversa c where c.restaurante.id = :restauranteId "
            + "and c.pedidoCriado is not null and c.ofertadoEm between :inicio and :fim")
    long contarClientesConvertidos(@Param("restauranteId") Long restauranteId,
            @Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    @Query("select c from WhatsappRepeticaoConversa c where c.restaurante.id = :restauranteId "
            + "and c.expiraEm < :agora and c.estado in :estados")
    List<WhatsappRepeticaoConversa> findExpiradas(@Param("restauranteId") Long restauranteId,
            @Param("agora") LocalDateTime agora,
            @Param("estados") List<WhatsappRepeticaoConversa.Estado> estados);
}
