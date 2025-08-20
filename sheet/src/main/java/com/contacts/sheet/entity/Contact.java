package com.contacts.sheet.entity;


import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.CreationTimestamp;


import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;

import java.time.format.DateTimeParseException;


@Entity
@Table(name = "contact_lists",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"phone1", "phone2", "last_attempt1", "last_attempt2"})
        }
)
@NoArgsConstructor
@AllArgsConstructor

@Data

public class Contact {


    @Id

    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;

    @Getter
    @Setter // Add individual Getters and Setters

    @Column(name = "phone1")

    private String phone1;

    @Getter
    @Setter

    @Column(name = "last_attempt1")

    private LocalDateTime lastAttempt1;

    @Getter // Keep getter for lastResult

    @Column(name = "last_result1")

    private String lastResult1;

    @Column(name = "phone2")

    private String phone2;

    @Getter
    @Setter

    @Column(name = "last_attempt2")

    private LocalDateTime lastAttempt2;

    @Getter // Keep getter for lastResult

    @Column(name = "last_result2")

    private String lastResult2;

    @Getter

    @Column(name = "conversation_id")

    private String conversationId;

    @Getter
    @Setter

    @Column(name = "conversation_start_time")

    private LocalDateTime conversationStartTime;

    @Getter
    @Setter

    @Column(name = "conversation_end_time")

    private LocalDateTime conversationEndTime;

    @Getter
    @Setter

    @Column(name = "call_duration_seconds")

    private Long callDurationSeconds;

    @Column(name = "order_id")

    private String orderId;

    @Column(name = "phone1Status")

    private String phone1Status; // "sent"/"not sent"

    @Column(name = "phone2Status")

    private String phone2Status; // "sent"/"not sent"

    @Getter

    @Column(name = "selected_agent_id")

    private String selectedAgentId;

    @Getter

    @Column(name = "agent_name")

    private String agentName;

    @Getter

    @Column(name = "agent_email")

    private String agentEmail;

    @Getter
    @Setter

    @Column(name = "wrap_up_code")

    private String wrapUpCode;

    @Getter

    @Column(name = "created_at", nullable = false, updatable = false)

    private LocalDateTime createdAt;

    @Column(name = "contact_callable")

    private String contactCallable;

    @Column(name = "callback_scheduled_time")

    private LocalDateTime callbackScheduledTime;

    @Getter
    @Setter

    @Column(name = "talk_time_seconds")

    private Long talkTimeSeconds;

    @Getter
    @Setter

    @Column(name = "hold_time_seconds")

    private Long holdTimeSeconds;

    @Getter
    @Setter

    @Column(name = "after_call_work_seconds")

    private Long afterCallWorkSeconds;


// --- Custom Setters for lastResult, conversationId, selectedAgentId, agentName ---


    public void setLastResult1(String lastResult1) {

        this.lastResult1 = lastResult1;

// Apply the logic when lastResult is set

        applyNullificationLogic();

    }

    public void setLastResult2(String lastResult2) {

        this.lastResult2 = lastResult2;

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


    public void setAgentEmail(String agentEmail) {

        this.agentEmail = agentEmail;

        applyNullificationLogic();

    }


// Helper method to encapsulate the nullification logic

    private void applyNullificationLogic() {

        if (this.conversationId == null || this.conversationId.isEmpty()) {

            this.conversationEndTime = null;

            this.conversationStartTime = null;

            this.callDurationSeconds = null;

            this.selectedAgentId = null;

            this.agentName = null;

            this.agentEmail = null;

        }

    }


// --- Existing Constructors (no changes needed here, as setters will handle the logic) ---


    public Contact(String phone1, String lastAttemptStr1, String lastResult1,

                   String phone2, String lastAttemptStr2, String lastResult2) {

        this.phone1 = phone1;

        setLastResult1(lastResult1); // Use the custom setter for phone1


// Handle lastAttemptStr1

        if (lastAttemptStr1 != null && !lastAttemptStr1.trim().isEmpty()) {

            try {

                this.lastAttempt1 = LocalDateTime.parse(lastAttemptStr1, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            } catch (DateTimeParseException e) {

                System.err.println("Warning: Failed to parse lastAttempt String '" + lastAttemptStr1 +

                        "' for phone1: " + phone1 + ". It will be stored as null.");

                this.lastAttempt1 = null;

            }

        } else {

            this.lastAttempt1 = null;

        }


// ========== نفس اللوجيك على phone2 ==========

        this.phone2 = phone2;

        setLastResult2(lastResult2); // Use the custom setter for phone2


        if (lastAttemptStr2 != null && !lastAttemptStr2.trim().isEmpty()) {

            try {

                this.lastAttempt2 = LocalDateTime.parse(lastAttemptStr2, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            } catch (DateTimeParseException e) {

                System.err.println("Warning: Failed to parse lastAttempt String '" + lastAttemptStr2 +

                        "' for phone2: " + phone2 + ". It will be stored as null.");

                this.lastAttempt2 = null;

            }

        } else {

            this.lastAttempt2 = null;

        }


        this.createdAt = LocalDateTime.now();

    }


    public Contact(String phone1, LocalDateTime lastAttempt1, String lastResult1,

                   String phone2, LocalDateTime lastAttempt2, String lastResult2,

                   String conversationId, String orderId, String contactCallable) {

        this.phone1 = phone1;

        this.lastAttempt1 = lastAttempt1;

        this.lastResult1 = lastResult1;


        this.phone2 = phone2;

        this.lastAttempt2 = lastAttempt2;

        this.lastResult2 = lastResult2;


        this.orderId = orderId;

        this.contactCallable = contactCallable;


        setConversationId(conversationId); // يطبق منطقك

        setLastResult1(lastResult1); // nullification logic

        setLastResult2(lastResult2); // nullification logic


        this.createdAt = LocalDateTime.now();

        this.phone1Status = "not sent";

        this.phone2Status = "not sent";

    }

    public Contact(String phone1, LocalDateTime lastAttempt1, String lastResult1, String phone2, LocalDateTime lastAttempt2, String lastResult2, String conversationId,

                   LocalDateTime conversationStartTime, LocalDateTime conversationEndTime,

                   String selectedAgentId, String agentName, String agentEmail, String wrapUpCode, Long callDurationSeconds, String orderId) {

        this.phone1 = phone1;

        this.phone2 = phone2;

        this.lastAttempt1 = lastAttempt1;

        this.lastAttempt2 = lastAttempt2;

        setLastResult1(lastResult1); // Use the custom setter

        setLastResult2(lastResult2);

        setConversationId(conversationId); // Use the custom setter

        this.conversationStartTime = conversationStartTime;

        this.conversationEndTime = conversationEndTime;

        setSelectedAgentId(selectedAgentId); // Use the custom setter

        setAgentName(agentName);

        setAgentEmail(agentEmail);// Use the custom setter

        this.wrapUpCode = wrapUpCode;

        this.orderId = orderId;

        this.phone2Status = "not sent";

        this.phone1Status = "not sent";


        this.callDurationSeconds = callDurationSeconds;

        this.createdAt = LocalDateTime.now();

// The nullification logic will be applied by the custom setters.

    }

}