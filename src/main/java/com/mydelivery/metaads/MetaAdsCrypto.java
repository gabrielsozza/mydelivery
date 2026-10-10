package com.mydelivery.metaads;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MetaAdsCrypto {
    private final String keyBase64;
    private final SecureRandom random = new SecureRandom();

    public MetaAdsCrypto(@Value("${mydelivery.meta-ads.encryption-key:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    public boolean configured() {
        try { return Base64.getDecoder().decode(keyBase64).length == 32; }
        catch (IllegalArgumentException e) { return false; }
    }

    private SecretKeySpec key() {
        if (!configured()) throw new IllegalStateException("Chave de proteção da integração Meta não configurada");
        return new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
    }

    public String encrypt(String value) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) { throw new IllegalStateException("Falha ao proteger conexão Meta", e); }
    }

    public String decrypt(String value) {
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Falha ao ler conexão Meta", e); }
    }
}
