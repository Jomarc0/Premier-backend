package com.premier.config;

import com.premier.admin.security.AdminJwtUtil;
import com.premier.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.*;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    @Value("${ALLOWED_ORIGINS:" + AllowedOrigins.DEFAULT + "}")
    private String allowedOrigins;

    private final JwtUtil jwtUtil;
    private final AdminJwtUtil adminJwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil,
                           AdminJwtUtil adminJwtUtil) {
        this.jwtUtil = jwtUtil;
        this.adminJwtUtil = adminJwtUtil;
    }

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry) {
        // Admin dashboard subscribes
        registry.enableSimpleBroker("/topic", "/queue");
        // Clients send messages 
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(AllowedOrigins.parse(allowedOrigins).toArray(String[]::new))
                .withSockJS();
        // Native STOMP endpoint for React Native, which does not implement the
        // SockJS browser transports. It shares the exact same authentication.
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(AllowedOrigins.parse(allowedOrigins).toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String token = bearerToken(accessor.getFirstNativeHeader("Authorization"));
                    if (token == null) {
                        throw new SecurityException("WebSocket authentication required.");
                    }
                    accessor.setUser(authenticationFor(token));
                    return message;
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    authorizeSubscription(accessor);
                }

                return message;
            }
        });
    }

    private UsernamePasswordAuthenticationToken authenticationFor(String token) {
        if (adminJwtUtil.isAdminToken(token) && adminJwtUtil.isTokenValid(token)) {
            String role = adminJwtUtil.extractRole(token);
            return new UsernamePasswordAuthenticationToken(
                    adminJwtUtil.extractAdminId(token),
                    null,
                    List.of(new SimpleGrantedAuthority(role)));
        }
        if (jwtUtil.isFullToken(token)) {
            return new UsernamePasswordAuthenticationToken(
                    jwtUtil.extractPassengerId(token),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")));
        }
        throw new SecurityException("Invalid WebSocket token.");
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)) {
            throw new SecurityException("WebSocket authentication required.");
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        boolean admin = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ADMIN")
                        || a.getAuthority().equals("SUPER_ADMIN"));
        boolean staffOrAdmin = admin || auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("STAFF"));
        if (destination.startsWith("/topic/admin/") && !admin) {
            throw new SecurityException("Not allowed to subscribe to admin real-time events.");
        }
        if (destination.startsWith("/topic/bus-locations") && !staffOrAdmin) {
            throw new SecurityException("Not allowed to subscribe to bus locations.");
        }
        if (destination.startsWith("/topic/staff/") && !staffOrAdmin) {
            throw new SecurityException("Not allowed to subscribe to staff real-time events.");
        }
        if (destination.startsWith("/user/") && auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_PASSENGER"))) {
            throw new SecurityException("Not allowed to subscribe to passenger real-time events.");
        }
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }
}
