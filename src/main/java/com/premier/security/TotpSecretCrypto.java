package com.premier.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts TOTP seeds at the application boundary. Key material is supplied only by secret management. */
@Component
public class TotpSecretCrypto {
    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCrypto(@Value("${totp.encryption-key}") String encodedKey) {
        try {
            byte[] raw = Base64.getDecoder().decode(encodedKey);
            if (raw.length != 32) throw new IllegalArgumentException("expected 32 bytes");
            this.key = new SecretKeySpec(raw, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY must be a base64-encoded 32-byte AES key.", ex);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt TOTP secret.", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        // Legacy plaintext is accepted only to permit one-time migration on the next verified use.
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            String[] parts = stored.split(":", 3);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt TOTP secret.", ex);
        }
    }

    public boolean isEncrypted(String stored) { return stored != null && stored.startsWith(PREFIX); }
}
