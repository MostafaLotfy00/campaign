package com.contacts.sheet.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Entity
@Table(name = "contact_lists", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"phone", "last_attempt"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone")
    private String phone;

    // تأكد أن النوع هو LocalDateTime
    @Column(name = "last_attempt")
    private LocalDateTime lastAttempt;

    @Column(name = "last_result")
    private String lastResult;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Contact(String phone, String lastAttemptStr, String lastResult) {
        this.phone = phone;
        this.lastResult = lastResult;

        // إذا كان lastAttemptStr ليس فارغًا، حاول تحليله. وإلا، سيبقى null.
        if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
            try {
                this.lastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                // في حالة فشل التحويل، قم بطباعة تحذير وتعيين القيمة إلى null
                System.err.println("تحذير: فشل في تحليل lastAttempt String '" + lastAttemptStr + "' للهاتف: " + phone + ". سيتم تخزينه كـ null.");
                this.lastAttempt = null;
            }
        } else {
            this.lastAttempt = null; // تعيين null إذا كان String فارغًا
        }
        this.createdAt = LocalDateTime.now();
    }

    public Contact(String phone, LocalDateTime lastAttempt, String lastResult, String conversationId) {
        this.phone = phone;
        this.lastAttempt = lastAttempt;
        this.lastResult = lastResult;
        this.conversationId = conversationId; // <<<<< إضافة الـ conversationId هنا
        this.createdAt = LocalDateTime.now();
    }
}