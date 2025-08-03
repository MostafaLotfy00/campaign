package com.contacts.sheet.service;

import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.component.GenesysScheduler;
import com.contacts.sheet.entity.Contact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.contacts.sheet.model.ConversationDetailsResponse; // <<<<< إضافة Import جديد
import com.contacts.sheet.model.Participant; // <<<<< إضافة Import جديد
import com.contacts.sheet.model.Session;     // <<<<< إضافة Import جديد
import com.contacts.sheet.model.Segment;     // <<<<< إضافة Import جديد
import com.contacts.sheet.model.ScimUserResponse; // <<<<<< أضف هذا
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors; // <<-- إضافة Import جديد مهم هنا
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.contacts.sheet.configration.RetryUtils.retry;


@Service
public class GenesysService {

    // بنستخدم @Value عشان نجيب الـ configuration من application.properties
    @Value("${genesys.client-id}")
    private String clientId;

    @Value("${genesys.client-secret}")
    private String clientSecret;

    @Value("${genesys.region}")
    private String region;

    @Value("${genesys.contact-list-id}")
    private String contactListId;

    private final RestTemplate restTemplate; // عشان نعمل HTTP requests
    private final ContactRepo contactRepository; // عشان نتعامل مع الداتابيز
    private static final Logger logger = LoggerFactory.getLogger(GenesysService.class);
    // Constructor بيعمل حقن للـ RestTemplate والـ ContactRepository تلقائي
    public GenesysService(RestTemplate restTemplate, ContactRepo contactRepository) {
        this.restTemplate = restTemplate;
        this.contactRepository = contactRepository;
    }

    // دالة لجلب الـ Access Token من Genesys Cloud
    private String getAccessToken() {
        String authUrl = String.format("https://login.%s/oauth/token", region);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String authString = clientId + ":" + clientSecret;
        String base64AuthString = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
        headers.set("Authorization", "Basic " + base64AuthString);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);

        try {
            return retry(3, 2000, () -> {
                try {
                    String tokenResponse = restTemplate.postForObject(authUrl, requestEntity, String.class);

                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(tokenResponse);
                    String token = root.path("access_token").asText();

                    if (token == null || token.isEmpty()) {
                        throw new RuntimeException("Access token not found in response");
                    }

                    return token;
                } catch (HttpClientErrorException e) {
                    logger.error("Error getting access token: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
                    throw new RuntimeException("Failed to get access token: " + e.getResponseBodyAsString(), e);
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed to retrieve access token after retries: " + e.getMessage());
            throw new RuntimeException("Access token retrieval failed after retries.", e);
        }
    }



    public ConversationDetailsResponse fetchConversationDetails(String conversationId) {
        String accessToken = getAccessToken(); // ممكن تمرر الـ token لو مش عاوز تجيب واحد جديد كل مرة
        if (accessToken == null) {
           logger.error("Failed to obtain Access Token for Conversation Details API.");
            return null;
        }

        String detailsUrl = String.format("https://api.%s/api/v2/analytics/conversations/%s/details", region, conversationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return retry(3, 2000, () -> {
                logger.info("🔍 Fetching call details for Conversation ID: " + conversationId);

                ResponseEntity<ConversationDetailsResponse> response = restTemplate.exchange(
                        detailsUrl,
                        HttpMethod.GET,
                        requestEntity,
                        ConversationDetailsResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    logger.info("✅ Successfully fetched call details for ID: " + conversationId);
                    return response.getBody();
                } else {
                    throw new RuntimeException("❌ Failed to fetch call details. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed permanently to fetch call details for ID: " + conversationId + ": " + e.getMessage());
            return null;
        }
    }


    // دالة لبدء عملية الـ Export لـ Contact List معينة
    private String initiateContactExport(String token) {
        String exportUrl = String.format("https://api.%s/api/v2/outbound/contactlists/%s/export", region, "bdba8620-ccff-413b-a0ea-4c609601c4e7");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return retry(3, 2000, () -> {
                try {
                    ResponseEntity<String> response = restTemplate.exchange(exportUrl, HttpMethod.GET, requestEntity, String.class);
                    String exportResponse = response.getBody();

                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(exportResponse);
                    JsonNode downloadUriNode = root.path("uri");

                    if (downloadUriNode.isMissingNode() || downloadUriNode.isNull()) {
                        throw new RuntimeException("Download URI not found in export response: " + exportResponse);
                    }
                    return downloadUriNode.asText();

                } catch (HttpClientErrorException e) {
                    logger.error("Error initiating contact export: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
                    throw new RuntimeException("Failed to initiate export: " + e.getResponseBodyAsString(), e);
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed to initiate contact export after retries: " + e.getMessage());
            throw new RuntimeException("Contact export failed after retries.", e);
        }
    }

    // دالة لقراءة بيانات الـ CSV من الـ Download URI
    private String readExportData(String downloadUri, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            logger.info("Attempting to fetch content from the download link: " + downloadUri);
            ResponseEntity<String> initialResponse = restTemplate.exchange(downloadUri, HttpMethod.GET, requestEntity, String.class);
            String content = initialResponse.getBody();

            if (content != null && content.trim().startsWith("<!DOCTYPE html>")) {
                logger.info("HTML content received, attempting to extract the direct CSV link...");
                String directCsvLink = extractDirectCsvLink(content);

                if (directCsvLink != null) {
                    logger.info("Direct CSV link extracted: " + directCsvLink + ". Attempting to download from this link...");
                    ResponseEntity<String> csvResponse = restTemplate.exchange(directCsvLink, HttpMethod.GET, requestEntity, String.class);
                    return csvResponse.getBody();
                } else {
                    logger.error("Failed to extract a direct CSV link from the HTML. The content is still HTML.");
                    return content;
                }
            } else {
                logger.info("Received content that appears to be CSV directly from the original URI.");
                return content;
            }

        } catch (HttpClientErrorException e) {
            logger.error("Error fetching data from " + downloadUri + ": " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch data: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while fetching data from " + downloadUri + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to fetch data.", e);
        }
    }

    // دالة رئيسية لعملية المزامنة (هتشتغل عن طريق الـ Scheduler)
    public void syncContactsFromGenesysApi() {
        logger.info("--- Starting Genesys Cloud data synchronization ---");

        String accessToken = null;
        try {
            accessToken = getAccessToken();
            logger.info("Access Token retrieved successfully.");

            String downloadUri = initiateContactExport(accessToken);
            logger.info("Contacts export started successfully. Download link: {}", downloadUri);

            logger.info("Waiting for 10 seconds for the export file to be ready...");
            Thread.sleep(10000); // Optional: adjust time if needed

            String csvContent = readExportData(downloadUri, accessToken);

            if (csvContent == null || csvContent.trim().isEmpty() || csvContent.trim().startsWith("<!DOCTYPE html>")) {
                if (csvContent != null && csvContent.trim().startsWith("<!DOCTYPE html>")) {
                    logger.error("Failed to retrieve CSV content. Received HTML even after waiting. Check Genesys API behavior or increase wait time.");
                } else {
                    logger.error("No CSV content was retrieved or content is empty; cannot proceed with data storage.");
                }
                return;
            }

            logger.info("CSV content retrieved successfully. Processing...");
            processAndSaveCsv(csvContent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread was interrupted during wait: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Failed to synchronize Genesys Cloud data: {}", e.getMessage(), e);
        }

        logger.info("--- Finished synchronizing Genesys Cloud data ---");
    }


    private void processAndSaveCsv(String csvContent) {
        int recordsProcessed = 0;
        int recordsUpdated = 0;
        int recordsInserted = 0;

        try {
            CSVFormat csvFormat = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setQuote('"')
                    .build();

            try (CSVParser csvParser = new CSVParser(new StringReader(csvContent), csvFormat)) {

                for (CSVRecord csvRecord : csvParser) {
                    recordsProcessed++;
                    String phone = csvRecord.get("phone1");
                    String lastAttemptStr = csvRecord.get("CallRecordLastAttempt-phone1");
                    String lastResult = csvRecord.get("CallRecordLastResult-phone1");
                    String conversationId = csvRecord.get("conversationId");
                    String orderId = csvRecord.get("orderId");

                    if (phone == null || phone.trim().isEmpty()) {
                        logger.warn("Skipping row due to missing phone number: {}", csvRecord.toMap());
                        continue;
                    }

                    LocalDateTime parsedLastAttempt = null;
                    if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
                        try {
                            parsedLastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        } catch (DateTimeParseException e) {
                            logger.warn("Failed to parse lastAttempt: '{}' for phone: {}. Stored as null.", lastAttemptStr, phone);
                            parsedLastAttempt = null;
                        }
                    }

                    Optional<Contact> existingContactOptional = contactRepository.findByPhoneAndLastAttempt(phone, parsedLastAttempt);

                    if (existingContactOptional.isPresent()) {
                        Contact existingContact = existingContactOptional.get();
                        boolean updated = false;

                        if (lastResult != null && !lastResult.equals(existingContact.getLastResult())) {
                            existingContact.setLastResult(lastResult);
                            updated = true;
                        }

                        if (conversationId != null && !conversationId.equals(existingContact.getConversationId())) {
                            existingContact.setConversationId(conversationId);
                            updated = true;
                        }

                        if (updated) {
                            contactRepository.save(existingContact);
                            recordsUpdated++;
                            logger.info("Updated existing record (from CSV): Phone: {}, Last Attempt: {}, Last Result: {}, Conversation ID: {}",
                                    existingContact.getPhone(), existingContact.getLastAttempt(), existingContact.getLastResult(), existingContact.getConversationId());
                        }

                    } else {
                        Contact newContact = new Contact(phone, parsedLastAttempt, lastResult, conversationId, orderId);
                        contactRepository.save(newContact);
                        recordsInserted++;
                        logger.info("Inserted new record (from CSV): Phone: {}, Last Attempt: {}, Last Result: {}, Conversation ID: {}, Order ID: {}",
                                newContact.getPhone(), newContact.getLastAttempt(), newContact.getLastResult(), newContact.getConversationId(), newContact.getOrderId());
                    }
                }

                logger.info("Finished processing {} records from the CSV.", recordsProcessed);
                logger.info("Updated {} records in the 'contact_lists' table (from CSV).", recordsUpdated);
                logger.info("Inserted {} new records into the 'contact_lists' table (from CSV).", recordsInserted);
            }

        } catch (IOException e) {
            logger.error("Error reading CSV content: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("An error occurred while processing and saving the CSV: {}", e.getMessage(), e);
        }
    }
// <<<<<<<<<<<<<<< نهاية الميثود المعدلة بالكامل >>>>>>>>>>>>>>>


    // <<<<<<<<<<<<<<< ميثود جديدة: جلب اسم الـ Agent من SCIM API >>>>>>>>>>>>>>>

    public String fetchAgentDisplayName(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null; // No userId, skip API call
        }

        String accessToken = getAccessToken(); // Reuse token if still valid
        if (accessToken == null) {
            logger.error("Failed to obtain Access Token for SCIM Users API.");
            return null;
        }

        String scimUserUrl = String.format("https://api.%s/api/v2/scim/users/%s", region, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return retry(3, 2000, () -> {
                logger.info("🔍 Fetching Agent data for User ID: {}", userId);

                ResponseEntity<ScimUserResponse> response = restTemplate.exchange(
                        scimUserUrl,
                        HttpMethod.GET,
                        requestEntity,
                        ScimUserResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    logger.info("✅ Successfully fetched Agent data for User ID: {}", userId);
                    return response.getBody().getDisplayName();
                } else {
                    throw new RuntimeException("❌ Failed to fetch Agent data. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed permanently to fetch Agent data for User ID: {}: {}", userId, e.getMessage(), e);
            return null;
        }
    }


    // <<<<<<<<<<<<<<< نهاية الميثود الجديدة >>>>>>>>>>>>>>>


    public void updateContactsWithConversationDetails() {
        logger.info("--- Starting to update Contacts with call details from Genesys API ---");

        List<Contact> contactsToUpdate = contactRepository.findByConversationIdIsNotNullAndConversationStartTimeIsNull();
        logger.info("Found {} contact records that require call details.", contactsToUpdate.size());

        int updatedCount = 0;

        for (Contact contact : contactsToUpdate) {
            if (contact.getConversationId() == null || contact.getConversationId().isEmpty()) {
                logger.warn("Skipping contact without Conversation ID: {}", contact.getPhone());
                continue;
            }

            ConversationDetailsResponse details = fetchConversationDetails(contact.getConversationId());
            if (details != null) {
                contact.setConversationStartTime(details.getConversationStart());
                contact.setConversationEndTime(details.getConversationEnd());

                if (contact.getConversationStartTime() != null && contact.getConversationEndTime() != null) {
                    Duration duration = Duration.between(contact.getConversationStartTime(), contact.getConversationEndTime());
                    contact.setCallDurationSeconds(duration.getSeconds());
                } else {
                    contact.setCallDurationSeconds(null);
                }

                String selectedAgentId = null;
                String wrapUpCode = null;

                for (Participant participant : details.getParticipants()) {
                    if ("agent".equalsIgnoreCase(participant.getPurpose()) && participant.getUserId() != null) {
                        selectedAgentId = participant.getUserId();
                    }

                    if (participant.getSessions() != null) {
                        for (Session session : participant.getSessions()) {
                            if (wrapUpCode == null && session.getSegments() != null) {
                                for (Segment segment : session.getSegments()) {
                                    if (segment.getWrapUpCode() != null) {
                                        wrapUpCode = segment.getWrapUpCode();
                                        break;
                                    }
                                }
                            }
                            if (wrapUpCode != null) {
                                break;
                            }
                        }
                    }

                    if (selectedAgentId != null && wrapUpCode != null) {
                        break;
                    }
                }

                contact.setSelectedAgentId(selectedAgentId);
                contact.setWrapUpCode(wrapUpCode);

                if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                    String agentName = fetchAgentDisplayName(selectedAgentId);
                    contact.setAgentName(agentName);
                } else {
                    contact.setAgentName(null);
                }

                contactRepository.save(contact);
                updatedCount++;

                logger.info("✅ Updated Contact: {} | Conversation ID: {} | Start: {} | End: {} | Duration: {}s | Agent ID: {} | Agent Name: {} | WrapUpCode: {}",
                        contact.getPhone(),
                        contact.getConversationId(),
                        contact.getConversationStartTime(),
                        contact.getConversationEndTime(),
                        contact.getCallDurationSeconds(),
                        selectedAgentId,
                        contact.getAgentName(),
                        wrapUpCode
                );
            } else {
                logger.error("❌ Failed to fetch call details for Contact: {} (Conversation ID: {})",
                        contact.getPhone(), contact.getConversationId());
            }
        }

        logger.info("--- Finished updating Contacts with call details. {} records updated. ---", updatedCount);
    }


    private String extractDirectCsvLink(String htmlContent) {
        Pattern pattern = Pattern.compile("href=\"(https?://[^\"]+\\.csv)\"|url='(https?://[^']+\\.csv)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(htmlContent);

        if (matcher.find()) {
            if (matcher.group(1) != null) {
                return matcher.group(1);
            } else if (matcher.group(2) != null) {
                return matcher.group(2);
            }
        }

        Pattern metaRefreshPattern = Pattern.compile("<meta\\s+http-equiv=['\"]refresh['\"]\\s+content=['\"]\\d+;\\s*url=(https?://[^'\"]+\\.csv)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher metaRefreshMatcher = metaRefreshPattern.matcher(htmlContent);
        if (metaRefreshMatcher.find()) {
            return metaRefreshMatcher.group(1);
        }

        logger.warn("No direct CSV link found in the HTML content. " +
                "Will attempt to download from the original URI, but it may still be HTML. " +
                "Content sample: {}", htmlContent.substring(0, Math.min(htmlContent.length(), 500)));

        return null;
    }

    public List<Contact> getContacts() {
        return contactRepository.findAll(); // أو فلترة حسب شرط معين
    }
}