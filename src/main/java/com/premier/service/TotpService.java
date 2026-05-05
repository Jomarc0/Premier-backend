package com.premier.service;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TotpService {

    @Value("${totp.issuer:PremierTransit}")
    private String issuer;

    // Generate new TOTP secret
    public String generateSecret() {
        return new DefaultSecretGenerator().generate();
    }

    // Generate QR Code URL for Google Authenticator
    public String generateQrCodeUrl(
            String secret, String identifier) {
        return String.format(
            "otpauth://totp/%s:%s?secret=%s" +
            "&issuer=%s&algorithm=SHA1&digits=6&period=30",
            encode(issuer),
            encode(identifier),
            secret,
            encode(issuer)
        );
    }

   
    public boolean verifyCode(String secret, String code) {
        try {
            CodeGenerator generator =
                new DefaultCodeGenerator();
            CodeVerifier verifier = new DefaultCodeVerifier(
                generator, new SystemTimeProvider());
            // Allow 1 period window (30 sec tolerance)
            ((DefaultCodeVerifier) verifier)
                .setAllowedTimePeriodDiscrepancy(1);
            return verifier.isValidCode(secret, code);
        } catch (Exception e) {
            log.error("TOTP verify error: {}",
                e.getMessage());
            return false;
        }
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder
                .encode(value, "UTF-8")
                .replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }
}