package com.contacts.sheet.service;

import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.entity.Contact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                    System.err.println("Error getting access token: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
                    throw new RuntimeException("Failed to get access token: " + e.getResponseBodyAsString(), e);
                }
            });
        } catch (Exception e) {
            System.err.println("🚫 Failed to retrieve access token after retries: " + e.getMessage());
            throw new RuntimeException("Access token retrieval failed after retries.", e);
        }
    }



    public ConversationDetailsResponse fetchConversationDetails(String conversationId) {
        String accessToken = getAccessToken(); // ممكن تمرر الـ token لو مش عاوز تجيب واحد جديد كل مرة
        if (accessToken == null) {
            System.err.println("Failed to obtain Access Token for Conversation Details API.");
            return null;
        }

        String detailsUrl = String.format("https://api.%s/api/v2/analytics/conversations/%s/details", region, conversationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return retry(3, 2000, () -> {
                System.out.println("🔍 Fetching call details for Conversation ID: " + conversationId);

                ResponseEntity<ConversationDetailsResponse> response = restTemplate.exchange(
                        detailsUrl,
                        HttpMethod.GET,
                        requestEntity,
                        ConversationDetailsResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    System.out.println("✅ Successfully fetched call details for ID: " + conversationId);
                    return response.getBody();
                } else {
                    throw new RuntimeException("❌ Failed to fetch call details. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            System.err.println("🚫 Failed permanently to fetch call details for ID: " + conversationId + ": " + e.getMessage());
            return null;
        }
    }


    // دالة لبدء عملية الـ Export لـ Contact List معينة
    private String initiateContactExport(String token) {
        String exportUrl = String.format("https://api.%s/api/v2/outbound/contactlists/%s/export", region, "bdba8620-ccff-413b-a0ea-4c609601c4e7"); // تم استخدام contactListId من الـ properties
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

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
            System.err.println("Error initiating contact export: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw new RuntimeException("Failed to initiate export: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            System.err.println("Unexpected error initiating contact export: " + e.getMessage());
            throw new RuntimeException("Failed to initiate export.", e);
        }
    }

    // دالة لقراءة بيانات الـ CSV من الـ Download URI
    private String readExportData(String downloadUri, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            System.out.println("Attempting to fetch content from the download link: " + downloadUri);
            ResponseEntity<String> initialResponse = restTemplate.exchange(downloadUri, HttpMethod.GET, requestEntity, String.class);
            String content = initialResponse.getBody();

            if (content != null && content.trim().startsWith("<!DOCTYPE html>")) {
                System.out.println("HTML content received, attempting to extract the direct CSV link...");
                String directCsvLink = extractDirectCsvLink(content);

                if (directCsvLink != null) {
                    System.out.println("Direct CSV link extracted: " + directCsvLink + ". Attempting to download from this link...");
                    ResponseEntity<String> csvResponse = restTemplate.exchange(directCsvLink, HttpMethod.GET, requestEntity, String.class);
                    return csvResponse.getBody();
                } else {
                    System.err.println("Failed to extract a direct CSV link from the HTML. The content is still HTML.");
                    return content;
                }
            } else {
                System.out.println("Received content that appears to be CSV directly from the original URI.");
                return content;
            }

        } catch (HttpClientErrorException e) {
            System.err.println("Error fetching data from " + downloadUri + ": " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch data: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            System.err.println("Unexpected error while fetching data from " + downloadUri + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to fetch data.", e);
        }
    }

    // دالة رئيسية لعملية المزامنة (هتشتغل عن طريق الـ Scheduler)
    public void syncContactsFromGenesysApi() { // اسم الميثود اللي هتشتغل من الـ Scheduler
        System.out.println("--- Starting Genesys Cloud data synchronization ---");

        String accessToken = null;
        try {
            accessToken = getAccessToken();
            System.out.println("Access Token retrieved successfully.");


            String downloadUri = initiateContactExport(accessToken);
            System.out.println("Contacts export started successfully. Download link: " + downloadUri);


            System.out.println("Waiting for 10 seconds for the export file to be ready...");

            Thread.sleep(10000); // ممكن تزيد الوقت لو لسه المشكلة موجودة

            String csvContent = readExportData(downloadUri, accessToken);
            if (csvContent == null || csvContent.trim().isEmpty() || csvContent.trim().startsWith("<!DOCTYPE html>")) {
                if (csvContent != null && csvContent.trim().startsWith("<!DOCTYPE html>")) {
                    System.err.println("Failed to retrieve CSV content. Received HTML even after waiting and retrying. Please review Genesys API behavior or consider increasing the wait time.");
                } else {
                    System.err.println("No CSV content was retrieved or the content is empty; cannot proceed with data storage.");
                }
                return;
            }

            System.out.println("CSV content retrieved successfully. Processing...");

            processAndSaveCsv(csvContent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Thread was interrupted during wait: " + e.getMessage());

        } catch (Exception e) {
            System.err.println("Failed to synchronize Genesys Cloud data: " + e.getMessage());

            e.printStackTrace();
        }
        System.out.println("--- Finished synchronizing Genesys Cloud data ---");

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

                // ملاحظة: تم حذف الـ Map `existingContactsMap` لأنه لم يعد مناسبًا
                // للبحث عن Unique Key مركب (phone, lastAttempt).
                // سنقوم بالبحث في الداتابيز مباشرةً لكل سجل.
                for (CSVRecord csvRecord : csvParser) {
                    recordsProcessed++;
                    String phone = csvRecord.get("phone1");
                    String lastAttemptStr = csvRecord.get("CallRecordLastAttempt-phone1");
                    String lastResult = csvRecord.get("CallRecordLastResult-phone1");
                    String conversationId = csvRecord.get("conversationId");
                    String orderId = csvRecord.get("orderId"); // <<<<< تأكد من اسم العمود "orderId" في ملف الـ CSV
                    if (phone == null || phone.trim().isEmpty()) {
                        System.err.println("Skipping row due to missing phone number: " + csvRecord.toMap());
                        continue;
                    }

                    LocalDateTime parsedLastAttempt = null;
                    if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
                        try {
                            parsedLastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        } catch (DateTimeParseException e) {
                            System.err.println("Warning: Failed to parse lastAttempt string: '" + lastAttemptStr + "' for phone: " + phone + ". It will be stored as null.");
                            parsedLastAttempt = null;
                        }
                    }

                    // <<<<<<<<<<<<<<< هنا التعديل الأساسي في منطق البحث والإضافة/التحديث >>>>>>>>>>>>>>>
                    // البحث عن سجل موجود بالـ phone والـ lastAttempt معًا
                    Optional<Contact> existingContactOptional = contactRepository.findByPhoneAndLastAttempt(phone, parsedLastAttempt);

                    if (existingContactOptional.isPresent()) {
                        // لو السجل موجود (بنفس الـ phone والـ lastAttempt)، يبقى تحديث الحقول الأخرى
                        Contact existingContact = existingContactOptional.get();
                        boolean updated = false;

                        // تحديث lastResult لو مختلف
                        if (lastResult != null && !lastResult.equals(existingContact.getLastResult())) {
                            existingContact.setLastResult(lastResult);
                            updated = true;
                        }

                        // تحديث conversationId لو مختلف
                        if (conversationId != null && !conversationId.equals(existingContact.getConversationId())) {
                            existingContact.setConversationId(conversationId);
                            updated = true;
                        }

                        // باقي الحقول (conversationStartTime, conversationEndTime, agentName, wrapUpCode, callDurationSeconds)
                        // سيتم ملؤها بواسطة ميثود updateContactsWithConversationDetails() لاحقاً.

                        if (updated) {
                            contactRepository.save(existingContact);
                            recordsUpdated++;
                            System.out.println("Successfully updated existing record (from CSV): Phone: " + existingContact.getPhone() + ", Last Attempt: " + existingContact.getLastAttempt() + ", Last Result: " + existingContact.getLastResult() + ", Conversation ID: " + existingContact.getConversationId());
                        } else {
                            //System.out.println("لا توجد تحديثات لسجل موجود: Phone: " + phone);
                            // ممكن تعملها comment بعد ما تتأكد إنها شغالة
                        }

                    } else {
                        // لو السجل مش موجود (سواء الـ phone أو الـ lastAttempt مختلف)، هنضيف سجل جديد
                        Contact newContact = new Contact(phone, parsedLastAttempt, lastResult, conversationId, orderId);
                        contactRepository.save(newContact);
                        recordsInserted++;
                        System.out.println("Successfully inserted new record (from CSV): Phone: " + newContact.getPhone() + ", Last Attempt: " + newContact.getLastAttempt() + ", Last Result: " + newContact.getLastResult() + ", Conversation ID: " + newContact.getConversationId() + ", Order ID: " + newContact.getOrderId());
                    }

                    // <<<<<<<<<<<<<<< نهاية التعديل الأساسي >>>>>>>>>>>>>>>
                }

                System.out.println("Finished processing " + recordsProcessed + " records from the CSV.");
                System.out.println("Updated " + recordsUpdated + " records in the 'contact_lists' table (from CSV).");
                System.out.println("Inserted " + recordsInserted + " new records into the 'contact_lists' table (from CSV).");

            }
        } catch (IOException e) {
            System.err.println("Error reading CSV content: " + e.getMessage());

            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An error occurred while processing and saving the CSV: " + e.getMessage());

            e.printStackTrace();
        }
    }
// <<<<<<<<<<<<<<< نهاية الميثود المعدلة بالكامل >>>>>>>>>>>>>>>


    // <<<<<<<<<<<<<<< ميثود جديدة: جلب اسم الـ Agent من SCIM API >>>>>>>>>>>>>>>
    public String fetchAgentDisplayName(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null; // لو مفيش userId، مش هنعمل call للـ API
        }

        String accessToken = getAccessToken(); // ممكن تعيد استخدام الـ token لو لسه صالح
        if (accessToken == null) {
            System.err.println("Failed to obtain Access Token for SCIM Users API.");
            return null;
        }

        String scimUserUrl = String.format("https://api.%s/api/v2/scim/users/%s", region, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            return retry(3, 2000, () -> {
                System.out.println("🔍 Fetching Agent data for User ID: " + userId);

                ResponseEntity<ScimUserResponse> response = restTemplate.exchange(
                        scimUserUrl,
                        HttpMethod.GET,
                        requestEntity,
                        ScimUserResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    System.out.println("✅ Successfully fetched Agent data for User ID: " + userId);
                    return response.getBody().getDisplayName();
                } else {
                    throw new RuntimeException("❌ Failed to fetch Agent data. Status: " + response.getStatusCode());
                }
            });
        } catch (Exception e) {
            System.err.println("🚫 Failed permanently to fetch Agent data for User ID: " + userId + ": " + e.getMessage());
            return null;
        }
    }

    // <<<<<<<<<<<<<<< نهاية الميثود الجديدة >>>>>>>>>>>>>>>


    public void updateContactsWithConversationDetails() {
        System.out.println("--- Starting to update Contacts with call details from Genesys API ---");
        // جلب الـ Contacts اللي ليها conversationId بس لسه مفيش ليها conversationStartTime
        List<Contact> contactsToUpdate = contactRepository.findByConversationIdIsNotNullAndConversationStartTimeIsNull();
        System.out.println("Found " + contactsToUpdate.size() + " contact records that require call details.");
        int updatedCount = 0;
        for (Contact contact : contactsToUpdate) {
            if (contact.getConversationId() == null || contact.getConversationId().isEmpty()) {
                System.out.println("Skipping contact without Conversation ID: " + contact.getPhone());
                continue;
            }
            ConversationDetailsResponse details = fetchConversationDetails(contact.getConversationId());
            if (details != null) {
                // تحديث حقول الوقت الجديدة من الـ Conversation Details API
                contact.setConversationStartTime(details.getConversationStart());
                contact.setConversationEndTime(details.getConversationEnd());

                // حساب مدة المكالمة بالثواني بناءً على الحقلين الجديدين
                if (contact.getConversationStartTime() != null && contact.getConversationEndTime() != null) {
                    Duration duration = Duration.between(contact.getConversationStartTime(), contact.getConversationEndTime());
                    contact.setCallDurationSeconds(duration.getSeconds());
                } else {
                    contact.setCallDurationSeconds(null);
                }

                String selectedAgentId = null; // ده اللي هيتخزن فيه الـ userId بتاع الـ agent participant
                String wrapUpCode = null;

                // <<<<<<<<<<<<<<< هنا التعديل الأساسي: البحث عن الـ Agent ID و الـ WrapUpCode >>>>>>>>>>>>>>>
                for (Participant participant : details.getParticipants()) {
                    // أولاً: البحث عن الـ userId للـ participant اللي الـ purpose بتاعه "agent"
                    if ("agent".equalsIgnoreCase(participant.getPurpose()) && participant.getUserId() != null) {
                        selectedAgentId = participant.getUserId();
                        // بما إن الـ userId ده هو اللي هنستخدمه كـ selectedAgentId، هنحطه هنا.
                        // لو عايز تضمن إنه اول agent هتلاقيه، ممكن تحط break هنا،
                        // بس في معظم الحالات بيكون فيه agent واحد له purpose "agent".
                        // break; // ممكن تضيفها هنا لو عايز تاخد أول agent ID وتوقف البحث
                    }

                    // ثانيًا: البحث عن الـ WrapUpCode (بيكون في session داخل participant)
                    if (participant.getSessions() != null) {
                        for (Session session : participant.getSessions()) {
                            if (wrapUpCode == null && session.getSegments() != null) {
                                for (Segment segment : session.getSegments()) {
                                    if (segment.getWrapUpCode() != null) {
                                        wrapUpCode = segment.getWrapUpCode();
                                        break; // كسر الـ loop ده بمجرد العثور على wrapUpCode
                                    }
                                }
                            }
                            if (wrapUpCode != null) { // لو لقينا الـ wrapUpCode نوقف البحث في الـ sessions بتاعة الـ participant ده
                                break;
                            }
                        }
                    }

                    // لو لقينا الـ selectedAgentId والـ wrapUpCode، نوقف البحث في الـ participants
                    if (selectedAgentId != null && wrapUpCode != null) {
                        break;
                    }
                }
                // <<<<<<<<<<<<<<< نهاية التعديل >>>>>>>>>>>>>>>

                // تخزين الـ selectedAgentId (اللي دلوقتي بقى الـ userId بتاع الـ agent participant) والـ wrapUpCode
                contact.setSelectedAgentId(selectedAgentId);
                contact.setWrapUpCode(wrapUpCode);

                // استدعاء لجلب اسم الـ Agent باستخدام الـ userId اللي لقيناه
                if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                    String agentName = fetchAgentDisplayName(selectedAgentId); // استدعاء الميثود الجديدة
                    contact.setAgentName(agentName);
                } else {
                    contact.setAgentName(null); // لو مفيش Agent ID، يبقى الاسم null
                }

                contactRepository.save(contact);
                updatedCount++;

                System.out.println("✅ Call details updated for Contact: " + contact.getPhone()
                        + " (Conversation ID: " + contact.getConversationId() + ")"
                        + " Conversation Start: " + contact.getConversationStartTime()
                        + ", Conversation End: " + contact.getConversationEndTime()
                        + ", Duration: " + contact.getCallDurationSeconds() + " seconds."
                        + ", Agent ID (User ID): " + selectedAgentId
                        + ", Agent Name: " + contact.getAgentName()
                        + ", WrapUpCode: " + wrapUpCode);
            } else {
                System.err.println("❌ Failed to fetch call details for Contact: " + contact.getPhone()
                        + " (ID: " + contact.getConversationId() + ")");

            }
        }
        System.out.println("--- Finished updating Contacts with call details. " + updatedCount + " records updated. ---");
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

        System.err.println("Warning: No direct CSV link found in the HTML content. " +
                "Will attempt to download from the original URI, but it may still be HTML. " +
                "Content sample: " + htmlContent.substring(0, Math.min(htmlContent.length(), 500)));

        return null;
    }
    public List<Contact> getContacts() {
        return contactRepository.findAll(); // أو فلترة حسب شرط معين
    }
}