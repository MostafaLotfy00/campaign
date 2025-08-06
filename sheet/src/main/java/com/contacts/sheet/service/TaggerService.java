package com.contacts.sheet.service;

import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.entity.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static com.contacts.sheet.configration.RetryUtils.retry;

@Service
@RequiredArgsConstructor
public class TaggerService {

    private static final Logger logger = LoggerFactory.getLogger(TaggerService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    private final ContactRepo contactRepo;

    private final String TAGGER_API_URL = "https://admin-internal.dev.taager.com/dialer/webhooks/genesys/call-attempts";

    public void sendContactsToTagger(List<Contact> contacts) {
        String accessToken = getAccessTokenFromKeycloak();
        if (accessToken == null) {
            logger.error("❌ Failed to retrieve access token. Aborting.");
            return;
        }


        List<Contact> unsentContacts = contacts.stream()
                .filter(contact -> "not sent".equalsIgnoreCase(contact.getStatus()))
                .filter(contact -> contact.getLastAttempt() != null)
                .filter(contact -> {
                    if (contact.getConversationEndTime() == null) {
                        if ("callback".equalsIgnoreCase(contact.getLastResult())) {
                            return true;
                        } else {
                            // check conversationId
                            return contact.getConversationId() == null || contact.getConversationId().isEmpty();
                        }
                    } else {
                        return true;
                    }
                })
                .collect(Collectors.toList());


        if (unsentContacts.isEmpty()) {
            logger.info("[STEP 2] Sending contacts to Tagger..."+unsentContacts);
            logger.info("✅ All contacts have already been sent. Nothing to send.");
            return;
        }

        List<Map<String, Object>> callAttempts = unsentContacts.stream()
                .map(this::convertContactToCallAttempt)
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("call_attempts", callAttempts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        try {
            String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            logger.info("📦 JSON Payload:\n{}", jsonPayload);

            ResponseEntity<String> response = retry(3, 2000, () -> {
                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);
                return restTemplate.exchange(
                        TAGGER_API_URL,
                        HttpMethod.POST,
                        requestEntity,
                        String.class
                );
            });

            logger.info("✅ Response from Tagger: {}", response.getBody());

            if (response.getStatusCode().is2xxSuccessful()) {
                unsentContacts.forEach(contact -> contact.setStatus("sent"));
                contactRepo.saveAll(unsentContacts);
                logger.info("✅ Status updated to 'sent' for all sent contacts.");
            }

        } catch (Exception e) {
            logger.error("🚫 Final failure after retries: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> convertContactToCallAttempt(Contact contact) {
        Map<String, Object> callAttempt = new HashMap<>();

        callAttempt.put("order_id", String.valueOf(contact.getOrderId()));

        if (contact.getLastAttempt() != null) {
            callAttempt.put("call_datetime", contact.getLastAttempt().atOffset(ZoneOffset.UTC).toString());
        }

        callAttempt.put("wrap_up_reason", contact.getWrapUpCode() != null ? contact.getWrapUpCode() : "No Answer");
        callAttempt.put("call_duration", (contact.getCallDurationSeconds() != null && contact.getCallDurationSeconds() > 0)
                ? contact.getCallDurationSeconds()
                : 0);
        callAttempt.put("agent_id", contact.getSelectedAgentId() != null ? contact.getSelectedAgentId() : "unknown");

        if ("Callback".equalsIgnoreCase(contact.getLastResult())) {
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
            return retry(3, 2000, () -> {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    JsonNode json = objectMapper.readTree(response.getBody());
                    return json.get("access_token").asText();
                } else {
                    throw new RuntimeException("Failed to retrieve token. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed to get access token after retries: {}", e.getMessage(), e);
            return null;
        }
    }
}
