package com.contacts.sheet.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationDetailsResponse {
    private String conversationId;
    private LocalDateTime conversationStart;
    private LocalDateTime conversationEnd;
    private String originatingDirection;
    private List<Participant> participants;
}
