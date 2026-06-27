package com.premier.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    @Value("${ALLOWED_ORIGINS:" + AllowedOrigins.DEFAULT + "}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry) {
        // Admin dashboard subscribes
        registry.enableSimpleBroker("/topic");
        // Clients send messages 
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(AllowedOrigins.parse(allowedOrigins).toArray(String[]::new))
                .withSockJS();
    }
}
