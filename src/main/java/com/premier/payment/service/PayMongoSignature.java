package com.premier.payment.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** https://docs.paymongo.com/docs/developer-tools-webhook-setup-management */
@Component
public class PayMongoSignature {
    @Value("${paymongo.webhook-secret:}") private String secret;
    @Value("${paymongo.live-mode:false}") private boolean live;
    public boolean liveMode() { return live; }
    public void verify(String raw, String header) {
        try {
            if (secret.isBlank() || raw == null || raw.length() > 262144 || header == null || header.length() > 1024) throw new IllegalArgumentException();
            Map<String, String> parts = new HashMap<>();
            for (String part : header.split(",", -1)) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2 || !Set.of("t", "te", "li").contains(pair[0]) || parts.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException();
            }
            String timestamp = parts.get("t");
            long epoch = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (epoch < now - 300 || epoch > now + 300) throw new IllegalArgumentException();
            String signature = parts.get(live ? "li" : "te");
            if (signature == null || !signature.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((timestamp + "." + raw).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new SecurityException("Invalid PayMongo webhook signature."); }
    }
}
