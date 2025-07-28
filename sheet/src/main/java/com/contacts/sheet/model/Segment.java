package com.contacts.sheet.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Segment {
    private String segmentType; // مثلاً "interact", "wrapup", "dialing"
    private String disconnectType; // نوع الفصل (مثلاً "peer", "client", "transfer")
    private String wrapUpCode;
}
