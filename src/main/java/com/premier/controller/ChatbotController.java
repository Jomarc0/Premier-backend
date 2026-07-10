package com.premier.controller;

import com.premier.request.ChatRequest;
import com.premier.response.ChatResponse;
import com.premier.model.Passenger;
import com.premier.service.DialogflowService;
import com.premier.service.GeminiService;
import com.premier.service.PremierBotKnowledgeService;
import com.premier.support.model.SupportTicketIssueType;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/passenger/chat")  
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final DialogflowService dialogflowService;
    private final GeminiService geminiService;
    private final PremierBotKnowledgeService premierBotKnowledgeService;

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
            @AuthenticationPrincipal Passenger passenger,
            HttpServletRequest httpRequest) {

        String userId = (passenger != null)
            ? String.valueOf(passenger.getId())
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

        if (isGeneralHelpRequest(sanitized)) {
            ChatResponse local = premierBotKnowledgeService.answer(sanitized, passenger);
            if (local != null) {
                return ResponseEntity.ok(local);
            }
        }

        ChatResponse localKnowledge = premierBotKnowledgeService.answer(sanitized, passenger);
        if (localKnowledge != null) {
            if (shouldUseGemini(sanitized, localKnowledge)) {
                localKnowledge.setReply(geminiService.enhanceSupportReply(sanitized, localKnowledge.getReply()));
            }
            return ResponseEntity.ok(localKnowledge);
        }

        ChatResponse response = dialogflowService
            .detectIntent(sanitized, request.getSessionId());

        SensitiveIntent sensitiveIntent = detectSensitiveIntent(sanitized, response.getIntent());
        if (sensitiveIntent != null) {
            return ResponseEntity.ok(ChatResponse.builder()
                    .success(true)
                    .reply("This request needs admin review. Please fill out the support ticket form using your card number, email address, and reason. The admin will check your request and send confirmation to your email.")
                    .intent(sensitiveIntent.intentName())
                    .sensitive(true)
                    .recommendedAction("OPEN_SUPPORT_TICKET_FORM")
                    .build());
        }

        if (!response.isSuccess()) {
            response = ChatResponse.builder()
                .success(true)
                .reply(getLocalReply(sanitized))
                .build();
        }

        String reply = response.getReply();
        if (shouldUseGemini(sanitized, response)) {
            reply = geminiService.enhanceSupportReply(sanitized, reply);
        }
        response.setReply(reply);
        response.setSensitive(false);

        return ResponseEntity.ok(response);
    }

    private boolean shouldUseGemini(String message, ChatResponse response) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        return intent.contains("fallback")
                || intent.isBlank()
                || message.length() > 120
                || response.getReply() == null
                || response.getReply().isBlank();
    }

    private SensitiveIntent detectSensitiveIntent(String message, String dialogflowIntent) {
        String text = (message + " " + (dialogflowIntent == null ? "" : dialogflowIntent)).toLowerCase();
        if (text.contains("stolen")) {
            return new SensitiveIntent("STOLEN_CARD", SupportTicketIssueType.LOST_CARD);
        }
        if (text.contains("lost") || text.contains("missing")) {
            return new SensitiveIntent("LOST_CARD", SupportTicketIssueType.LOST_CARD);
        }
        if (text.contains("freeze") || text.contains("block my card") || text.contains("deactivate my card")) {
            return new SensitiveIntent("FREEZE_CARD_REQUEST", SupportTicketIssueType.FREEZE_CARD);
        }
        if (text.contains("change card") || text.contains("card change") || text.contains("update card")) {
            return new SensitiveIntent("CARD_UPDATE_REQUEST", SupportTicketIssueType.DAMAGED_CARD);
        }
        return null;
    }

    private record SensitiveIntent(String intentName, SupportTicketIssueType issueType) {}

    private boolean isGeneralHelpRequest(String message) {
        String text = message == null ? "" : message.toLowerCase();
        return text.equals("help")
                || text.equals("i want help")
                || text.equals("i need help")
                || text.equals("can you help me")
                || text.contains("customer support")
                || text.contains("contact support")
                || text.contains("support hotline");
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
            return "For top-up issues, make sure the payment was completed. " +
                "Balances usually reflect within 5-10 minutes.";
        if (m.contains("fare") || m.contains("deduct"))
            return "Fare deductions are automatic after a valid tap. " +
                "Fare disputes are reviewed within 24 hours.";
        if (m.contains("payment") || m.contains("failed"))
            return "Check your GCash or Maya payment status and try again. " +
                "If it still fails, call (02) 8888-171.";
        if (m.contains("lost") || m.contains("rfid") ||
            m.contains("card"))
            return "Report lost RFID cards at (02) 8888-171 so staff can " +
                "block and replace the card.";
        if (m.contains("balance") || m.contains("check"))
            return "Your balance is on your dashboard " +
                "and updates after every transaction.";
        if (m.contains("hello") || m.contains("hi") ||
            m.contains("hey"))
            return "Hello! Ask me about top-ups, fares, " +
                "RFID cards, or payments!";
        return "A support agent will respond within 24 hours. " +
            "For urgent concerns, call (02) 8888-171.";
    }
}
