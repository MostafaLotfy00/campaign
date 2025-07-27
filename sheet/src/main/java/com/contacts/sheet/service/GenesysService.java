package com.contacts.sheet.service;

import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.entity.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
            String tokenResponse = restTemplate.postForObject(authUrl, requestEntity, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(tokenResponse);
            return root.path("access_token").asText();

        } catch (HttpClientErrorException e) {
            System.err.println("Error getting access token: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw new RuntimeException("Failed to get access token: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            System.err.println("Unexpected error getting access token: " + e.getMessage());
            throw new RuntimeException("Failed to get access token.", e);
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
            System.out.println("جاري محاولة جلب المحتوى من رابط التحميل: " + downloadUri);
            ResponseEntity<String> initialResponse = restTemplate.exchange(downloadUri, HttpMethod.GET, requestEntity, String.class);
            String content = initialResponse.getBody();

            if (content != null && content.trim().startsWith("<!DOCTYPE html>")) {
                System.out.println("تم استلام محتوى HTML، جاري محاولة استخراج رابط الـ CSV المباشر...");
                String directCsvLink = extractDirectCsvLink(content);

                if (directCsvLink != null) {
                    System.out.println("تم استخراج رابط CSV مباشر: " + directCsvLink + ". جاري محاولة التحميل من هذا الرابط.");
                    ResponseEntity<String> csvResponse = restTemplate.exchange(directCsvLink, HttpMethod.GET, requestEntity, String.class);
                    return csvResponse.getBody();
                } else {
                    System.err.println("لم نتمكن من استخراج رابط CSV مباشر من الـ HTML. المحتوى مازال HTML.");
                    return content;
                }
            } else {
                System.out.println("تم استلام محتوى يبدو أنه CSV مباشرة من الـ URI الأصلي.");
                return content;
            }

        } catch (HttpClientErrorException e) {
            System.err.println("خطأ في جلب البيانات من " + downloadUri + ": " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            throw new RuntimeException("فشل في جلب البيانات: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            System.err.println("خطأ غير متوقع في جلب البيانات من " + downloadUri + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("فشل في جلب البيانات.", e);
        }
    }

    // دالة رئيسية لعملية المزامنة (هتشتغل عن طريق الـ Scheduler)
    public void syncContactsFromGenesysApi() { // اسم الميثود اللي هتشتغل من الـ Scheduler
        System.out.println("--- بدء مزامنة بيانات Genesys Cloud ---");
        String accessToken = null;
        try {
            accessToken = getAccessToken();
            System.out.println("تم الحصول على Access Token بنجاح.");

            String downloadUri = initiateContactExport(accessToken);
            System.out.println("تم بدء Export الـ Contacts. رابط التحميل: " + downloadUri);

            System.out.println("جاري الانتظار لمدة 10 ثواني حتى يصبح ملف الـ Export جاهزاً...");
            Thread.sleep(10000); // ممكن تزيد الوقت لو لسه المشكلة موجودة

            String csvContent = readExportData(downloadUri, accessToken);
            if (csvContent == null || csvContent.trim().isEmpty() || csvContent.trim().startsWith("<!DOCTYPE html>")) {
                if (csvContent != null && csvContent.trim().startsWith("<!DOCTYPE html>")) {
                    System.err.println("فشل في الحصول على محتوى CSV. تم استلام HTML حتى بعد الانتظار ومحاولة الاستخراج. يرجى مراجعة سلوك Genesys API أو زيادة مدة الانتظار.");
                } else {
                    System.err.println("لم يتم استرجاع أي محتوى CSV أو المحتوى فارغ، لا يمكن المتابعة لتخزين البيانات.");
                }
                return;
            }

            System.out.println("تم استرجاع محتوى CSV بنجاح. جاري المعالجة...");
            processAndSaveCsv(csvContent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("تم مقاطعة الـ Thread أثناء الانتظار: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("فشلت مزامنة بيانات Genesys Cloud: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("--- انتهاء مزامنة بيانات Genesys Cloud ---");
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
                // جلب كل الـ Contacts الموجودة في الداتابيز مرة واحدة
                // واستخدام الـ phone كـ key لسهولة البحث عن طريق Stream API
                Map<String, Contact> existingContactsMap = contactRepository.findAll().stream()
                        .collect(Collectors.toMap(Contact::getPhone, contact -> contact,
                                (existing, replacement) -> existing)); // في حالة وجود نفس الـ phone أكثر من مرة، احتفظ بالسجل الموجود (existing)

                for (CSVRecord csvRecord : csvParser) {
                    recordsProcessed++;

                    String phone = csvRecord.get("phone1"); // تأكد من اسم العمود في الـ CSV
                    String lastAttemptStr = csvRecord.get("CallRecordLastAttempt-phone1"); // تأكد من اسم العمود
                    String lastResult = csvRecord.get("CallRecordLastResult-phone1"); // تأكد من اسم العمود
                    // <<<<<<<<<<<<<<< جديد: استخراج conversationId من الـ CSV >>>>>>>>>>>>>
                    // تأكد إن اسم العمود ده موجود في ملف الـ CSV اللي Genesys بتطلعه
                    // لو مش موجود، لازم تعرف إزاي تجيبه من الـ CSV أو لو مبيجيش من الـ CSV أصلاً
                    String conversationId = csvRecord.get("conversationId"); // <<<<<<<<<<<<<<< تأكد من اسم العمود في الـ CSV

                    if (phone == null || phone.trim().isEmpty()) {
                        System.err.println("تخطي صف بسبب نقص رقم الهاتف: " + csvRecord.toMap());
                        continue;
                    }

                    LocalDateTime parsedLastAttempt = null;
                    if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
                        try {
                            parsedLastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                            //System.out.println("Processing: Phone=" + phone + ", Original lastAttemptStr='" + lastAttemptStr + "', Parsed lastAttempt=" + parsedLastAttempt); // ممكن تعملها comment بعد ما تتأكد إنها شغالة
                        } catch (DateTimeParseException e) {
                            System.err.println("تحذير: فشل في تحليل تاريخ آخر محاولة: '" + lastAttemptStr + "' للهاتف: " + phone + ". سيتم تخزينه كـ null.");
                            parsedLastAttempt = null;
                        }
                    } else {
                        //System.out.println("تنبيه: لا توجد قيمة لـ lastAttempt للهاتف: " + phone + ". سيتم تخزينه كـ null."); // ممكن تعملها comment بعد ما تتأكد إنها شغالة
                    }

                    // <<<<<<<<<<<<<<< تحديث لوجيك البحث والحفظ >>>>>>>>>>>>>
                    Contact existingContact = existingContactsMap.get(phone); // البحث في الـ Map اللي عملناه

                    if (existingContact != null) {
                        // لو لقينا Contact بنفس رقم التليفون
                        boolean updated = false;

                        // تحديث lastAttempt لو الـ CSV عنده قيمة أحدث
                        if (parsedLastAttempt != null &&
                                (existingContact.getLastAttempt() == null || parsedLastAttempt.isAfter(existingContact.getLastAttempt()))) {
                            existingContact.setLastAttempt(parsedLastAttempt);
                            updated = true;
                        }

                        // تحديث lastResult لو مختلف
                        if (lastResult != null && !lastResult.equals(existingContact.getLastResult())) {
                            existingContact.setLastResult(lastResult);
                            updated = true;
                        }

                        // تحديث conversationId فقط لو مختلف وموجود في الـ CSV
                        if (conversationId != null && !conversationId.equals(existingContact.getConversationId())) {
                            existingContact.setConversationId(conversationId);
                            updated = true;
                        }

                        if (updated) {
                            contactRepository.save(existingContact);
                            recordsUpdated++;
                            System.out.println("تم تحديث سجل موجود بنجاح: Phone: " + existingContact.getPhone() +
                                    ", Last Attempt: " + existingContact.getLastAttempt() +
                                    ", Last Result: " + existingContact.getLastResult() +
                                    ", Conversation ID: " + existingContact.getConversationId());
                        } else {
                            //System.out.println("تخطي سجل موجود (لا توجد تحديثات): Phone: " + phone); // ممكن تعملها comment بعد ما تتأكد إنها شغالة
                        }

                    } else {
                        // لو رقم التليفون مش موجود خالص في الداتابيز، هنضيف سجل جديد
                        Contact newContact = new Contact(phone, parsedLastAttempt, lastResult, conversationId); // <<<<< استخدام الـ Constructor الجديد
                        contactRepository.save(newContact);
                        recordsInserted++;
                        System.out.println("تم إدخال سجل جديد بنجاح: Phone: " + newContact.getPhone() +
                                ", Last Attempt: " + newContact.getLastAttempt() +
                                ", Last Result: " + newContact.getLastResult() +
                                ", Conversation ID: " + newContact.getConversationId());
                    }
                }
                System.out.println("تم الانتهاء من معالجة " + recordsProcessed + " سجل من الـ CSV.");
                System.out.println("تم تحديث " + recordsUpdated + " سجل في جدول 'contact_lists'.");
                System.out.println("تم إدخال " + recordsInserted + " سجل جديد في جدول 'contact_lists'.");

            }

        } catch (IOException e) {
            System.err.println("خطأ في قراءة محتوى CSV: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("حدث خطأ أثناء معالجة وحفظ CSV: " + e.getMessage());
            e.printStackTrace();
        }
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

        System.err.println("تحذير: لم يتم العثور على رابط CSV مباشر في محتوى الـ HTML. " +
                "سنحاول التحميل من الـ URI الأصلي، لكن قد يكون مازال HTML. " +
                "عينة من المحتوى: " + htmlContent.substring(0, Math.min(htmlContent.length(), 500)));
        return null;
    }
}