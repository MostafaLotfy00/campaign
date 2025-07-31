package com.contacts.sheet.service;

import com.contacts.sheet.entity.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaggerService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final String TAGGER_API_URL = "https://admin-internal.dev.taager.com/dialer/webhooks/genesys/call-attempts";

    public void sendContactsToTagger(List<Contact> contacts) {
        String accessToken = getAccessTokenFromKeycloak();
        if (accessToken == null) {
            System.err.println("❌ Failed to retrieve access token. Aborting.");
            return;
        }

        List<Map<String, Object>> callAttempts = contacts.stream()
                .map(this::convertContactToCallAttempt)
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("call_attempts", callAttempts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        try {
            // ✅ Log payload as pretty JSON
            String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            System.out.println("📦 JSON Payload:\n" + jsonPayload);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    TAGGER_API_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            System.out.println("✅ Response from Tagger: " + response.getBody());
        } catch (Exception e) {
            System.err.println("❌ Error sending contacts: " + e.getMessage());
        }
    }

    private Map<String, Object> convertContactToCallAttempt(Contact contact) {
        Map<String, Object> callAttempt = new HashMap<>();

        // Required: order_id
        callAttempt.put("order_id", String.valueOf(contact.getOrderId()));

        // Required: call_datetime
        if (contact.getLastAttempt() != null) {
            callAttempt.put("call_datetime", contact.getLastAttempt().atOffset(ZoneOffset.UTC).toString());
        }

        // Default: wrap_up_reason = "No Answer"
        callAttempt.put("wrap_up_reason", contact.getWrapUpCode() != null ? contact.getWrapUpCode() : "No Answer");

        // Default: call_duration = 0
        callAttempt.put("call_duration", (contact.getCallDurationSeconds() != null && contact.getCallDurationSeconds() > 0)
                ? contact.getCallDurationSeconds()
                : 0);

        // Default: agent_id = "unknown"
        callAttempt.put("agent_id", contact.getSelectedAgentId() != null ? contact.getSelectedAgentId() : "unknown");

        // Optional: callback_requested logic
        if ("Callback Requested".equalsIgnoreCase(contact.getWrapUpCode())) {
            callAttempt.put("callback_requested", true);

            Map<String, Object> callbackDetails = new HashMap<>();
            if (contact.getConversationStartTime() != null) {
                callbackDetails.put("callback_time", contact.getConversationStartTime().atOffset(ZoneOffset.UTC).toString());
            }
            callbackDetails.put("callback_agent_id", contact.getSelectedAgentId());

            callAttempt.put("callback_details", callbackDetails);
        }

        return callAttempt;
    }

    private String getAccessTokenFromKeycloak() {
        String url = "https://keycloak.dev.taager.com/realms/taager_admin/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", "integration");
        body.add("username", "1rni21onr22infi23n");
        body.add("password", "DMiYa3U2M7J9Vnc12312");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode json = objectMapper.readTree(response.getBody());
                return json.get("access_token").asText();
            } else {
                System.err.println("❌ Failed to retrieve token. Status: " + response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            System.err.println("❌ Exception during token request: " + e.getMessage());
            return null;
        }
    }
}
