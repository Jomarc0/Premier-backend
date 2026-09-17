package com.premier.payment.service;

import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;

/** Never log provider exception text: it can contain a registration or request body. */
public final class FcmDiagnostics {
    private FcmDiagnostics() {}

    public static Throwable cause(Throwable failure) {
        while ((failure instanceof java.util.concurrent.ExecutionException
                || failure instanceof java.util.concurrent.CompletionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    public static boolean unregistered(Throwable failure) {
        return cause(failure) instanceof FirebaseMessagingException fcm
                && fcm.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED;
    }

    public static String code(Throwable failure) {
        var root = cause(failure);
        if (root instanceof FirebaseMessagingException fcm) {
            return String.valueOf(fcm.getMessagingErrorCode() != null ? fcm.getMessagingErrorCode() : fcm.getErrorCode());
        }
        return root.getClass().getSimpleName();
    }

    public static Object httpStatus(Throwable failure) {
        return cause(failure) instanceof FirebaseMessagingException fcm && fcm.getHttpResponse() != null
                ? fcm.getHttpResponse().getStatusCode() : "UNAVAILABLE";
    }
}
