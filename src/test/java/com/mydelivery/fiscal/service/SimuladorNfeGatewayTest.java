package com.mydelivery.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mydelivery.fiscal.service.NfeGateway.Emitente;
import com.mydelivery.fiscal.service.NfeGateway.ItemNota;
import com.mydelivery.fiscal.service.NfeGateway.Pagamento;
import com.mydelivery.fiscal.service.NfeGateway.RequisicaoCancelamento;
import com.mydelivery.fiscal.service.NfeGateway.RequisicaoEmissao;

/**
 * Cobre o SimuladorNfeGateway — pré-requisitos, chave 44 dígitos válida,
 * fluxos de cancelamento e contingência.
 */
class SimuladorNfeGatewayTest {

    private SimuladorNfeGateway gw;

    @BeforeEach
    void setUp() {
        gw = new SimuladorNfeGateway("simulador");
    }

    private RequisicaoEmissao reqValida() {
        var emit = new Emitente("12345678000199", "LOJA TESTE LTDA", "Loja Teste",
                "ISENTO", "ES", "3205200", "Vitoria",
                "Rua X", "100", "Centro", "29100000", 1);
        var itens = List.of(new ItemNota(1, "PROD1", "Hamburger",
                "21069090", "5102", null, "102", 0, "UN",
                BigDecimal.ONE, new BigDecimal("30.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        var pag = List.of(new Pagamento("01", new BigDecimal("30.00")));
        return new RequisicaoEmissao("ES", 2, 1, 1L, LocalDateTime.now(),
                emit, null, itens, new BigDecimal("30.00"), pag,
                "000001", "abcdefabcdefabcdefabcdef1234567890AB",
                new byte[]{1, 2, 3}, "senha");
    }

    @Test
    void emissaoValida_aprovadaComChave44() {
        var r = gw.emitir(reqValida());
        assertThat(r.aprovada()).isTrue();
        assertThat(r.cStat()).isEqualTo("100");
        assertThat(r.chaveAcesso()).hasSize(44);
        assertThat(r.chaveAcesso()).matches("\\d{44}");
        assertThat(r.xmlAssinado()).contains("SIMULADO"); // marcador de "não vale como nota"
        assertThat(r.qrCodeUrl()).isNotBlank();
    }

    @Test
    void emissaoSemCertificado_negada() {
        var r = reqValida();
        var semCert = new RequisicaoEmissao(r.uf(), r.ambiente(), r.serie(), r.numero(),
                r.emitidaEm(), r.emitente(), r.destinatario(), r.itens(), r.valorTotal(),
                r.pagamentos(), r.cscId(), r.cscValor(), new byte[0], r.certificadoSenha());
        var res = gw.emitir(semCert);
        assertThat(res.aprovada()).isFalse();
        assertThat(res.cStat()).isEqualTo("ERRO_TEC");
        assertThat(res.motivo()).contains("Certificado");
    }

    @Test
    void emissaoSemCsc_negada() {
        var r = reqValida();
        var semCsc = new RequisicaoEmissao(r.uf(), r.ambiente(), r.serie(), r.numero(),
                r.emitidaEm(), r.emitente(), r.destinatario(), r.itens(), r.valorTotal(),
                r.pagamentos(), r.cscId(), "", r.certificadoPfx(), r.certificadoSenha());
        var res = gw.emitir(semCsc);
        assertThat(res.aprovada()).isFalse();
        assertThat(res.motivo()).contains("CSC");
    }

    @Test
    void emissaoSemItens_negada() {
        var r = reqValida();
        var semItens = new RequisicaoEmissao(r.uf(), r.ambiente(), r.serie(), r.numero(),
                r.emitidaEm(), r.emitente(), r.destinatario(), List.of(), r.valorTotal(),
                r.pagamentos(), r.cscId(), r.cscValor(), r.certificadoPfx(), r.certificadoSenha());
        assertThat(gw.emitir(semItens).aprovada()).isFalse();
    }

    @Test
    void cancelamento_validaChaveJustificativa() {
        // 2(UF)+4(AAMM)+14(CNPJ)+2(mod)+3(serie)+9(nNF)+1(tpEmis)+8(cNF)+1(DV) = 44
        var chave44 = "32" + "2312" + "12345678000199" + "65" + "001" + "000000001" + "1" + "12345678" + "0";
        var req = new RequisicaoCancelamento("ES", 2, "12345678000199",
                chave44, "PROTO123", "Erro no valor unitario do item — cancelando",
                1, new byte[]{1}, "senha");
        var r = gw.cancelar(req);
        assertThat(r.aprovado()).isTrue();
        assertThat(r.cStat()).isEqualTo("135");
        assertThat(r.protocoloCancelamento()).startsWith("SIMCANC");
    }

    @Test
    void cancelamento_justificativaCurta_recusada() {
        var chave44 = "32" + "2312" + "12345678000199" + "65" + "001" + "000000001" + "1" + "12345678" + "0";
        var req = new RequisicaoCancelamento("ES", 2, "12345678000199",
                chave44, "PROTO", "curta", 1, new byte[]{1}, "s");
        var r = gw.cancelar(req);
        assertThat(r.aprovado()).isFalse();
        assertThat(r.motivo()).contains("15");
    }

    @Test
    void contingencia_aprovadaOfflineComChave() {
        var r = gw.emitirContingencia(reqValida());
        assertThat(r.aprovada()).isTrue();
        assertThat(r.cStat()).isEqualTo("CONTINGENCIA");
        assertThat(r.chaveAcesso()).hasSize(44);
        assertThat(r.protocolo()).isEqualTo("PENDENTE_TRANSMISSAO");
    }

    @Test
    void statusSefaz_reportaOnline() {
        var s = gw.consultarStatusSefaz("ES", 2);
        assertThat(s.online()).isTrue();
        assertThat(s.cStat()).isEqualTo("107");
    }

    @Test
    void gatewayDesabilitado_recusaTudo() {
        var desligado = new SimuladorNfeGateway("real");   // != "simulador"
        assertThat(desligado.disponivel()).isFalse();
        assertThat(desligado.emitir(reqValida()).aprovada()).isFalse();
    }
}
