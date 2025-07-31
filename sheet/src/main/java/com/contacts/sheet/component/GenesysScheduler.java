package com.contacts.sheet.component;

import com.contacts.sheet.entity.Contact;
import com.contacts.sheet.service.GenesysService;
import com.contacts.sheet.service.TaggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GenesysScheduler {

    private final GenesysService genesysService;
    private final TaggerService taggerService;
    private static final Logger logger = LoggerFactory.getLogger(GenesysScheduler.class);
    public GenesysScheduler(GenesysService genesysService, TaggerService taggerService) {
        this.genesysService = genesysService;
        this.taggerService = taggerService;
    }

    @Scheduled(fixedRate = 600000)
    public void runGenesysCsvSyncJob() {
        logger.info("--- Scheduler: Starting Genesys data sync task from CSV ---");
        genesysService.syncContactsFromGenesysApi();
        System.out.println("--- Scheduler: انتهاء تشغيل مهمة مزامنة بيانات Genesys من CSV ---");
    }

    @Scheduled(fixedRate = 600000, initialDelay = 60000)
    public void runConversationDetailsUpdateJob() {
        System.out.println("--- Scheduler: بدء تشغيل مهمة تحديث تفاصيل المكالمات ---");
        genesysService.updateContactsWithConversationDetails();
        System.out.println("--- Scheduler: انتهاء تشغيل مهمة تحديث تفاصيل المكالمات ---");
    }

    @Scheduled(fixedRate = 600000, initialDelay = 120000)
    public void runSendToTaggerJob() {
        System.out.println("--- Scheduler: بدء تشغيل مهمة إرسال البيانات لـ Tagger ---");
        List<Contact> contacts = genesysService.getContacts(); // تأكد أن الميثود دي موجودة
        taggerService.sendContactsToTagger(contacts);
        System.out.println("--- Scheduler: انتهاء مهمة إرسال البيانات لـ Tagger ---");
    }
}
