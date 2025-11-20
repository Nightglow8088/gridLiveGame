package com.example.pupupudemo.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AgentAiService {

    private static final String API_URL = "https://yhm-staging.hw.pufflearn.com/ai/completion/complete";
    private static final String API_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJib21pbmd4dWFuIiwiaWQiOiI2OTFkNTU1NDZjNDM2MDRiOTRjMTdlMjkiLCJpYXQiOjE3NjM1MzAwNjgsImV4cCI6MTc2Mzk2MjA2OH0.tArHV6LjAjJegDociuRDSbqpBTN4S_trrtTpO6YiRCk";

    private final RestTemplate restTemplate = new RestTemplate();

    public String getAgentDecision(String agentStateJson) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + API_TOKEN);

            // 🔥 关键修改：在 System Prompt 里增加“强制采集”的指令
            String systemPrompt = """
                    You are an autonomous agent in a 2D grid survival game.
                    Your Goal: Survive and Craft items.
                    
                    RULES:
                    1. **PRIORITY #1**: If you see "STANDING_ON <Resource>" in your vision, you MUST choose action "HARVEST". Do not move away!
                    2. To craft an Axe, you need 2 Stones. HARVEST stones whenever you see them!
                    3. Survive: Consume 1 Wheat per turn.
                    
                    Input Context:
                    %s
                    
                    REQUIRED OUTPUT FORMAT (JSON ONLY):
                    {
                        "action": "MOVE" | "HARVEST" | "CRAFT" | "TRADE",
                        "direction": "UP" | "DOWN" | "LEFT" | "RIGHT" | null,
                        "target_resource": "Wheat" | "Stone" | null,
                        "reasonING": "Short explanation"
                    }
                    """.formatted(agentStateJson);

            AiRequest requestPayload = AiRequest.builder()
                    .provider("azureOpenAI")
                    .model("gpt-4o")
                    .messages(List.of(new AiRequest.Message("system", systemPrompt)))
                    .parameters(Map.of("response_format", "json_object", "temperature", 0.5)) // 温度调低点，让它更听话
                    .build();

            HttpEntity<AiRequest> entity = new HttpEntity<>(requestPayload, headers);
            ResponseEntity<AiResponse> response = restTemplate.postForEntity(API_URL, entity, AiResponse.class);

            if (response.getBody() != null && response.getBody().getMessage() != null) {
                return response.getBody().getMessage().getContent();
            }

        } catch (Exception e) {
            System.err.println("AI API Error: " + e.getMessage());
            return "{\"action\": \"WAIT\"}";
        }
        return "{\"action\": \"WAIT\"}";
    }

    // DTO Classes
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AiRequest {
        private String provider;
        private String model;
        private List<Message> messages;
        private Map<String, Object> parameters;
        @Data @AllArgsConstructor @NoArgsConstructor
        public static class Message { private String role; private String content; }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AiResponse {
        private ResponseMessage message;
        @Data @NoArgsConstructor @AllArgsConstructor
        public static class ResponseMessage { private String content; }
    }
}