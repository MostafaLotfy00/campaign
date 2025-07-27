package com.contacts.sheet.configration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration // بتحدد إن الكلاس ده بيوفر beans لـ Spring
public class AppConfig {

    @Bean // بتحدد إن الدالة دي بترجع bean لـ Spring context
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
