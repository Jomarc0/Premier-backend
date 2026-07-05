package com.premier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String model;

    public String enhanceSupportReply(String userMessage, String safeContextReply) {
        if (apiKey == null || apiKey.isBlank()) {
            return safeContextReply;
        }

        String prompt = """
                You are the Premier Transit passenger support assistant.
                Answer in plain, helpful language.
                Do not expose tokens, API keys, RFID UID values, payment secrets, internal IDs, database records, or personal information.
                Do not approve refunds, top-ups, card freezes, emergency actions, or account changes.
                If the user asks for a sensitive action, tell them an administrator must review it.

                User message:
                %s

                Safe system context:
                %s
                """.formatted(limit(userMessage, 500), limit(safeContextReply, 800));

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                    + ":generateContent?key=" + apiKey;

            Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (text.isTextual() && !text.asText().isBlank()) {
                return text.asText().trim();
            }
        } catch (Exception ex) {
            log.warn("Gemini enhancement skipped: {}", ex.getMessage());
        }

        return safeContextReply;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("<[^>]*>", "").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
