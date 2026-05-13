package com.premier.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.config-path}")
    private String firebaseConfigPath;

    @PostConstruct
    public void initFirebase() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                InputStream stream = getConfigStream();
                
                FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build();

                FirebaseApp.initializeApp(options);
                log.info("Firebase initialized from: {}", firebaseConfigPath);
            }
        } catch (IOException e) {
            log.error("Firebase init failed: {}", e.getMessage());
            throw new RuntimeException("Firebase initialization failed", e);
        }
    }
    
    private InputStream getConfigStream() throws IOException {
        if (firebaseConfigPath.startsWith("classpath:")) {
            String file = firebaseConfigPath.replace("classpath:", "");
            return new ClassPathResource(file).getInputStream();
        }
        return new FileInputStream(firebaseConfigPath);
    }
}