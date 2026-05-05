package com.premier.service;

import com.google.firebase.messaging.*;
import com.premier.request.FcmTokenRequest;
import com.premier.response.ApiResponse;
import com.premier.model.Passenger;
import com.premier.repository.PassengerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FirebaseService {

    private final PassengerRepository passengerRepository;

    //  SINGLE NOTIFICATION 

    public void sendNotification(
            String fcmToken,
            String title,
            String body) {
        sendNotification(fcmToken, title, body, null);
    }

    public void sendNotification(
            String fcmToken,
            String title,
            String body,
            Map<String, String> data) {

        if (fcmToken == null || fcmToken.isEmpty()) {
            log.warn("FCM token is null — skipping notification");
            return;
        }

        try {
            Message.Builder messageBuilder = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build())
                .setAndroidConfig(AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(
                        AndroidNotification.builder()
                            .setSound("default")
                            .setClickAction(
                                "FLUTTER_NOTIFICATION_CLICK")
                            .build())
                    .build())
                .setApnsConfig(ApnsConfig.builder()
                    .setAps(Aps.builder()
                        .setSound("default")
                        .setBadge(1)
                        .build())
                    .build())
                .setWebpushConfig(WebpushConfig.builder()
                    .setNotification(
                        WebpushNotification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setIcon("/logo192.png")
                            .setBadge("/badge.png")
                            .build())
                    .build());

            if (data != null) {
                messageBuilder.putAllData(data);
            }

            String response = FirebaseMessaging.getInstance()
                .send(messageBuilder.build());

            log.info("✅ Notification sent: {}", response);

        } catch (FirebaseMessagingException e) {
            log.error("❌ FCM send failed: {}",
                e.getMessage());
        }
    }

    //  MULTIPLE TOKENS 

    public void sendToMultiple(
            List<String> tokens,
            String title,
            String body) {

        if (tokens == null || tokens.isEmpty()) return;

        try {
            MulticastMessage message =
                MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(
                                AndroidConfig.Priority.HIGH)
                            .build())
                    .build();

            BatchResponse response = FirebaseMessaging
                .getInstance()
                .sendEachForMulticast(message);

            log.info("✅ Multicast: {}/{} sent",
                response.getSuccessCount(),
                tokens.size());

        } catch (FirebaseMessagingException e) {
            log.error("❌ Multicast failed: {}",
                e.getMessage());
        }
    }

    // SPECIFIC NOTIFICATIONS 

    public void sendTopUpSuccess(
            String fcmToken,
            String amount,
            String newBalance) {
        sendNotification(
            fcmToken,
            "Top-up Successful! 💳",
            "₱" + amount + " added. " +
            "New balance: ₱" + newBalance,
            Map.of(
                "type", "TOPUP",
                "amount", amount,
                "newBalance", newBalance
            )
        );
    }

    public void sendFareDeduction(
            String fcmToken,
            String amount,
            String remainingBalance,
            String dropOff) {
        sendNotification(
            fcmToken,
            "Fare Deducted 🚌",
            "₱" + amount + " deducted for ride to " +
            dropOff + ". Balance: ₱" + remainingBalance,
            Map.of(
                "type", "FARE",
                "amount", amount,
                "balance", remainingBalance
            )
        );
    }

    public void sendLowBalance(
            String fcmToken,
            String balance) {
        sendNotification(
            fcmToken,
            "Low Balance Warning ⚠️",
            "Your balance is ₱" + balance +
            ". Please top up soon.",
            Map.of("type", "LOW_BALANCE")
        );
    }

    public void sendTicketUpdate(
            String fcmToken,
            String ticketId,
            String status) {
        sendNotification(
            fcmToken,
            "Support Ticket Updated 🎫",
            "Your ticket #" + ticketId +
            " is now " + status,
            Map.of(
                "type", "TICKET",
                "ticketId", ticketId,
                "status", status
            )
        );
    }

    //  UPDATE FCM TOKEN 

    public ApiResponse<String> updateFcmToken(
            Passenger passenger,
            FcmTokenRequest request) {

        passenger.setFcmToken(request.getFcmToken());
        passengerRepository.save(passenger);

        
        log.info("FCM token updated for passenger: ID={}", 
            passenger.getId());

        return ApiResponse.success(
            "FCM token updated.", "OK");
    }

    //  SEND TO ALL 

    public void broadcastToAll(
            String title, String body) {

        List<String> tokens = passengerRepository
            .findAll()
            .stream()
            .filter(p -> p.getFcmToken() != null
                && !p.getFcmToken().isEmpty())
            .map(Passenger::getFcmToken)
            .toList();

        if (!tokens.isEmpty()) {
            sendToMultiple(tokens, title, body);
            log.info("Broadcast sent to {} passengers",
                tokens.size());
        }
    }
}