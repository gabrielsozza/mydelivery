package com.mydelivery.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cobre as garantias de segurança do "vidro blindado" dos certificados A1:
 *  - Round-trip: o que criptografa, descriptografa igual
 *  - Isolamento por CNPJ: chave de 1 CNPJ NÃO descriptografa dado de outro
 *  - GCM detecta adulteração: mexer 1 byte no ciphertext, tag ou IV falha
 *  - Fail-fast sem master key: chamadas explodem, não silenciam
 */
class CofreCertificadoServiceTest {

    private CofreCertificadoService cofre;

    @BeforeEach
    void setUp() {
        byte[] rand = new byte[32];
        new SecureRandom().nextBytes(rand);
        String masterKey = Base64.getEncoder().encodeToString(rand);
        cofre = new CofreCertificadoService(masterKey);
    }

    @Test
    void roundTrip_bytes() {
        byte[] plain = "certificado PFX simulado com bytes binários ".getBytes(StandardCharsets.UTF_8);
        var cif = cofre.criptografar(plain, "12345678000199");
        byte[] volta = cofre.descriptografar(cif.ciphertext(), cif.iv(), cif.tag(), "12345678000199");
        assertThat(volta).isEqualTo(plain);
    }

    @Test
    void roundTrip_string() {
        String senha = "S3nh@_D0_Cert!ficado";
        var cif = cofre.criptografar(senha, "12345678000199");
        String volta = cofre.descriptografarString(cif.ciphertext(), cif.iv(), cif.tag(), "12345678000199");
        assertThat(volta).isEqualTo(senha);
    }

    @Test
    void isolamentoPorCnpj_chaveDeOutroCnpjFalha() {
        // Cifra pro CNPJ A, tenta abrir com chave do CNPJ B — TAG do GCM não bate.
        var cif = cofre.criptografar("segredo do CNPJ A", "11111111000111");
        assertThatThrownBy(() ->
                cofre.descriptografarString(cif.ciphertext(), cif.iv(), cif.tag(), "22222222000222"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Falha ao descriptografar");
    }

    @Test
    void adulteracaoNoCiphertext_ehDetectada() {
        var cif = cofre.criptografar("payload", "12345678000199");
        byte[] ct = cif.ciphertext().clone();
        ct[0] = (byte) (ct[0] ^ 0x01);  // flip 1 bit
        assertThatThrownBy(() -> cofre.descriptografar(ct, cif.iv(), cif.tag(), "12345678000199"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void adulteracaoNaTag_ehDetectada() {
        var cif = cofre.criptografar("payload", "12345678000199");
        byte[] tag = cif.tag().clone();
        tag[0] = (byte) (tag[0] ^ 0x01);
        assertThatThrownBy(() -> cofre.descriptografar(cif.ciphertext(), cif.iv(), tag, "12345678000199"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void semMasterKey_verificarConfigExplode() {
        CofreCertificadoService semKey = new CofreCertificadoService(null);
        assertThat(semKey.disponivel()).isFalse();
        assertThatThrownBy(semKey::verificarConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FISCAL_MASTER_KEY");
    }

    @Test
    void masterKeyComTamanhoErrado_falhaNoBoot() {
        // Chave muito curta — construtor deve reclamar antes de virar bean.
        String pequena = Base64.getEncoder().encodeToString(new byte[16]);   // 16 bytes, não 32
        assertThatThrownBy(() -> new CofreCertificadoService(pequena))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void ivsSaoAleatorios_naoReusaEntreOperacoes() {
        // GCM SEM IV único é catastrófico. Confirma que 2 cifras da mesma
        // mensagem produzem IVs (e portanto ciphertexts) diferentes.
        var a = cofre.criptografar("mesma mensagem", "12345678000199");
        var b = cofre.criptografar("mesma mensagem", "12345678000199");
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }
}
