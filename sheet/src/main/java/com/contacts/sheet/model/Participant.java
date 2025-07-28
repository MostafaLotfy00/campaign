package com.contacts.sheet.model;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Participant {
    private String participantId;
    private String participantName;
    private String purpose; // مثلاً "customer", "outbound", "agent", "acd"
    private String userId; // ده لو الـ participant هو agent
    private List<Session> sessions;
}
