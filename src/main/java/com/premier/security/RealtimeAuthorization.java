package com.premier.security;

import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.security.AdminJwtUtil;
import com.premier.repository.PassengerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** The simple broker is a notification hint; HTTP remains the authoritative read path. */
@Component
@RequiredArgsConstructor
public class RealtimeAuthorization {
    private final JwtUtil passengerJwt;
    private final AdminJwtUtil adminJwt;
    private final PassengerRepository passengers;
    private final AdminRepository admins;
    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    private static final int MAX_SESSIONS = 5000;

    public Message<?> inbound(Message<?> message) {
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (a == null || a.getSessionId() == null) throw denied();
        if (a.getCommand() == StompCommand.DISCONNECT) {
            sessions.remove(a.getSessionId());
            return message;
        }
        if (a.getCommand() == StompCommand.CONNECT) {
            String header = a.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || header.length() > 4096) throw denied();
            String token = header.substring(7);
            var authentication = authenticate(token);
            synchronized (sessions) {
                if (sessions.containsKey(a.getSessionId()) || sessions.size() >= MAX_SESSIONS) throw denied();
                sessions.put(a.getSessionId(), token);
            }
            a.setUser(authentication);
            return message;
        }
        var auth = authenticate(sessions.get(a.getSessionId()));
        if (a.getCommand() == StompCommand.SUBSCRIBE) {
            String role = auth.getAuthorities().iterator().next().getAuthority();
            String destination = a.getDestination();
            boolean admin = role.equals("ADMIN") || role.equals("SUPER_ADMIN");
            boolean allowed = "/topic/admin/realtime".equals(destination) && admin
                    || "/topic/staff/realtime".equals(destination) && (admin || role.equals("STAFF"))
                    || "/user/queue/realtime".equals(destination) && role.equals("ROLE_PASSENGER");
            if (!allowed) throw denied();
        } else if (a.getCommand() != null && a.getCommand() != StompCommand.UNSUBSCRIBE) {
            // No application MessageMapping exists: client SEND, ACK, transactions, etc. are forbidden.
            throw denied();
        }
        return message;
    }

    public Message<?> outbound(Message<?> message) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(message);
        if (a.getCommand() != StompCommand.MESSAGE) return message;
        try {
            // Re-read current account state before every delivery, including quiet clients.
            authenticate(a.getSessionId() == null ? null : sessions.get(a.getSessionId()));
            return message;
        } catch (RuntimeException invalid) {
            if (a.getSessionId() != null) sessions.remove(a.getSessionId());
            return null;
        }
    }

    private UsernamePasswordAuthenticationToken authenticate(String token) {
        if (token == null) throw denied();
        if (adminJwt.isAdminToken(token) && !adminJwt.isEnrollmentToken(token)) {
            var admin = admins.findById(adminJwt.extractAdminId(token)).orElseThrow(RealtimeAuthorization::denied);
            if (!adminJwt.isCurrentSession(token, admin) || !Boolean.TRUE.equals(admin.getActive()) || admin.isLocked()
                    || !admin.getRole().name().equals(adminJwt.extractRole(token))
                    || (admin.getRole() != AdminRole.STAFF && !Boolean.TRUE.equals(admin.getIs2FaEnabled()))) throw denied();
            return auth(admin.getId(), admin.getRole().name());
        }
        if (passengerJwt.isFullToken(token)) {
            var passenger = passengers.findById(passengerJwt.extractPassengerId(token)).orElseThrow(RealtimeAuthorization::denied);
            if (!passengerJwt.isCurrentSession(token, passenger)) throw denied();
            return auth(passenger.getId(), "ROLE_PASSENGER");
        }
        throw denied();
    }

    private static UsernamePasswordAuthenticationToken auth(Long id, String role) {
        return new UsernamePasswordAuthenticationToken(id.toString(), null, List.of(new SimpleGrantedAuthority(role)));
    }
    private static SecurityException denied() { return new SecurityException("Realtime authorization denied."); }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) { sessions.remove(event.getSessionId()); }

    @Scheduled(fixedDelay = 30000)
    public void expire() {
        sessions.entrySet().removeIf(entry -> !passengerJwt.isFullToken(entry.getValue())
                && !adminJwt.isTokenValid(entry.getValue()));
    }
}
