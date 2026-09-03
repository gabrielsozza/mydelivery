package com.mydelivery.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mydelivery.fiscal.service.NfeGateway.Emitente;
import com.mydelivery.fiscal.service.NfeGateway.ItemNota;
import com.mydelivery.fiscal.service.NfeGateway.Pagamento;
import com.mydelivery.fiscal.service.NfeGateway.RequisicaoCancelamento;
import com.mydelivery.fiscal.service.NfeGateway.RequisicaoEmissao;

/**
 * TESTE E2E do fluxo fiscal — cobre a cadeia:
 *   PFX real (autoassinado em test.pfx)
 *     ↓ carrega KeyStore com senha
 *     ↓ extrai certificado + fingerprint
 *   Cofre (AES-256-GCM + HKDF por CNPJ)
 *     ↓ cifra PFX + senha
 *     ↓ decifra → bytes idênticos
 *     ↓ reabre KeyStore com bytes decifrados → cert válido
 *   Simulador NFC-e
 *     ↓ emite nota com dados do cert
 *     ↓ valida chave 44 dígitos, cStat 100, XML gerado, QR-Code presente
 *     ↓ cancela → cStat 135
 *     ↓ contingência offline → cStat CONTINGENCIA
 *
 * <p>NÃO cobre SEFAZ real (precisa cert ICP-Brasil + CSC oficial + endpoint SEFAZ).
 * Mas cobre TODA a orquestração interna que EU controlo — o que quebrou nas últimas
 * rodadas de deploy (envelope, chave, XML, marcadores de simulação).
 *
 * <p>Rodagem: {@code mvn test -Dtest=FluxoFiscalE2ETest}
 */
class FluxoFiscalE2ETest {

    private static final String CNPJ = "12345678000199";
    private static final String SENHA_PFX = "senha123";
    private static final String CSC_ID = "000001";
    private static final String CSC_TOKEN = "abcdefabcdefabcdefabcdef1234567890AB";

    private byte[] carregarPfxDeTeste() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fiscal/test.pfx")) {
            assertThat(is).isNotNull().withFailMessage("test.pfx não está no classpath");
            return is.readAllBytes();
        }
    }

    private CofreCertificadoService cofreComMasterKey() {
        byte[] rand = new byte[32];
        new SecureRandom().nextBytes(rand);
        return new CofreCertificadoService(Base64.getEncoder().encodeToString(rand));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 1: PFX carrega + cert extrai
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void pfxDeTeste_carregaComSenhaCerta_extraiCertificado() throws Exception {
        byte[] pfx = carregarPfxDeTeste();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(pfx), SENHA_PFX.toCharArray());

        String alias = ks.aliases().nextElement();
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        assertThat(cert).isNotNull();
        assertThat(cert.getSubjectX500Principal().getName()).contains(CNPJ);
        cert.checkValidity();
    }

    @Test
    void pfxDeTeste_senhaErrada_falha() throws Exception {
        byte[] pfx = carregarPfxDeTeste();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        assertThatCode(() -> ks.load(new ByteArrayInputStream(pfx), "senhaerrada".toCharArray()))
                .isInstanceOf(Exception.class);
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatCode(
            org.junit.jupiter.api.function.Executable e) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(e::execute);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 2: Cofre cifra + decifra PFX real (round-trip binário)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void cofre_cifraEDecifra_pfxReal_bytesIdenticos() throws Exception {
        byte[] pfx = carregarPfxDeTeste();
        var cofre = cofreComMasterKey();

        var cif = cofre.criptografar(pfx, CNPJ);
        assertThat(cif.iv()).hasSize(12);
        assertThat(cif.tag()).hasSize(16);
        assertThat(cif.ciphertext()).isNotEqualTo(pfx);   // realmente cifrou

        byte[] volta = cofre.descriptografar(cif.ciphertext(), cif.iv(), cif.tag(), CNPJ);
        assertThat(volta).isEqualTo(pfx);
    }

    @Test
    void cofre_pfxDecifrado_reabreKeyStoreComMesmaSenha() throws Exception {
        byte[] pfx = carregarPfxDeTeste();
        var cofre = cofreComMasterKey();

        // Cifra + decifra
        var cifPfx = cofre.criptografar(pfx, CNPJ);
        var cifSenha = cofre.criptografar(SENHA_PFX, CNPJ);
        byte[] pfxVolta = cofre.descriptografar(cifPfx.ciphertext(), cifPfx.iv(), cifPfx.tag(), CNPJ);
        String senhaVolta = cofre.descriptografarString(cifSenha.ciphertext(), cifSenha.iv(), cifSenha.tag(), CNPJ);

        // Reabre KeyStore com o resultado — TEM que abrir igual
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(pfxVolta), senhaVolta.toCharArray());
        String alias = ks.aliases().nextElement();
        assertThat(ks.getCertificate(alias)).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 3: Simulador emite NFC-e válida (chave 44 + XML + QR)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void emissaoSimulada_produzNfceValida() throws Exception {
        var gw = new SimuladorNfeGateway("simulador");
        var req = requisicaoEmissaoValida(SENHA_PFX);
        var r = gw.emitir(req);

        assertThat(r.aprovada()).isTrue();
        assertThat(r.cStat()).isEqualTo("100");
        assertThat(r.chaveAcesso()).hasSize(44).matches("\\d{44}");
        assertThat(r.protocolo()).isNotBlank();
        assertThat(r.xmlAssinado()).contains("SIMULADO").contains(CNPJ).contains(r.chaveAcesso());
        assertThat(r.qrCodeUrl()).isNotBlank();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 4: Cancelamento simulado (janela SEFAZ 30 min)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void cancelamentoSimulado_aprovado() throws Exception {
        var gw = new SimuladorNfeGateway("simulador");
        var req = requisicaoEmissaoValida(SENHA_PFX);
        var emitida = gw.emitir(req);
        assertThat(emitida.aprovada()).isTrue();

        var cancReq = new RequisicaoCancelamento("ES", 2, CNPJ, emitida.chaveAcesso(),
                emitida.protocolo(), "Cancelamento de teste automatizado do fluxo E2E fiscal",
                1, carregarPfxDeTeste(), SENHA_PFX);
        var cancRes = gw.cancelar(cancReq);
        assertThat(cancRes.aprovado()).isTrue();
        assertThat(cancRes.cStat()).isEqualTo("135");
        assertThat(cancRes.protocoloCancelamento()).startsWith("SIMCANC");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 5: Contingência offline (quando SEFAZ cai)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void contingenciaOffline_emitidaLocalmenteComChaveValida() throws Exception {
        var gw = new SimuladorNfeGateway("simulador");
        var req = requisicaoEmissaoValida(SENHA_PFX);
        var r = gw.emitirContingencia(req);
        assertThat(r.aprovada()).isTrue();
        assertThat(r.cStat()).isEqualTo("CONTINGENCIA");
        assertThat(r.chaveAcesso()).hasSize(44);
        assertThat(r.protocolo()).isEqualTo("PENDENTE_TRANSMISSAO");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Etapa 6: Status SEFAZ (fluxo pre-emissão)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void statusSefaz_reportaOnlineComCstat107() {
        var gw = new SimuladorNfeGateway("simulador");
        var s = gw.consultarStatusSefaz("ES", 2);
        assertThat(s.online()).isTrue();
        assertThat(s.cStat()).isEqualTo("107");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helper: monta requisição de emissão com o PFX real de teste
    // ═══════════════════════════════════════════════════════════════════
    private RequisicaoEmissao requisicaoEmissaoValida(String senha) throws Exception {
        byte[] pfx = carregarPfxDeTeste();
        var emit = new Emitente(CNPJ, "LOJA TESTE LTDA", "Loja Teste E2E",
                "ISENTO", "ES", "3205200",
                "Rua Teste", "100", "Centro", "29100000", 1);
        var itens = List.of(
            new ItemNota(1, "PROD1", "Hamburguer Teste E2E",
                "21069090", "5102", null, "102", 0, "UN",
                BigDecimal.ONE, new BigDecimal("30.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        var pag = List.of(new Pagamento("01", new BigDecimal("30.00")));
        return new RequisicaoEmissao("ES", 2, 1, 1L, LocalDateTime.now(),
                emit, null, itens, new BigDecimal("30.00"), pag,
                CSC_ID, CSC_TOKEN, pfx, senha);
    }
}
