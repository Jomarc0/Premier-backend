package com.premier.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/** Defines Philippine Time as the application's business and display timezone. */
@Configuration
public class ApplicationTimeConfig {

    public static final ZoneId PHT = ZoneId.of("Asia/Manila");

    @PostConstruct
    void configureProcessTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone(PHT));
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer phtDateTimeSerialization() {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        JsonSerializer<LocalDateTime> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
                    throws java.io.IOException {
                generator.writeString(value.atZone(PHT).format(formatter));
            }
        };
        return builder -> builder.serializerByType(LocalDateTime.class, serializer);
    }
}
