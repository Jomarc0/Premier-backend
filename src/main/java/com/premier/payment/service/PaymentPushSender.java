package com.premier.payment.service;

import com.google.firebase.messaging.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@lombok.extern.slf4j.Slf4j
public class PaymentPushSender {
    public String send(String destination, String kind, String reference, long timeoutMillis) throws Exception {
        var delivery = FirebaseMessaging.getInstance().sendAsync(Message.builder().setToken(destination)
            .setNotification(Notification.builder().setTitle("TEST".equals(kind) ? "Firebase Test" : "Premier payment update")
                .setBody("TEST".equals(kind) ? "Your mobile notification system is working." : "Open Premier to view your payment status.").build())
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
