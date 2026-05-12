package com.premier.controller;

import com.premier.request.ChatRequest;
import com.premier.response.ChatResponse;
import com.premier.service.DialogflowService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/passenger/chat")  
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final DialogflowService dialogflowService;

    // In-memory per-user rate limiting: 20 msg/min
    private final Map<String, Bucket> buckets =
        new ConcurrentHashMap<>();

    private Bucket resolveBucket(String userId) {
        return buckets.computeIfAbsent(userId, k ->
            Bucket.builder()
                .addLimit(Bandwidth.builder()
                    .capacity(20)
                    .refillGreedy(20, Duration.ofMinutes(1))
                    .build())
                .build()
        );
    }

    @PostMapping("/message")
    public ResponseEntity<ChatResponse> handleMessage(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {

        String userId = (userDetails != null)
            ? userDetails.getUsername()
            : httpRequest.getRemoteAddr();

        Bucket bucket = resolveBucket(userId);
        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded: {}", userId);
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ChatResponse.builder()
                    .success(false)
                    .reply("You're sending messages too quickly. " +
                           "Please wait before trying again.")
                    .errorCode("RATE_LIMITED")
                    .build());
        }

        String sanitized = sanitize(request.getMessage());
        if (sanitized.isBlank()) {
            return ResponseEntity.badRequest()
                .body(ChatResponse.builder()
                    .success(false)
                    .reply("Invalid message.")
                    .errorCode("INVALID_INPUT")
                    .build());
        }

        log.info("Chat [{}]: {}", userId, sanitized);

        ChatResponse response = dialogflowService
            .detectIntent(sanitized, request.getSessionId());

        if (!response.isSuccess()) {
            response = ChatResponse.builder()
                .success(true)
                .reply(getLocalReply(sanitized))
                .build();
        }

        return ResponseEntity.ok(response);
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input
            .replaceAll("<[^>]*>", "")
            .replaceAll("[<>\"'&;]", "")
            .trim();
    }

    private String getLocalReply(String msg) {
        String m = msg.toLowerCase();
        if (m.contains("top-up") || m.contains("topup") ||
            m.contains("recharge") || m.contains("load"))
            return "💳 For top-up issues, ensure payment was " +
                "completed. Balances reflect within 5-10 minutes.";
        if (m.contains("fare") || m.contains("deduct"))
            return "🚌 Fare deductions are automatic on tap. " +
                "Disputes reviewed within 24 hours.";
        if (m.contains("payment") || m.contains("failed"))
            return "❌ Check your GCash/Maya balance and try " +
                "again. Still failing? Call (123) 456-7890.";
        if (m.contains("lost") || m.contains("rfid") ||
            m.contains("card"))
            return "🪪 Report lost cards at (123) 456-7890 " +
                "to block and replace immediately.";
        if (m.contains("balance") || m.contains("check"))
            return "💰 Your balance is on your dashboard " +
                "and updates after every transaction.";
        if (m.contains("hello") || m.contains("hi") ||
            m.contains("hey"))
            return "👋 Hello! Ask me about top-ups, fares, " +
                "RFID cards, or payments!";
        return "🤖 A support agent will respond within 24 hrs. " +
            "Urgent? Call (123) 456-7890.";
    }
}