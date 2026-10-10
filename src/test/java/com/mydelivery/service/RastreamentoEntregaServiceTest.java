package com.mydelivery.service;

import com.mydelivery.model.Cliente;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RastreamentoEntregaServiceTest {
    private PedidoRepository pedidos;
    private JdbcTemplate jdbc;
    private RastreamentoEntregaService service;
    private Pedido pedido;

    @BeforeEach void setup() {
        pedidos = mock(PedidoRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new RastreamentoEntregaService(pedidos, jdbc);
        Restaurante loja = new Restaurante(); loja.setId(51L); loja.setNome("Loja teste");
        loja.setRastreamentoEntregaAtivo(true); loja.setEntregaCodigoConfirmacaoAtivo(true);
        Entregador entregador = new Entregador(); entregador.setId(9L); entregador.setRestaurante(loja);
        Cliente cliente = new Cliente(); cliente.setNome("Cliente"); cliente.setTelefone("5511999999999");
        pedido = new Pedido(); pedido.setId(123L); pedido.setRestaurante(loja); pedido.setEntregador(entregador);
        pedido.setCliente(cliente); pedido.setTipo(Pedido.Tipo.DELIVERY); pedido.setStatus(Pedido.Status.SAIU_ENTREGA);
        when(pedidos.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test void geraCredenciaisAleatoriasEArmazenaSomenteHashes() throws Exception {
        var inicio = service.iniciar(pedido, true);
        assertThat(inicio.token()).hasSizeGreaterThan(40);
        assertThat(inicio.codigo()).matches("\\d{6}");
        assertThat(pedido.getRastreamentoTokenHash()).isNotEqualTo(inicio.token());
        assertThat(pedido.getRastreamentoTokenHash()).isEqualTo(sha256(inicio.token()));
        assertThat(pedido.getEntregaCodigoHash()).isEqualTo(sha256(inicio.codigo()));
        assertThat(pedido.getRastreamentoConsentidoEm()).isNotNull();
        verify(jdbc).update(contains("entrega_rastreamento_auditoria"), any(), any(), any(), any(), isNull(), any());
    }

    @Test void lojaDesativadaBloqueiaInicio() {
        pedido.getRestaurante().setRastreamentoEntregaAtivo(false);
        pedido.getRestaurante().setEntregaCodigoConfirmacaoAtivo(false);
        assertThatThrownBy(() -> service.iniciar(pedido, true)).isInstanceOf(ResponseStatusException.class);
        verify(pedidos, never()).save(any());
    }

    @Test void codigoInvalidoIncrementaTentativasEValidoSoPodeSerUsadoUmaVez() {
        var inicio = service.iniciar(pedido, true);
        assertThat(service.confirmarCodigo(pedido, 9L, "000000".equals(inicio.codigo()) ? "000001" : "000000")).isFalse();
        assertThat(pedido.getEntregaCodigoTentativas()).isEqualTo(1);
        assertThat(service.confirmarCodigo(pedido, 9L, inicio.codigo())).isTrue();
        assertThatThrownBy(() -> service.confirmarCodigo(pedido, 9L, inicio.codigo()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void entregadorDiferenteNaoPodeValidarCodigo() {
        var inicio = service.iniciar(pedido, true);
        assertThatThrownBy(() -> service.confirmarCodigo(pedido, 10L, inicio.codigo()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void coordenadaComPrecisaoBaixaNaoEArmazenada() {
        service.iniciar(pedido, true);
        assertThatThrownBy(() -> service.atualizarPosicao(pedido, 9L, -23.5, -46.6, 250.0, System.currentTimeMillis()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(pedido.getEntregadorLatitude()).isNull();
    }

    @Test void codigoPermiteNoMaximoCincoTentativasInvalidas() {
        var inicio = service.iniciar(pedido, true);
        String incorreto = inicio.codigo().equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) assertThat(service.confirmarCodigo(pedido, 9L, incorreto)).isFalse();
        assertThat(pedido.getEntregaCodigoTentativas()).isEqualTo(5);
        assertThatThrownBy(() -> service.confirmarCodigo(pedido, 9L, incorreto))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void coordenadaRecenteEArmazenadaEAtualizacoesSaoLimitadas() {
        service.iniciar(pedido, true);
        service.atualizarPosicao(pedido, 9L, -23.5, -46.6, 18.0, System.currentTimeMillis());
        assertThat(pedido.getEntregadorLatitude()).isEqualByComparingTo("-23.5");
        assertThat(pedido.getEntregadorLocalizacaoEm()).isNotNull();
        assertThatThrownBy(() -> service.atualizarPosicao(pedido, 9L, -23.5001, -46.6001, 18.0, System.currentTimeMillis()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
