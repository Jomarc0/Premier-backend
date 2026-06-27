package com.premier.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.dialogflow.v2.*;
import com.premier.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DialogflowService {

    @Value("${dialogflow.project-id}")
    private String projectId;

    @Value("${dialogflow.language-code:en}")
    private String languageCode;

    @Value("${dialogflow.credentials-path}")
    private String credentialsPath;

    private SessionsClient sessionsClient;

    @PostConstruct
    public void init() {
        try {
            //Works for local classpath AND Render /app/ files
            InputStream credStream = getCredentialsStream();
            
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(credStream)
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

            SessionsSettings settings = SessionsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

            this.sessionsClient = SessionsClient.create(settings);
            log.info("Dialogflow initialized. Project: {}", projectId);

        } catch (Exception e) {
            log.error("Dialogflow init failed: {}", e.getMessage(), e);
        }
    }


    private InputStream getCredentialsStream() throws IOException {
        if (credentialsPath.startsWith("classpath:")) {
            String file = credentialsPath.replace("classpath:", "");
            return new ClassPathResource(file).getInputStream();
        } 
        return new FileInputStream(credentialsPath);
    }

    public ChatResponse detectIntent(String userMessage, String sessionId) {
        if (sessionsClient == null) {
            return fallbackResponse("Our AI support is temporarily unavailable. Please call (123) 456-7890.");
        }

        // Use provided sessionId or generate a new one
        String resolvedSession = (sessionId != null && !sessionId.isBlank())
                ? sessionId
                : UUID.randomUUID().toString();

        try {
            SessionName session = SessionName.of(projectId, resolvedSession);

            TextInput textInput = TextInput.newBuilder()
                    .setText(userMessage)
                    .setLanguageCode(languageCode)
                    .build();

            QueryInput queryInput = QueryInput.newBuilder()
                    .setText(textInput)
                    .build();

            DetectIntentResponse response = sessionsClient.detectIntent(session, queryInput);
            QueryResult result = response.getQueryResult();

            String replyText = result.getFulfillmentText();
            String intentName = result.getIntent().getDisplayName();
            float confidence = result.getIntentDetectionConfidence();

            // QUICK REPLIES FROM CUSTOM PAYLOAD 
            List<String> quickReplies = new ArrayList<>();
            for (Intent.Message msg : result.getFulfillmentMessagesList()) {
                if (msg.hasPayload()) {
                    try {
                        com.google.protobuf.Struct payload = msg.getPayload();
                        com.google.protobuf.Value qrValue = payload.getFieldsMap().get("quickReplies");
                        if (qrValue != null && qrValue.hasStructValue()) {
                            com.google.protobuf.Value listValue = qrValue.getStructValue()
                                .getFieldsMap().get("quickReplies");
                            if (listValue != null && listValue.hasListValue()) {
                                listValue.getListValue().getValuesList()
                                    .forEach(v -> quickReplies.add(v.getStringValue()));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Could not parse quick replies from payload: {}", e.getMessage());
                    }
                }
            }

            log.info("Dialogflow → Intent: [{}] | Confidence: {:.2f} | Session: {}",
                    intentName, confidence, resolvedSession);

            // Fallback if empty response
            if (replyText == null || replyText.isBlank()) {
                replyText = "I'm not sure how to help with that. Please contact support at (123) 456-7890.";
            }

            return ChatResponse.builder()
                    .success(true)
                    .reply(replyText)
                    .intent(intentName)
                    .quickReplies(quickReplies.isEmpty() ? null : quickReplies)
                    .build();

        } catch (Exception e) {
            log.error("Dialogflow detectIntent failed: {}", e.getMessage(), e);
            return fallbackResponse(
                    "Our support bot encountered an issue. Please try again or contact support at (123) 456-7890."
            );
        }
    }

    private ChatResponse fallbackResponse(String message) {
        return ChatResponse.builder()
                .success(false)
                .reply(message)
                .errorCode("DIALOGFLOW_ERROR")
                .build();
    }
}
