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

import java.time.LocalDateTime;
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
                // ✅ أول فلتر: الحالات الأربع (not sent/sent)
                .filter(contact -> {
                    String phone1Status = contact.getPhone1Status() == null ? "" : contact.getPhone1Status().toLowerCase();
                    String phone2Status = contact.getPhone2Status() == null ? "" : contact.getPhone2Status().toLowerCase();

                    boolean isPhone1NotSent = "not sent".equals(phone1Status);
                    boolean isPhone1Sent    = "sent".equals(phone1Status);

                    boolean isPhone2NotSent = "not sent".equals(phone2Status);
                    boolean isPhone2Sent    = "sent".equals(phone2Status);

                    return (isPhone1NotSent && isPhone2Sent)    // (not sent , sent)
                            || (isPhone1Sent && isPhone2NotSent)    // (sent , not sent)
                            || (isPhone1Sent && isPhone2Sent)       // (sent , sent)
                            || (isPhone1NotSent && isPhone2NotSent); // (not sent , not sent)
                })
                // keep contact only if at least one attempt exists
                .filter(contact -> contact.getLastAttempt1() != null || contact.getLastAttempt2() != null)
                .filter(contact -> {
                    boolean hasConversationId = contact.getConversationId() != null && !contact.getConversationId().isEmpty();
                    return !hasConversationId || contact.getCallDurationSeconds() != null;
                })
                .filter(contact -> {
                    boolean phone1Valid = checkAttemptValid(contact.getLastResult1(), contact.getConversationId(), contact.getConversationEndTime());
                    boolean phone2Valid = checkAttemptValid(contact.getLastResult2(), contact.getConversationId(), contact.getConversationEndTime());
                    return phone1Valid || phone2Valid;
                })
                .collect(Collectors.toList());


        if (unsentContacts.isEmpty()) {
            logger.info("[STEP 2] Sending contacts to Tagger..." + unsentContacts);
            logger.info("✅ All contacts have already been sent. Nothing to send.");
            return;
        }

        // --- collect only attempts that are "not sent" ---
        List<Map<String, Object>> callAttempts = unsentContacts.stream()
                .flatMap(contact -> convertContactToCallAttempt(contact).stream()
                        .filter(attempt -> {
                            String phone = (String) attempt.get("phone");
                            if (phone.equals(contact.getPhone1())) {
                                return "not sent".equalsIgnoreCase(contact.getPhone1Status());
                            } else if (phone.equals(contact.getPhone2())) {
                                return "not sent".equalsIgnoreCase(contact.getPhone2Status());
                            }
                            return false;
                        })
                )
                .collect(Collectors.toList());

        if (callAttempts.isEmpty()) {
            logger.info("[STEP 2] Sending contacts to Tagger..." + unsentContacts);
            logger.info("✅ All contacts have already been sent. Nothing to send.");
            return;
        }
        logger.info(callAttempts.toString());

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
                return restTemplate.exchange(TAGGER_API_URL, HttpMethod.POST, requestEntity, String.class);
            });

            logger.info("✅ Response from Tagger: {}", response.getBody());

            if (response.getStatusCode().is2xxSuccessful()) {
                // تحديث status لكل contact بناءً على الـ phone الذي تم إرساله
                for (Contact contact : unsentContacts) {
                    for (Map<String, Object> attempt : callAttempts) {
                        String phone = (String) attempt.get("phone");
                        if (contact.getPhone1() != null && contact.getPhone1().equals(phone)) {
                            contact.setPhone1Status("sent");
                        }
                        if (contact.getPhone2() != null && contact.getPhone2().equals(phone)) {
                            contact.setPhone2Status("sent");
                        }
                    }
                }

                contactRepo.saveAll(unsentContacts);
                logger.info("✅ Status updated to 'sent' for sent attempts only.");
            }

        } catch (Exception e) {
            logger.error("🚫 Final failure after retries: {}", e.getMessage(), e);
        }
    }

    private boolean checkAttemptValid(String lastResult, String conversationId, LocalDateTime conversationEndTime) {
        if (lastResult == null) return false;

        List<String> validResults = Arrays.asList(
                "Call Back",
                "Answer Machine\\Voice Mail",
                "Busy",
                "No Answer",
                "Rejected Order",
                "Confirmed Order",
                "Customer Hung Up",
                "Dead Call",
                "DNC Do Not Call",
                "Postponed Order"
        );
        boolean isInboundOutbound = lastResult.toUpperCase().startsWith("ININ-OUTBOUND");

        if (conversationEndTime == null) {
            if (validResults.stream().anyMatch(v -> v.equalsIgnoreCase(lastResult))|| isInboundOutbound) {
                return true;
            } else {
                return conversationId == null || conversationId.isEmpty();
            }
        } else {
            return true;
        }
    }

    private List<Map<String, Object>> convertContactToCallAttempt(Contact contact) {
        List<Map<String, Object>> attempts = new ArrayList<>();

        // --- First attempt (phone1) ---
        if (contact.getPhone1() != null || contact.getLastAttempt1() != null || contact.getLastResult1() != null) {
            Map<String, Object> attempt1 = new HashMap<>();
            attempt1.put("order_id", contact.getOrderId());
            attempt1.put("phone", contact.getPhone1());
            if (contact.getLastAttempt1() != null) {
                attempt1.put("call_datetime", contact.getLastAttempt1().atOffset(ZoneOffset.UTC).toString());
                attempt1.put("wrap_up_reason", contact.getLastResult1() != null ? contact.getLastResult1() : "Empty");
                attempt1.put("call_duration", contact.getCallDurationSeconds() != null ? contact.getCallDurationSeconds() : 0);
                attempt1.put("agent_id", contact.getAgentEmail() != null ? contact.getAgentEmail() : "unknown");
                attempts.add(attempt1);
            }
        }

        // --- Second attempt (phone2) ---
        if (!"ININ-OUTBOUND-INVALID-PHONE-NUMBER".equalsIgnoreCase(contact.getLastResult2())) {
            if (contact.getPhone2() != null || contact.getLastAttempt2() != null || contact.getLastResult2() != null) {
                Map<String, Object> attempt2 = new HashMap<>();
                attempt2.put("order_id", contact.getOrderId());
                attempt2.put("phone", contact.getPhone2());
                if (contact.getLastAttempt2() != null) {
                    attempt2.put("call_datetime", contact.getLastAttempt2().atOffset(ZoneOffset.UTC).toString());
                    attempt2.put("wrap_up_reason", contact.getLastResult2() != null ? contact.getLastResult2() : "Empty");
                    attempt2.put("call_duration", contact.getCallDurationSeconds() != null ? contact.getCallDurationSeconds() : 0);
                    attempt2.put("agent_id", contact.getAgentEmail() != null ? contact.getAgentEmail() : "unknown");
                    attempts.add(attempt2);
                }
            }
        }

        return attempts;
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
