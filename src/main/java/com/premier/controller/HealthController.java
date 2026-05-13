package com.premier.controller;

import com.google.firebase.FirebaseApp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {
    
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "OK",
            "firebase", FirebaseApp.getApps().isEmpty() ? "NOT_INITIALIZED" : "OK",
            "timestamp", System.currentTimeMillis()
        );
    }
}