package com.premier.payment.service;

import com.google.firebase.messaging.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class PaymentPushSender {
    public void send(String destination, String kind, String reference, long timeoutMillis) throws Exception {
        var delivery = FirebaseMessaging.getInstance().sendAsync(Message.builder().setToken(destination)
            .setNotification(Notification.builder().setTitle("Premier payment update")
                .setBody("Open Premier to view your payment status.").build())
            .putData("type", kind).putData("reference", reference).build());
        try { delivery.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception failure) { delivery.cancel(true); throw failure; }
    }
}
