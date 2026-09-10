package com.premier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        var client = new RestTemplate(factory);
        // This manually constructed client does not inherit Boot's preferred-json-mapper property.
        // Keep provider JsonNode payloads and persisted payment snapshots on the same mapper.
        client.setMessageConverters(java.util.List.of(
                new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                new org.springframework.http.converter.ResourceHttpMessageConverter(),
                new org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter(),
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)));
        return client;
    }
}
