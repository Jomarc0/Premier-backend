package com.premier.service;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.premier.response.TotpSetupResponse;

@Service
@Slf4j
public class TotpService {

    @Value("${totp.issuer}")
    private String issuer;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public TotpSetupResponse generateSetup(String email, String secret) {
        String qrCodeUrl = String.format(
            "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
            issuer, email, secret, issuer
        );

        return TotpSetupResponse.builder()
                .secret(secret)
                .qrCodeUrl(qrCodeUrl)
                .manualEntryKey(secret)
                .build();
    }

    public boolean verifyCode(String secret, String code) {
        try {
            CodeGenerator codeGenerator = new DefaultCodeGenerator();
            CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
            return verifier.isValidCode(secret, code);
        } catch (Exception e) {
            log.error("TOTP verification failed: {}", e.getMessage());
            return false;
        }
    }
}