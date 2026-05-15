package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.CarrinhoAbandonado;

public interface CarrinhoAbandonadoRepository extends JpaRepository<CarrinhoAbandonado, Long> {

    Optional<CarrinhoAbandonado> findBySessionIdAndStatus(String sessionId, CarrinhoAbandonado.Status status);

    List<CarrinhoAbandonado> findByRestauranteIdAndStatus(Long restauranteId, CarrinhoAbandonado.Status status);

    // busca carrinhos ATIVOS criados há mais de X minutos e ainda não notificados
    List<CarrinhoAbandonado> findByStatusAndCriadoEmBefore(
        CarrinhoAbandonado.Status status,
        LocalDateTime limite
    );

    List<CarrinhoAbandonado> findByRestauranteSlugOrderByCriadoEmDesc(String slug);

    // para relatório do admin
    long countByRestauranteIdAndStatus(Long restauranteId, CarrinhoAbandonado.Status status);

    // usado quando um pedido é criado: pega todos os carrinhos abertos do mesmo
    // telefone no mesmo restaurante (ATIVO ou NOTIFICADO) pra marcar como RECUPERADO
    List<CarrinhoAbandonado> findByRestauranteIdAndTelefoneClienteAndStatusIn(
        Long restauranteId,
        String telefoneCliente,
        List<CarrinhoAbandonado.Status> statusList
    );
}