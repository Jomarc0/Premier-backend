package com.premier.service;
import java.util.Locale;
/** External AI receives only fixed topics, never raw passenger text or account identifiers. */
public final class AiPrivacy {
    private AiPrivacy() {}
    public static String topic(String input) {
        String text=input==null?"":input.toLowerCase(Locale.ROOT);
        if(text.contains("lost")||text.contains("stolen"))return "General guidance about a lost transport card";
        if(text.contains("topup")||text.contains("top-up")||text.contains("top up"))return "General guidance about transport wallet top-ups";
        if(text.contains("qr")||text.contains("nfc")||text.contains("rfid"))return "General guidance about supported transport fare payment methods";
        if(text.contains("ticket")||text.contains("support"))return "General guidance about contacting passenger support";
        if(text.contains("route")||text.contains("bus"))return "General guidance about finding current transport route information";
        return "General passenger assistance";
    }
}
