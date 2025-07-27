package com.contacts.sheet.controller;

import com.contacts.sheet.entity.Contact;
import com.contacts.sheet.service.ReportService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/contacts-csv")
    public ResponseEntity<ByteArrayResource> generateContactsCsvReport(
            @RequestParam(name = "date", required = false) String dateStr) { // التاريخ اختياري

        LocalDate reportDate;
        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                // بنحاول نحلل التاريخ لو تم إرساله
                reportDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                // لو التاريخ مش صحيح، بنرجع Bad Request
                return ResponseEntity.badRequest()
                        .body(new ByteArrayResource("Invalid date format. Use YYYY-MM-DD.".getBytes(StandardCharsets.UTF_8)));
            }
        } else {
            // لو مفيش تاريخ، بنستخدم تاريخ اليوم الحالي
            reportDate = LocalDate.now();
            System.out.println("لم يتم تحديد تاريخ، جاري إنشاء تقرير لتاريخ اليوم: " + reportDate);
        }

        List<Contact> contacts = reportService.getContactsByDate(reportDate);

        if (contacts.isEmpty()) {
            return ResponseEntity.noContent() // 204 No Content لو مفيش بيانات
                    .build();
        }

        try {
            String csvContent = reportService.generateCsvContent(contacts);

            // إعداد الـ Headers عشان الملف يتم تحميله كـ attachment
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts_report_" + reportDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv\"");
            headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE); // أو MediaType.TEXT_CSV_VALUE

            // بنرجع المحتوى كـ ByteArrayResource
            ByteArrayResource resource = new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8));

            System.out.println("تم إنشاء تقرير CSV بنجاح، جاري إرساله للتحميل.");
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(resource.contentLength())
                    .contentType(MediaType.parseMediaType("application/csv")) // تحديد نوع المحتوى كـ CSV
                    .body(resource);

        } catch (IOException e) {
            System.err.println("خطأ أثناء إنشاء أو إرسال تقرير CSV: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(new ByteArrayResource("Error generating CSV report.".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
