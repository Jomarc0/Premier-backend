package com.premier.service;

import com.premier.driver.model.Vehicle;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.Passenger;
import com.premier.model.TopUpRequest;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.repository.TopUpRequestRepository;
import com.premier.repository.TransactionRepository;
import com.premier.response.ChatResponse;
import com.premier.support.model.SupportTicket;
import com.premier.support.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PremierBotKnowledgeService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH);

    private final TransactionRepository transactionRepository;
    private final TopUpRequestRepository topUpRequestRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final VehicleRepository vehicleRepository;

    public ChatResponse answer(String message, Passenger passenger) {
        String text = normalize(message);

        if (containsAny(text, "hello", "hi", "hey", "good morning", "good afternoon")) {
            return response("Hello! I can help with balance, top-ups, RFID cards, fare payments, QR/NFC fare tokens, buses, routes, support tickets, and account safety.",
                    "LOCAL_GREETING", List.of("Check balance", "Top-up help", "Payment failed", "Lost RFID card"));
        }

        if (containsAny(text, "what can you do", "features", "help", "guide", "menu")) {
            return response("""
                    I can help you with:
                    - Balance and recent transaction guidance
                    - Top-up status and PayMongo payment concerns
                    - RFID, QR, and NFC fare payment questions
                    - Lost, frozen, blocked, or damaged card next steps
                    - Bus, route, and terminal guidance
                    - Support ticket status guidance

                    I can explain and guide, but admin approval is required for card freezing, replacements, refunds, and account changes.
                    """.trim(), "LOCAL_CAPABILITIES", List.of("Check balance", "Recent transaction", "Open ticket", "Bus routes"));
        }

        if (containsAny(text, "balance", "how much money", "remaining load", "wallet")) {
            if (passenger == null) {
                return action("Please log in first so I can check your balance safely.", "LOCAL_BALANCE_LOGIN_REQUIRED", "LOGIN", List.of("Login", "Top-up help"));
            }
            return response("Your current balance is " + money(passenger.getBalance()) + ". Your card is " + label(passenger.getStatus().name()) + ".", "LOCAL_BALANCE", List.of("Recent transaction", "Top-up help"));
        }

        if (containsAny(text, "recent transaction", "last transaction", "history", "receipt")) {
            if (passenger == null) {
                return action("Please log in first so I can show account-specific transaction guidance.", "LOCAL_TRANSACTION_LOGIN_REQUIRED", "LOGIN", List.of("Login", "Fare deduction"));
            }
            List<Transaction> recent = transactionRepository
                    .findByPassengerIdOrderByCreatedAtDesc(passenger.getId(), PageRequest.of(0, 3))
                    .getContent();
            if (recent.isEmpty()) {
                return response("I do not see recent transactions for your account yet.", "LOCAL_RECENT_TRANSACTIONS_EMPTY", List.of("Top-up help", "Fare payment"));
            }
            StringBuilder reply = new StringBuilder("Here are your latest transactions:");
            for (Transaction tx : recent) {
                reply.append("\n- ")
                        .append(label(tx.getType().name()))
                        .append(" | ")
                        .append(label(tx.getStatus().name()))
                        .append(" | ")
                        .append(money(tx.getAmount()))
                        .append(tx.getCreatedAt() == null ? "" : " | " + tx.getCreatedAt().format(DATE_TIME));
            }
            return response(reply.toString(), "LOCAL_RECENT_TRANSACTIONS", List.of("Payment failed", "Top-up issue"));
        }

        if (containsAny(text, "top up", "top-up", "topup", "load", "recharge", "paymongo")) {
            return topUpReply(passenger);
        }

        if (containsAny(text, "payment failed", "failed payment", "cannot pay", "fare failed", "insufficient", "deduct failed")) {
            return action("""
                    Payment can fail because of low balance, inactive/frozen card status, expired QR/NFC token, duplicate tap cooldown, or a device connection issue.

                    Try checking your balance, regenerate QR/NFC token if needed, and tap only once. If money was deducted but no ride was recorded, submit a support ticket for admin review.
                    """.trim(), "LOCAL_PAYMENT_FAILED", "OPEN_SUPPORT_TICKET_FORM", List.of("Check balance", "Top-up help", "Open ticket"));
        }

        if (containsAny(text, "rfid", "card", "lost", "stolen", "freeze", "blocked", "damaged", "replacement")) {
            return rfidCardReply(text, passenger);
        }

        if (containsAny(text, "qr", "nfc", "token", "scan")) {
            return response("""
                    QR and NFC fare tokens are short-lived for security. If a token expires or was already used, generate a new one from your passenger dashboard before scanning again.

                    Do not share QR/NFC payloads or screenshots with other people.
                    """.trim(), "LOCAL_QR_NFC_HELP", List.of("Payment failed", "Check balance"));
        }

        if (containsAny(text, "bus", "route", "terminal", "vehicle", "trip")) {
            return routeReply();
        }

        if (containsAny(text, "ticket", "request status", "support request", "case")) {
            return ticketReply(passenger);
        }

        if (containsAny(text, "2fa", "totp", "google authenticator", "authenticator", "verification code")) {
            return response("""
                    Premier uses Google Authenticator/TOTP for stronger account security. If setup is required, scan the QR code shown by the app and enter the current 6-digit code.

                    Never share your authenticator code with anyone, including support.
                    """.trim(), "LOCAL_TOTP_HELP", List.of("Login help", "Account concern"));
        }

        if (containsAny(text, "refund", "reverse", "wrong deduction", "dispute")) {
            return action("Fare disputes and refunds need admin review. Please submit a support ticket with the payment time, amount, card number, and what happened.", "LOCAL_DISPUTE", "OPEN_SUPPORT_TICKET_FORM", List.of("Open ticket", "Recent transaction"));
        }

        return null;
    }

    private ChatResponse topUpReply(Passenger passenger) {
        if (passenger != null) {
            List<TopUpRequest> topUps = topUpRequestRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId());
            if (!topUps.isEmpty()) {
                TopUpRequest latest = topUps.get(0);
                return response("Your latest top-up request is " + label(latest.getStatus().name()) + " for " + money(latest.getAmount()) + ". Reference: " + safe(latest.getReferenceNumber()) + ". If payment is already completed, balance usually updates after verification.",
                        "LOCAL_TOPUP_STATUS", List.of("Payment failed", "Check balance", "Open ticket"));
            }
        }
        return response("To top up, open your passenger dashboard, choose a top-up amount, continue to the PayMongo checkout, and finish payment. If the checkout was paid but your balance did not update, submit a support ticket with your reference number.",
                "LOCAL_TOPUP_HELP", List.of("Check balance", "Payment failed", "Open ticket"));
    }

    private ChatResponse rfidCardReply(String text, Passenger passenger) {
        if (containsAny(text, "lost", "stolen", "freeze", "block")) {
            return action("Lost or stolen RFID cards must be reviewed by an admin before freezing or replacement. Submit a support ticket immediately so staff can protect the card and guide replacement.", "LOCAL_CARD_FREEZE", "OPEN_SUPPORT_TICKET_FORM", List.of("Open ticket", "Contact support"));
        }
        if (passenger != null) {
            return response("Your card is currently " + label(passenger.getStatus().name()) + ". Card number: " + mask(passenger.getCardNumber()) + ". For replacement, damaged card, or status changes, submit a support ticket for admin review.",
                    "LOCAL_CARD_STATUS", List.of("Open ticket", "Payment failed"));
        }
        return action("For RFID card concerns, log in first or submit a support ticket with your card number and concern. Never share your RFID UID publicly.", "LOCAL_CARD_HELP", "OPEN_SUPPORT_TICKET_FORM", List.of("Login", "Open ticket"));
    }

    private ChatResponse routeReply() {
        List<Vehicle> vehicles = vehicleRepository.findAll();
        List<String> routes = vehicles.stream()
                .map(Vehicle::getRoute)
                .filter(route -> route != null && !route.isBlank())
                .distinct()
                .sorted()
                .limit(5)
                .toList();
        long activeBuses = vehicles.stream().filter(v -> v.getStatus() == VehicleStatus.ACTIVE).count();
        if (routes.isEmpty()) {
            return response("Route information is not available right now. Please check the bus monitoring page or ask staff at the terminal.", "LOCAL_ROUTE_EMPTY", List.of("Contact support"));
        }
        return response("Current stored routes include: " + String.join(", ", routes) + ". Active buses recorded: " + activeBuses + ". For live arrival or terminal status, check the bus monitoring screen.",
                "LOCAL_ROUTE_HELP", List.of("Bus monitoring", "Terminal help"));
    }

    private ChatResponse ticketReply(Passenger passenger) {
        if (passenger == null) {
            return action("Please log in to check account-specific ticket guidance, or open the support ticket form if you need to report a concern.", "LOCAL_TICKET_LOGIN_REQUIRED", "LOGIN", List.of("Login", "Open ticket"));
        }
        String card = passenger.getCardNumber();
        List<SupportTicket> tickets = supportTicketRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(ticket -> card != null && card.equals(ticket.getCardNumber()))
                .sorted(Comparator.comparing(SupportTicket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3)
                .toList();
        if (tickets.isEmpty()) {
            return action("I do not see recent support tickets for your card. You can open one for lost card, freeze card, top-up issue, payment concern, or technical concern.", "LOCAL_TICKET_EMPTY", "OPEN_SUPPORT_TICKET_FORM", List.of("Open ticket", "Payment failed"));
        }
        SupportTicket latest = tickets.get(0);
        return response("Your latest support ticket " + latest.getTicketNumber() + " is " + label(latest.getStatus().name()) + " for " + label(latest.getIssueType().name()) + ". Admin review is required before card/account changes.",
                "LOCAL_TICKET_STATUS", List.of("Open ticket", "Contact support"));
    }

    private ChatResponse response(String reply, String intent, List<String> quickReplies) {
        return ChatResponse.builder()
                .success(true)
                .reply(reply)
                .intent(intent)
                .quickReplies(quickReplies)
                .sensitive(false)
                .build();
    }

    private ChatResponse action(String reply, String intent, String action, List<String> quickReplies) {
        ChatResponse response = response(reply, intent, quickReplies);
        response.setRecommendedAction(action);
        return response;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String money(BigDecimal value) {
        return "PHP " + (value == null ? "0.00" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    private String label(String value) {
        if (value == null) return "Unknown";
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "Unavailable";
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "*".repeat(Math.max(0, trimmed.length() - visible)) + trimmed.substring(trimmed.length() - visible);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unavailable" : value;
    }
}
