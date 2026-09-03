package com.mydelivery.fiscal.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Cofre criptográfico do módulo fiscal — "vidro blindado" dos certificados A1.
 *
 * <h3>Design de segurança (camadas)</h3>
 * <ol>
 *   <li><b>Chave-mestra</b> ({@code FISCAL_MASTER_KEY}) — 32 bytes base64,
 *       injetada só via variável de ambiente do Railway (secret manager).
 *       NUNCA no código, nunca no repositório, nunca em log.</li>
 *   <li><b>Envelope encryption</b> — chave-mestra derivada por CNPJ via HKDF-SHA256.
 *       Cada CNPJ tem sua própria chave efetiva. Se um único certificado vazar,
 *       a chave dele NÃO permite abrir os outros.</li>
 *   <li><b>AES-256-GCM</b> — cifra autenticada (detecta adulteração). IV de 12
 *       bytes aleatório por operação (nunca reusa). Tag de autenticação de 128
 *       bits guardada separada, permitindo verificar integridade.</li>
 *   <li><b>Sem plain text</b> — nunca grava plain no banco, nunca escreve em
 *       disco. Só existe em memória durante o uso.</li>
 * </ol>
 *
 * <p>Se {@code FISCAL_MASTER_KEY} não estiver configurada, o método
 * {@link #verificarConfig()} lança exceção — o módulo fiscal fica desligado
 * até o secret ser provisionado.
 */
@Slf4j
@Service
public class CofreCertificadoService {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final String KEY_ALG = "AES";
    private static final String CIPHER_ALG = "AES/GCM/NoPadding";
    private static final byte[] HKDF_INFO_CERT = "mydelivery-fiscal-v1-cert".getBytes(StandardCharsets.UTF_8);

    private final byte[] masterKey;   // 32 bytes
    private final SecureRandom rng = new SecureRandom();

    public CofreCertificadoService(
            @Value("${mydelivery.fiscal.master-key:${FISCAL_MASTER_KEY:}}") String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            this.masterKey = null;
            log.warn("[Fiscal][Cofre] FISCAL_MASTER_KEY não configurada — modulo fiscal DESLIGADO. "
                    + "Gere com: openssl rand -base64 32 → adicione como secret no Railway. "
                    + "PERDER esta chave = perder acesso a TODOS os certificados guardados.");
        } else {
            byte[] decoded = Base64.getDecoder().decode(masterKeyBase64.trim());
            if (decoded.length != 32) {
                throw new IllegalStateException(
                    "FISCAL_MASTER_KEY inválida — deve ter 32 bytes (base64 de 44 chars). Tem " + decoded.length);
            }
            this.masterKey = decoded;
            log.info("[Fiscal][Cofre] Master key carregada (32 bytes). Cofre pronto.");
        }
    }

    /** Chama no boot pra travar se master-key não estiver setada. */
    public void verificarConfig() {
        if (masterKey == null) {
            throw new IllegalStateException(
                "FISCAL_MASTER_KEY ausente — não é possível criptografar/descriptografar. "
              + "Provisione o secret no Railway antes de habilitar o módulo fiscal.");
        }
    }

    public boolean disponivel() { return masterKey != null; }

    /**
     * Deriva chave efetiva por CNPJ via HKDF-SHA256(masterKey, salt=cnpj).
     * Cada CNPJ tem chave DIFERENTE — comprometer 1 cert não vaza os outros.
     */
    private byte[] deriveKeyParaCnpj(String cnpj) {
        verificarConfig();
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        byte[] salt = cnpj.getBytes(StandardCharsets.UTF_8);
        hkdf.init(new HKDFParameters(masterKey, salt, HKDF_INFO_CERT));
        byte[] out = new byte[32];
        hkdf.generateBytes(out, 0, 32);
        return out;
    }

    /** Resultado de criptografia — 3 campos vão pro banco separados. */
    public record Cifrado(byte[] ciphertext, byte[] iv, byte[] tag) {}

    /**
     * Criptografa {@code plain} usando chave derivada do {@code cnpj}.
     * Devolve ciphertext, IV e TAG separados (guardar cada um numa coluna
     * — o esquema desta app faz isso).
     */
    public Cifrado criptografar(byte[] plain, String cnpj) {
        if (plain == null) throw new IllegalArgumentException("plain null");
        if (cnpj == null || cnpj.isBlank()) throw new IllegalArgumentException("cnpj vazio");
        byte[] key = deriveKeyParaCnpj(cnpj);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(CIPHER_ALG);
            c.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, KEY_ALG),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] combined = c.doFinal(plain);   // GCM devolve ciphertext || tag
            int ctLen = combined.length - GCM_TAG_BYTES;
            byte[] ct = new byte[ctLen];
            byte[] tag = new byte[GCM_TAG_BYTES];
            System.arraycopy(combined, 0, ct, 0, ctLen);
            System.arraycopy(combined, ctLen, tag, 0, GCM_TAG_BYTES);
            return new Cifrado(ct, iv, tag);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao criptografar dado fiscal", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);   // zera chave em memória ASAP
        }
    }

    /** Sobrecarga pra string (senha do certificado, CSC). Usa UTF-8. */
    public Cifrado criptografar(String plain, String cnpj) {
        return criptografar(plain.getBytes(StandardCharsets.UTF_8), cnpj);
    }

    /**
     * Descriptografa. Se a TAG não bater (adulteração ou chave errada), lança
     * exceção — GCM detecta corrupção automaticamente.
     */
    public byte[] descriptografar(byte[] ciphertext, byte[] iv, byte[] tag, String cnpj) {
        if (ciphertext == null || iv == null || tag == null) {
            throw new IllegalArgumentException("ciphertext/iv/tag null");
        }
        byte[] key = deriveKeyParaCnpj(cnpj);
        try {
            byte[] combined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
            Cipher c = Cipher.getInstance(CIPHER_ALG);
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, KEY_ALG),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(combined);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao descriptografar dado fiscal (tag inválido? chave errada?)", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    public String descriptografarString(byte[] ciphertext, byte[] iv, byte[] tag, String cnpj) {
        return new String(descriptografar(ciphertext, iv, tag, cnpj), StandardCharsets.UTF_8);
    }

    /** Fingerprint pra logar sem expor conteúdo. */
    public String fingerprintHex(byte[] dado) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(dado));
        } catch (Exception e) {
            return "err";
        }
    }
}
