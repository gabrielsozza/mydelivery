package com.mydelivery.metaads;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MetaAdsCryptoTest {
    @Test void protectsTokenAndDetectsTampering() {
        var crypto = new MetaAdsCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted = crypto.encrypt("token-secreto");
        assertNotEquals("token-secreto", encrypted);
        assertEquals("token-secreto", crypto.decrypt(encrypted));
        byte[] tampered = Base64.getDecoder().decode(encrypted);
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt(Base64.getEncoder().encodeToString(tampered)));
    }

    @Test void rejectsMissingKey() {
        var crypto = new MetaAdsCrypto("");
        assertFalse(crypto.configured());
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("token"));
    }
}
