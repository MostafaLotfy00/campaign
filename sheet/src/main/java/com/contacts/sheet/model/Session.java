package com.contacts.sheet.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Session {
    private String sessionId;
    private String direction; // مثلاً "outbound", "inbound"
    private String dnis; // رقم التليفون (لـ outbound)
    private String ani; // رقم المتصل (لـ inbound)
    private String selectedAgentId; // <<<<<<< ده الحقل اللي عاوزينه
    private List<Segment> segments;
    private String MediaType;
    private LocalDateTime CallbackScheduledTime;
}
