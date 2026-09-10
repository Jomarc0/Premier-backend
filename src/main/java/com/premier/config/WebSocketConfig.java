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

    private final com.premier.security.RealtimeAuthorization authorization;

    public WebSocketConfig(com.premier.security.RealtimeAuthorization authorization) {
        this.authorization = authorization;
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
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                return authorization.inbound(message);
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                return authorization.outbound(message);
            }
        });
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(8192).setSendBufferSizeLimit(65536).setSendTimeLimit(10000)
                .setTimeToFirstMessage(10000);
    }
}
