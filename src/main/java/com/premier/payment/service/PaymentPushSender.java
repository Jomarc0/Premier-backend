package com.premier.payment.service;

import com.google.firebase.messaging.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class PaymentPushSender {
    private final com.premier.repository.TransactionRepository transactions;

    public String sendPayment(Long passengerId, String destination, String kind, String reference, long timeoutMillis) throws Exception {
        var transaction = transactions.findByReferenceNumberAndPassengerId(reference, passengerId)
            .filter(t -> t.getStatus() == com.premier.model.TransactionStatus.SUCCESS)
            .filter(t -> ("TOPUP".equals(kind) && t.getType() == com.premier.model.TransactionType.TOPUP)
                || ("FARE".equals(kind) && (t.getType() == com.premier.model.TransactionType.FARE_DEDUCTION
                    || t.getType() == com.premier.model.TransactionType.RIDE_FARE)))
            .filter(t -> t.getAmount() != null && t.getBalanceAfter() != null);
        if (transaction.isEmpty()) {
            log.warn("[FCM] payment details unavailable passenger={} reference={} kind={}", passengerId, reference, kind);
            return send(destination, kind, reference, timeoutMillis);
        }
        var payment = transaction.get();
        String amount = money(payment.getAmount());
        String balance = money(payment.getBalanceAfter());
        String title = "TOPUP".equals(kind) ? "Top-up successful" : "Fare deducted";
        String body = "TOPUP".equals(kind)
            ? amount + " added. New balance: " + balance + "."
            : amount + " paid. Remaining balance: " + balance + ".";
        return sendNotification(destination, kind, reference, timeoutMillis, title, body);
    }

    private String money(java.math.BigDecimal amount) {
        return "\u20b1" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    public String send(String destination, String kind, String reference, long timeoutMillis) throws Exception {
        return sendNotification(destination, kind, reference, timeoutMillis,
            "TEST".equals(kind) ? "Firebase Test" : "Premier payment update",
            "TEST".equals(kind) ? "Your mobile notification system is working." : "Open Premier to view your payment status.");
    }

    private String sendNotification(String destination, String kind, String reference, long timeoutMillis,
            String title, String body) throws Exception {
        var delivery = FirebaseMessaging.getInstance().sendAsync(Message.builder().setToken(destination)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                    .setChannelId("default")
                    .setSound("default")
                    .build())
                .build())
            .putData("type", kind).putData("reference", reference).build());
        try {
            String messageId = delivery.get(timeoutMillis, TimeUnit.MILLISECONDS);
            log.debug("[FCM] result=ACCEPTED reference={} kind={} messageId={}", reference, kind, messageId);
            return messageId;
        }
        catch (Exception failure) { delivery.cancel(true); throw failure; }
    }
}
