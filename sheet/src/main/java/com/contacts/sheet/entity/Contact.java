package com.contacts.sheet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Entity
@Table(name = "contact_lists", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"phone", "last_attempt"})
})
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter @Setter // Add individual Getters and Setters
    @Column(name = "phone")
    private String phone;

    @Getter @Setter
    @Column(name = "last_attempt")
    private LocalDateTime lastAttempt;

    @Getter // Keep getter for lastResult
    @Column(name = "last_result")
    private String lastResult;

    @Getter
    @Column(name = "conversation_id")
    private String conversationId;

    @Getter @Setter
    @Column(name = "conversation_start_time")
    private LocalDateTime conversationStartTime;

    @Getter @Setter
    @Column(name = "conversation_end_time")
    private LocalDateTime conversationEndTime;

    @Getter @Setter
    @Column(name = "call_duration_seconds")
    private Long callDurationSeconds;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "status", nullable = false)
    private String status;

    @Getter
    @Column(name = "selected_agent_id")
    private String selectedAgentId;

    @Getter
    @Column(name = "agent_name")
    private String agentName;

    @Getter @Setter
    @Column(name = "wrap_up_code")
    private String wrapUpCode;

    @Getter
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "contact_callable")
    private String contactCallable;

    // --- Custom Setters for lastResult, conversationId, selectedAgentId, agentName ---

    public void setLastResult(String lastResult) {
        this.lastResult = lastResult;
        // Apply the logic when lastResult is set
        applyNullificationLogic();
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
        // Apply the logic here as well, in case conversationId is set after lastResult
        applyNullificationLogic();
    }

    public void setSelectedAgentId(String selectedAgentId) {
        this.selectedAgentId = selectedAgentId;
        // Apply the logic here as well
        applyNullificationLogic();
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
        // Apply the logic here as well
        applyNullificationLogic();
    }

    // Helper method to encapsulate the nullification logic
    private void applyNullificationLogic() {
        if (this.conversationId==null  || this.conversationId.isEmpty() ) {
           this.conversationEndTime=null;
           this.conversationStartTime=null;
           this.callDurationSeconds=null;
            this.selectedAgentId = null;
            this.agentName = null;
        }
    }


    // --- Existing Constructors (no changes needed here, as setters will handle the logic) ---

    public Contact(String phone, String lastAttemptStr, String lastResult) {
        this.phone = phone;
        setLastResult(lastResult); // Use the custom setter
        // If lastAttemptStr is not null, try to parse it. Otherwise, it will remain null.
        if (lastAttemptStr != null && !lastAttemptStr.trim().isEmpty()) {
            try {
                // Ensure the DateTimeFormatter matches your actual input format.
                // Based on your image, it seems to be ISO_LOCAL_DATE_TIME or similar.
                // Let's assume ISO_LOCAL_DATE_TIME for the example, adjust if needed.
                this.lastAttempt = LocalDateTime.parse(lastAttemptStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                System.err.println("Warning: Failed to parse lastAttempt String '" + lastAttemptStr + "' for phone: " + phone + ". It will be stored as null.");
                this.lastAttempt = null;
            }
        } else {
            this.lastAttempt = null; // Set to null if the String is empty
        }
        this.createdAt = LocalDateTime.now();
        // The nullification logic will be applied when setLastResult is called.
    }

    public Contact(String phone, LocalDateTime lastAttempt, String lastResult, String conversationId,  String orderId,String contactCallable) {
        this.phone = phone;
        this.lastAttempt = lastAttempt;
        this.orderId = orderId; // تخزين orderId
        setLastResult(lastResult); // Use the custom setter
        setConversationId(conversationId); // Use the custom setter
        this.createdAt = LocalDateTime.now();
        this.status = "not sent"; // <<<<< تعيين القيمة الافتراضية هنا
this.contactCallable = contactCallable;
        // The nullification logic will be applied when setLastResult and setConversationId are called.
    }

    public Contact(String phone, LocalDateTime lastAttempt, String lastResult, String conversationId,
                   LocalDateTime conversationStartTime, LocalDateTime conversationEndTime,
                   String selectedAgentId, String agentName, String wrapUpCode, Long callDurationSeconds, String orderId) {
        this.phone = phone;
        this.lastAttempt = lastAttempt;
        setLastResult(lastResult); // Use the custom setter
        setConversationId(conversationId); // Use the custom setter
        this.conversationStartTime = conversationStartTime;
        this.conversationEndTime = conversationEndTime;
        setSelectedAgentId(selectedAgentId); // Use the custom setter
        setAgentName(agentName); // Use the custom setter
        this.wrapUpCode = wrapUpCode;
        this.orderId = orderId;
        this.status = "not sent";
        this.callDurationSeconds = callDurationSeconds;
        this.createdAt = LocalDateTime.now();
        // The nullification logic will be applied by the custom setters.
    }
}