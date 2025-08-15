package com.contacts.sheet.service;

import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.configration.RetryUtils;
import com.contacts.sheet.entity.Contact;
import com.contacts.sheet.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypurecloud.sdk.v2.ApiClient;
import com.mypurecloud.sdk.v2.ApiException;
import com.mypurecloud.sdk.v2.ApiResponse;
import com.mypurecloud.sdk.v2.Configuration;
import com.mypurecloud.sdk.v2.api.OutboundApi;
import com.mypurecloud.sdk.v2.model.DomainEntityRef;
import com.mypurecloud.sdk.v2.model.ExportUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;

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

Contact contact=new Contact();
    private static final int MAX_RETRIES = 3;
    private final RestTemplate restTemplate; // عشان نعمل HTTP requests
    private final ContactRepo contactRepository; // عشان نتعامل مع الداتابيز
    private static final Logger logger = LoggerFactory.getLogger(GenesysService.class);
    // Constructor بيعمل حقن للـ RestTemplate والـ ContactRepository تلقائي
    public GenesysService(RestTemplate restTemplate, ContactRepo contactRepository  ) {
        this.restTemplate = restTemplate;
        this.contactRepository = contactRepository;
    }
    //First iteration for Genesys Cloud
    public void syncContactsFromGenesysApi() {
        logger.info("************Start First Iteration of Genesys Cloud **************");
        logger.info("=== [SYNC START] Initiating synchronization with Genesys Cloud contacts ===");
        String accessToken = null;
        String downloadUri = null;
        String csvContent = null;
        // Step 1: Get Access Token
        try {
            logger.info("[STEP 1] 🔄 Attempting to retrieve OAuth access token for Genesys Cloud... please wait ⏳");
            accessToken = getAccessToken();
            logger.info("[SUCCESS ✅] Access token successfully retrieved.");}
        catch (Exception e) {
            logger.error("[STEP 1 - ERROR] Failed to retrieve access token. Check logs inside getAccessToken() for more details.");
            logger.info("=== [SYNC ABORTED] Cannot proceed without access token ===");
            return;}

        // Step 2: Initiate Export
        try {
            logger.info("[STEP 2 🔄]  Initiating contact export request to Genesys Cloud... please wait ⏳");
            downloadUri = initiateContactExport(accessToken);
            logger.info("[SUCCESS ✅] Contact export initiated. Download URI: {}", downloadUri);
        } catch (Exception e) {
            logger.error("[STEP 2 - ERROR] Failed to initiate contact export. Message: {}", e.getMessage(), e);
            logger.info("=== [SYNC ABORTED] Cannot proceed without download URI ===");
            return;
        }




        // Step 3: Download CSV
        try {
            logger.info("[STEP 3 🔄] Attempting to download exported CSV content... please wait ⏳");
            // Try reading the export data (CSV content)
            csvContent = readExportData(downloadUri, accessToken);
            if (csvContent == null || csvContent.trim().isEmpty() || csvContent.trim().startsWith("<!DOCTYPE html>")) {
                // Handle the case when the CSV content is invalid
                if (csvContent != null && csvContent.trim().startsWith("<!DOCTYPE html>"))
                {logger.error("[STEP 3 - ERROR] Received HTML instead of CSV from URI: {}", downloadUri);}
                else {logger.error("[STEP 3 - ERROR] CSV content is null or empty. Export might have failed.");}
                logger.info("=== [SYNC ABORTED] Cannot proceed without valid CSV content ===");
                return;}
            // Success case: Valid CSV content
            logger.info("[SUCCESS ✅] CSV content successfully downloaded from URI: {}", downloadUri);}
        catch (Exception e) {
            // Handle errors during the CSV download process
            logger.error("[STEP 3 - ERROR] Failed to download CSV content. Message: {}", e.getMessage(), e);
            logger.info("=== [SYNC ABORTED] Cannot proceed without valid CSV ===");}
        // Step 4: Process and Save CSV
        try {
            logger.info("[STEP 4 🔄] Starting CSV sync...  please wait ⏳");
            processAndSaveCsv(csvContent);
            logger.info("[STEP 4 - SUCCESS ✅] Contacts successfully processed and saved from CSV.");
        } catch (Exception e) {
            logger.error("[STEP 4 - ERROR] Failed to process and save contacts from CSV. Message: {}", e.getMessage(), e);
            logger.error("[SYNC FAILED] Contacts not updated.");
        }

        logger.info("=== [SYNC COMPLETE] Genesys Cloud contact synchronization completed successfully ===");
        logger.info("************Finished First Iteration of Genesys Cloud **************");
    }
    // دالة لجلب الـ Access Token من  step 1 Genesys Cloud
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
                    logger.error("🔴 [getAccessToken] HTTP error while requesting token: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
                    throw new RuntimeException("Failed to get access token: " + e.getResponseBodyAsString(), e);
                } catch (Exception e) {
                    logger.error("🔴 [getAccessToken] Unexpected error while requesting token: {}", e.getMessage(), e);
                    throw e;
                }
            });
        } catch (Exception e) {
            logger.error("🚫 [getAccessToken] Final failure after retries: {}", e.getMessage(), e);
            throw new RuntimeException("Access token retrieval failed after retries.", e);
        }
    }
    // step 2 Genesys Cloud sync data using token
    private String initiateContactExport(String token) {
        String contactListId = "6e3088a5-3218-4a9d-8fc0-a9f20348f110";
        String region = "mec1";

        try {
            ApiClient apiClient = ApiClient.Builder.standard()
                    .withAccessToken(token)
                    .withBasePath("https://api." + region + ".pure.cloud")
                    .build();
            Configuration.setDefaultApiClient(apiClient);

            OutboundApi outboundApi = new OutboundApi();

            // Step 1: Trigger a new export with retries
            DomainEntityRef postResponse = RetryUtils.retry(3, 60000, () -> {
                logger.info("📤 Triggering new export for contact list {}", contactListId);
                // The request body must be a new object, not null
                return outboundApi.postOutboundContactlistExport(contactListId, null);
            });

            String exportJobId = postResponse.getId();
            logger.info("✅ Export job initiated. Job ID: {}", exportJobId);

            // Step 2: Poll until ready with retries
            String downloadUri = RetryUtils.retry(60, 5000, () -> {
                logger.info("⏳ Polling export status for Job ID {}...", exportJobId);

                // This is the correct method to poll a specific job ID
                ExportUri exportUri = outboundApi.getOutboundContactlistExport(contactListId, exportJobId);

                if (exportUri != null && exportUri.getUri() != null && !exportUri.getUri().isEmpty()) {
                    logger.info("✅ Export ready: {}", exportUri.getUri());
                    return exportUri.getUri();
                } else {
                    // Throw an exception to trigger a retry if not ready
                    throw new RuntimeException("Export not ready yet.");
                }
            });

            if (downloadUri == null) {
                throw new RuntimeException("❌ Export did not complete in time");
            }

            return downloadUri;

        } catch (Exception e) {
            logger.error("🚫 Unexpected export error: {}", e.getMessage(), e);
            throw new RuntimeException("Contact export ultimately failed", e);
        }
    }    // step 3  download exported CSV content
    private String readExportData(String downloadUri, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            logger.info("Attempting to fetch content from the download link: {}", downloadUri);

            // Fetch initial response from the provided download URI
            ResponseEntity<String> initialResponse = restTemplate.exchange(downloadUri, HttpMethod.GET, requestEntity, String.class);
            String content = initialResponse.getBody();

            if (content != null && content.trim().startsWith("<!DOCTYPE html>")) {
                // Case: HTML content instead of CSV
                logger.error("[STEP 1 - ERROR] HTML content received from URI: {}", downloadUri);
                logger.info("[STEP 2] Attempting to extract the direct CSV link from HTML...");
                String directCsvLink = extractDirectCsvLink(content);

                if (directCsvLink != null) {
                    logger.info("[STEP 3] Direct CSV link found: {}. Attempting to download from this link...", directCsvLink);
                    ResponseEntity<String> csvResponse = restTemplate.exchange(directCsvLink, HttpMethod.GET, requestEntity, String.class);
                    return csvResponse.getBody();
                } else {
                    logger.error("[STEP 2 - ERROR] Unable to extract CSV link from HTML content.");
                    return content;
                }
            } else {
                // Case: CSV content received directly
                return content;
            }
        } catch (HttpClientErrorException e) {
            // Handle HTTP client errors
            logger.error("[ERROR] HTTP error while fetching data from {}: {} - {}", downloadUri, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch data: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            // Handle unexpected errors
            logger.error("[ERROR] Unexpected error while fetching data from {}: {}", downloadUri, e.getMessage());
            throw new RuntimeException("Failed to fetch data.", e);
        }
    }
    //Method used in step 3 to extract uri
    private String extractDirectCsvLink(String htmlContent) {
        Pattern pattern = Pattern.compile("href=\"(https?://[^\"]+\\.csv)\"|url='(https?://[^']+\\.csv)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(htmlContent);
        if (matcher.find()) {
            if (matcher.group(1) != null) {
                return matcher.group(1);
            } else if (matcher.group(2) != null) {
                return matcher.group(2);}}
        Pattern metaRefreshPattern = Pattern.compile("<meta\\s+http-equiv=['\"]refresh['\"]\\s+content=['\"]\\d+;\\s*url=(https?://[^'\"]+\\.csv)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher metaRefreshMatcher = metaRefreshPattern.matcher(htmlContent);
        if (metaRefreshMatcher.find()) {
            return metaRefreshMatcher.group(1);}
        logger.warn("No direct CSV link found in the HTML content. " +
                "Will attempt to download from the original URI, but it may still be HTML. " +
                "Content sample: {}", htmlContent.substring(0, Math.min(htmlContent.length(), 500)));
        return null;}
    // step 4 CSV sync
    private void processAndSaveCsv(String csvContent) {
        int recordsProcessed = 0;
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
                    String contactCallable = csvRecord.get("contactCallable");

                    if (phone == null || phone.trim().isEmpty()) {
                        logger.warn("[CSV ROW SKIPPED] Missing phone number at row {}: {}", recordsProcessed, csvRecord.toMap());
                        continue;
                    }

                    if ("OUTBOUND-CONTACT-INVALID-SKILL-SKIPPED".equalsIgnoreCase(lastResult)
                            || "ININ-WRAP-UP-TIMEOUT".equalsIgnoreCase(lastResult)) {
                        contactCallable = "0";
                    }

                    LocalDateTime parsedLastAttempt = null;
                    if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
                        try {
                            parsedLastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        } catch (DateTimeParseException e) {
                            logger.warn("[CSV PARSE] Invalid date format at row {} for phone {}: '{}'. Setting as null.",
                                    recordsProcessed, phone, lastAttemptStr);
                        }
                    }

                    logger.info("[CSV DATA] phone={}, lastAttempt={}, lastResult={}, conversationId={}, orderId={}",
                            phone, parsedLastAttempt, lastResult, conversationId, orderId);

                    // ✅ البحث على أساس جميع الأعمدة في UNIQUE CONSTRAINT
                    Optional<Contact> existingContactOptional =
                            contactRepository.findByPhoneAndLastAttemptAndLastResultAndConversationIdAndOrderIdAndContactCallable(
                                    phone, parsedLastAttempt, lastResult, conversationId, orderId, contactCallable
                            );

                    if (existingContactOptional.isEmpty()) {
                        Contact newContact = new Contact(phone, parsedLastAttempt, lastResult, conversationId, orderId, contactCallable);
                        try {
                            contactRepository.save(newContact);
                            recordsInserted++;
                            logger.info("[CSV INSERT] Inserted new contact: Phone={}, LastAttempt={}, Result={}, ConversationId={}, OrderId={}",
                                    newContact.getPhone(), newContact.getLastAttempt(), newContact.getLastResult(),
                                    newContact.getConversationId(), newContact.getOrderId());
                        } catch (DataIntegrityViolationException ex) {
                            logger.warn("[CSV SKIP] Detected duplicate at DB level, skipping insert. Phone={}, OrderId={}", phone, orderId);
                        }
                    } else {
                        logger.info("[CSV SKIP] Duplicate contact exists, skipping insert. Phone={}, OrderId={}", phone, orderId);
                    }
                }
            }

            logger.info("[CSV SYNC COMPLETED] Processed: {}, Inserted: {}", recordsProcessed, recordsInserted);
        } catch (IOException e) {
            logger.error("[CSV ERROR] Failed to read CSV content: {}", e.getMessage(), e);
            throw new RuntimeException("CSV read failed", e);
        } catch (Exception e) {
            logger.error("[CSV ERROR] Unexpected error during CSV processing: {}", e.getMessage(), e);
            throw e;
        }
    }

//******************************* End of First iteration ********************************************************//

    //Second iteration for Genesys Cloud
    public void updateContactsWithConversationDetails() {
        logger.info("************ Start Second Iteration of Genesys Cloud **************");
        logger.info("=== [SYNC START] Updating contacts with call details from Genesys Cloud ===");
        List<Contact> contactsToUpdate = null;
        // STEP 1: Fetch contacts that need call detail updates
        logger.info("[STEP 1] Fetching contacts with valid conversationId and missing conversationStartTime... Please wait...");
        try {
            contactsToUpdate = contactRepository.findWithValidConversationIdAndMissingStartTime();
            logger.info("[SUCCESS ✅] Found {} contact records that require call details update.", contactsToUpdate.size());
        } catch (Exception e) {
            logger.error("❌ Failed to fetch contacts from repository. Error: {}", e.getMessage(), e);
            return;}
        int updatedCount = 0;
        int failedCount = 0;
        for (Contact contact : contactsToUpdate) {
            String phone = contact.getPhone();
            String conversationId = contact.getConversationId();
            // STEP 2: Validate conversationId
            logger.info("[STEP 2] Validating conversation ID for phone: {}... Please wait...", phone);
            try {
                if (conversationId == null || conversationId.isEmpty()) {
                    logger.warn("⚠️ Skipping contact without Conversation ID | Phone: {}", phone);
                    continue;
                }
            } catch (Exception e) {
                logger.error("❌ Error during Conversation ID validation | Phone: {} | Error: {}", phone, e.getMessage(), e);
                failedCount++;
                continue;}

            ConversationDetailsResponse details = null;

            // STEP 3: Fetch conversation details from Genesys
            logger.info("[STEP 3] Fetching conversation details for conversationId: {}... Please wait...", conversationId);
            try {
                details = fetchConversationDetails(conversationId);
                if (details == null) {
                    logger.error("❌ Call details not found | Phone: {} | Conversation ID: {}", phone, conversationId);
                    failedCount++;
                    continue;
                }
            } catch (Exception e) {
                logger.error("❌ Exception while fetching call details | Phone: {} | Conversation ID: {} | Error: {}", phone, conversationId, e.getMessage(), e);
                failedCount++;
                continue;}

            // STEP 4: Set call times
            logger.info("[STEP 4] Setting conversation start/end time... Please wait...");
            try {
                contact.setConversationStartTime(details.getConversationStart());
                contact.setConversationEndTime(details.getConversationEnd());
            } catch (Exception e) {
                logger.error("❌ Error setting call times | Phone: {} | Error: {}", phone, e.getMessage(), e);
            }

            // ---
            // التعديل يبدأ هنا
            // ---

            // STEP 5: Extract agent ID, wrap-up code, and call duration
            logger.info("[STEP 5] Extracting agent ID, wrap-up code, and call duration from conversation details... Please wait...");
            String selectedAgentId = null;
            String wrapUpCode = null;
            Long tTalk = 0L;
            Long acw = 0L;
            Long hold = 0L;

            try {


                // First pass: find agent ID and extract metrics
                for (Participant participant : details.getParticipants()) {
                    if ("agent".equalsIgnoreCase(participant.getPurpose()) && participant.getUserId() != null) {
                        selectedAgentId = participant.getUserId();
                        if (participant.getSessions() != null) {
                            for (Session session : participant.getSessions()) {
                                if (session.getMetrics() != null) {
                                    for (Metric metric : session.getMetrics()) {
                                        if ("tTalk".equalsIgnoreCase(metric.getName())) {
                                            tTalk = metric.getValue() / 1000;
                                        }
                                        if ("tAcw".equalsIgnoreCase(metric.getName())) {
                                            acw = metric.getValue() / 1000;
                                        }
                                        if ("tHeld".equalsIgnoreCase(metric.getName())) {
                                            hold = metric.getValue() / 1000;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Second pass: find wrap-up code (can be on non-agent)
                for (Participant participant : details.getParticipants()) {
                    if (participant.getSessions() != null) {
                        for (Session session : participant.getSessions()) {
                            if (session.getSegments() != null) {
                                for (Segment segment : session.getSegments()) {
                                    if (segment.getWrapUpCode() != null) {
                                        wrapUpCode = segment.getWrapUpCode();
                                        break;
                                    }
                                }
                            }
                            if (wrapUpCode != null) break;
                        }
                    }
                    if (wrapUpCode != null) break;
                }
                // Save results
                contact.setSelectedAgentId(selectedAgentId);
                contact.setWrapUpCode(wrapUpCode);
                contact.setAfterCallWorkSeconds(acw);
                contact.setHoldTimeSeconds(hold);
                contact.setTalkTimeSeconds(tTalk);
                contact.setCallDurationSeconds(tTalk +  hold);
            } catch (Exception e) {
                logger.error("❌ Error extracting agent, wrap-up code, or duration | Phone: {} | Error: {}", phone, e.getMessage(), e);
            }
            logger.info("[STEP 6] Extracting callback scheduled time if last result is 'Call Back'...");
            try {
                if ("Call Back".equalsIgnoreCase(contact.getLastResult())) {
                    for (Participant participant : details.getParticipants()) {
                        if (participant.getSessions() != null) {
                            for (Session session : participant.getSessions()) {
                                if ("callback".equalsIgnoreCase(session.getMediaType())) {
                                    contact.setCallbackScheduledTime(session.getCallbackScheduledTime());
                                    logger.info("✅ Found and set callbackScheduledTime: {} for conversationId: {}", contact.getCallbackScheduledTime(), conversationId);
                                    break;
                                }
                            }
                        }
                        if (contact.getCallbackScheduledTime() != null) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("❌ Error extracting callbackScheduledTime | Phone: {} | Error: {}", phone, e.getMessage(), e);
            }

            // STEP 7: Fetch and set agent name
            logger.info("[STEP 7] Fetching and setting agent name for Agent ID: {}... Please wait...", selectedAgentId);
            try {
                if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                    try {
                        String agentName = fetchAgentDisplayName(selectedAgentId);
                        String agentEmail = fetchAgentEmail(selectedAgentId);
                        contact.setAgentEmail(agentEmail);
                        contact.setAgentName(agentName);
                    } catch (Exception e) {
                        logger.warn("⚠️ Failed to fetch agent name | Agent ID: {} | Phone: {} | Error: {}", selectedAgentId, phone, e.getMessage());
                        contact.setAgentName(null);
                        contact.setAgentEmail(null);
                    }
                } else {
                    contact.setAgentName(null);
                    contact.setAgentEmail(null);
                }
            } catch (Exception e) {
                logger.error("❌ Error while setting agent name | Phone: {} | Error: {}", phone, e.getMessage(), e);
            }

            // STEP 8: Save updated contact
            logger.info("[STEP 8] Saving updated contact data for phone: {}... Please wait...", phone);
            try {
                contactRepository.save(contact);
                updatedCount++;
                logger.info("✅ Contact updated successfully:");
                logger.info("   • Phone: {}", phone);
                logger.info("   • Conversation ID: {}", conversationId);
                logger.info("   • Start Time: {}", contact.getConversationStartTime());
                logger.info("   • End Time: {}", contact.getConversationEndTime());
                logger.info("   • Duration (s): {}", contact.getCallDurationSeconds());
                logger.info("   • Agent ID: {}", selectedAgentId);
                logger.info("   • Agent Name: {}", contact.getAgentName());
                logger.info("   • Agent Email: {}", contact.getAgentEmail());
                logger.info("   • Wrap-Up Code: {}", wrapUpCode);
                logger.info("   • Callback Scheduled Time: {}", contact.getCallbackScheduledTime());
            } catch (Exception e) {
                logger.error("❌ Failed to save contact | Phone: {} | Error: {}", phone, e.getMessage(), e);
                failedCount++;
            }
        }

        // STEP 9: Log final sync summary
        try {
            logger.info("=== [SYNC COMPLETE] ===");
            logger.info("✅ Total Contacts Updated: {}", updatedCount);
            logger.info("❌ Total Contacts Failed: {}", failedCount);
        } catch (Exception e) {
            logger.error("❌ Error while logging final summary | Error: {}", e.getMessage(), e);
        }
    }

    public ConversationDetailsResponse fetchConversationDetails(String conversationId) {
        String accessToken = getAccessToken();
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
            ConversationDetailsResponse detailsResponse = retry(3, 2000, () -> {
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

            // ⭐ الشرط الجديد هنا


            return detailsResponse;

        } catch (Exception e) {
            logger.error("🚫 Failed permanently to fetch call details for ID: " + conversationId + ": " + e.getMessage());
            return null;
        }
    }    // <<<<<<<<<<<<<<< ميثود جديدة: جلب اسم الـ Agent من SCIM API >>>>>>>>>>>>>>>

















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
    public String fetchAgentEmail(String userId) {
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
                logger.info("🔍 Fetching Agent Email for User ID: {}", userId);

                ResponseEntity<ScimUserResponse> response = restTemplate.exchange(
                        scimUserUrl,
                        HttpMethod.GET,
                        requestEntity,
                        ScimUserResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    logger.info("✅ Successfully fetched Agent Email for User ID: {}", userId);
                    return response.getBody().getEmails().get(0).getValue();
                } else {
                    throw new RuntimeException("❌ Failed to fetch Agent Email. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            logger.error("🚫 Failed permanently to fetch Agent data for User ID: {}: {}", userId, e.getMessage(), e);
            return null;
        }
    }


    public List<Contact> getContacts() {
        return contactRepository.findAll(); // أو فلترة حسب شرط معين
    }





}