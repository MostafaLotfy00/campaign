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

    //constructor
    public GenesysScheduler(GenesysService genesysService, TaggerService taggerService) {
        this.genesysService = genesysService;
        this.taggerService = taggerService;}
//run Genesyspiplineflow
    @Scheduled(fixedRate = 60000)
    public void runFullGenesysPipelineJob() {
        logger.info("🚀 Scheduler:  Starting full Genesys sync and processing pipeline...");
        genesysService.syncContactsFromGenesysApi();
        genesysService.updateContactsWithConversationDetails();




        // Step 3: Send contacts to Tagger
        try {
            logger.info("📤 iteration 3: Sending contacts to Tagger...");
            List<Contact> contacts = genesysService.getContacts();
            taggerService.sendContactsToTagger(contacts);
            logger.info("✅ iteration 3: Has been finished.");
        } catch (Exception e) {
            logger.error("❌ Step 3 failed: {}", e.getMessage());
        }

        logger.info("🏁 Scheduler: Finished full Genesys pipeline execution.");
    }



}
