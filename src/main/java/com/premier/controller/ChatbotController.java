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

        log.info("Chat request received for {} ({} characters).", userId, sanitized.length());

        // Dialogflow is the primary conversation router. The backend only supplies
        // authoritative data/actions after an intent has been identified.
        ChatResponse response = dialogflowService.detectIntent(sanitized, request.getSessionId());

        // These are the small, supported passenger-help rules. Keep the
        // response authoritative even if a Dialogflow response was configured
        // with an old phone number, payment promise, or invented policy.
        if (isExplicitLostCardReport(sanitized)) {
            return ResponseEntity.ok(ChatResponse.builder()
                    .success(true)
                    .intent("LOST_CARD_REPORT")
                    .reply("Opening the secure lost-card report. Review the warning, enter your email for updates, then confirm only if you want to freeze this card.")
                    .recommendedAction("REPORT_LOST_CARD")
                    .build());
        }
        if (isExplicitTicketCreation(sanitized)) {
            return ResponseEntity.ok(ticketFormResponse());
        }
        if (isHumanSupportRequest(response, sanitized)) {
            return ResponseEntity.ok(ChatResponse.builder()
                    .success(true)
                    .intent("SUPPORT_REQUEST")
                    .reply("I can help you submit a support ticket so the support team can investigate. Would you like to create a ticket?")
                    .recommendedAction("OPEN_SUPPORT_TICKET_FORM")
                    .quickReplies(List.of("Open ticket", "Cancel"))
                    .build());
        }
        if (isKnownSelfServiceRequest(sanitized)) {
            ChatResponse ruleResponse = premierBotKnowledgeService.answer(sanitized, passenger);
            if (ruleResponse != null) {
                return ResponseEntity.ok(ruleResponse);
            }
        }

        // Ticket status is authoritative account data. It is never supplied by
        // Dialogflow or Gemini, even when either service recognizes the phrase.
        if (isTicketStatusIntent(response, sanitized)) {
            ChatResponse ticketStatus = premierBotKnowledgeService.answer("ticket status", passenger);
            if (ticketStatus != null) {
                return ResponseEntity.ok(ticketStatus);
            }
        }

        // Lost-card guidance is a fixed account-protection procedure. The bot
        // directs the passenger to the dedicated confirmation screen; only that
        // screen can submit the irreversible freeze request.
        if (isLostCardIntent(response, sanitized)) {
            ChatResponse lostCardReply = premierBotKnowledgeService.answer("lost card", passenger);
            if (lostCardReply != null) {
                return ResponseEntity.ok(lostCardReply);
            }
        }

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

        if (isSupportTicketIntent(response)) {
            return ResponseEntity.ok(ChatResponse.builder()
                    .success(true)
                    .reply("I can help you submit a support ticket. Please complete the secure form so an admin can review your concern.")
                    .intent(response.getIntent())
                    .sensitive(false)
                    .recommendedAction("OPEN_SUPPORT_TICKET_FORM")
                    .quickReplies(List.of("Open ticket", "Cancel"))
                    .build());
        }

        // If Dialogflow is unavailable, retain safe local rules for known
        // passenger-help phrases instead of handing them to Gemini.
        if (shouldUseGemini(response)) {
            ChatResponse localKnowledge = premierBotKnowledgeService.answer(sanitized, passenger);
            if (localKnowledge != null) {
                return ResponseEntity.ok(localKnowledge);
            }
        }

        if (!shouldUseGemini(response)) {
            response.setSensitive(false);
            return ResponseEntity.ok(response);
        }

        // Dialogflow fallback is the only Gemini path. Gemini receives only a
        // non-sensitive, predefined fallback context and cannot invoke business logic.
        String safeFallback = "I couldn't match that to a supported passenger-help request. "
                + "I can help with general assistance, top-ups, lost-card procedures, and support tickets. "
                + "Please rephrase your question or contact support.";
        return ResponseEntity.ok(ChatResponse.builder()
                .success(true)
                .reply(geminiService.enhanceSupportReply(sanitized, safeFallback))
                .intent("FALLBACK")
                .sensitive(false)
                .quickReplies(List.of("Top-up help", "Lost RFID card", "Open ticket"))
                .build());
    }

    private boolean shouldUseGemini(ChatResponse response) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        return intent.contains("fallback")
                || intent.isBlank()
                || !response.isSuccess()
                || response.getReply() == null
                || response.getReply().isBlank();
    }

    private boolean isSupportTicketIntent(ChatResponse response) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        return (intent.contains("complaint")
                || intent.contains("support_request")
                || intent.contains("create_support")
                || intent.contains("contact_support"))
                && !intent.contains("status")
                && !intent.contains("detail")
                && !intent.contains("message");
    }

    private boolean isExplicitTicketCreation(String message) {
        String text = message == null ? "" : message.toLowerCase().trim();
        return text.equals("open ticket") || text.equals("create ticket") || text.equals("create a ticket")
                || text.equals("submit ticket") || text.equals("submit a ticket")
                || text.equals("yes, create a ticket") || text.equals("yes create a ticket");
    }

    private boolean isExplicitLostCardReport(String message) {
        String text = message == null ? "" : message.toLowerCase().trim();
        return text.equals("report lost card") || text.equals("report a lost card")
                || text.equals("freeze my card") || text.equals("freeze card")
                || text.equals("block my card");
    }

    private boolean isHumanSupportRequest(ChatResponse response, String message) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        String text = message == null ? "" : message.toLowerCase();
        return intent.contains("contact_support") || intent.contains("support_request")
                || text.contains("contact support") || text.contains("talk to support")
                || text.contains("customer support") || text.contains("report a problem")
                || text.contains("still need help");
    }

    private boolean isKnownSelfServiceRequest(String message) {
        String text = message == null ? "" : message.toLowerCase();
        return text.contains("top up") || text.contains("top-up") || text.contains("topup")
                || text.contains("reload") || text.contains("payment failed") || text.contains("fare failed")
                || text.contains("fare deduction") || text.contains("fare dispute")
                || text.contains("lost") || text.contains("stolen") || text.contains("missing")
                || text.contains("rfid") || text.contains("check balance")
                || text.equals("i need help") || text.equals("need help") || text.equals("help me");
    }

    private ChatResponse ticketFormResponse() {
        return ChatResponse.builder()
                .success(true)
                .intent("CREATE_TICKET")
                .reply("Please complete the secure support-ticket form. Your signed-in account will be linked to the request automatically.")
                .recommendedAction("OPEN_SUPPORT_TICKET_FORM")
                .build();
    }

    private boolean isTicketStatusIntent(ChatResponse response, String message) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        String text = message == null ? "" : message.toLowerCase();
        return intent.contains("ticket_status")
                || intent.contains("support_ticket_status")
                || ((text.contains("ticket") || text.contains("support request") || text.contains("case"))
                && (text.contains("status") || text.contains("update") || text.contains("progress")));
    }

    private boolean isLostCardIntent(ChatResponse response, String message) {
        String intent = response.getIntent() == null ? "" : response.getIntent().toLowerCase();
        String text = message == null ? "" : message.toLowerCase();
        return intent.contains("lost_card") || intent.contains("lostcard")
                || ((text.contains("lost") || text.contains("missing") || text.contains("stolen"))
                && (text.contains("card") || text.contains("rfid")));
    }

    private SensitiveIntent detectSensitiveIntent(String message, String dialogflowIntent) {
        String text = (message + " " + (dialogflowIntent == null ? "" : dialogflowIntent)).toLowerCase();
        if (text.contains("stolen")) {
            return new SensitiveIntent("STOLEN_CARD", SupportTicketIssueType.LOST_CARD);
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

    private String sanitize(String input) {
        if (input == null) return "";
        return input
            .replaceAll("<[^>]*>", "")
            .replaceAll("[<>\"'&;]", "")
            .trim();
    }

}
