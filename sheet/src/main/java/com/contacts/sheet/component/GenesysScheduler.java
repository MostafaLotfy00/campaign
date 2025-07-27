package com.contacts.sheet.component;

import com.contacts.sheet.service.GenesysService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component // عشان Spring يتعرف عليها كـ Component ويقدر يشغل الـ methods اللي فيها
public class GenesysScheduler {

    private final GenesysService genesysService; // بنعمل حقن لـ GenesysService

    // Constructor بيعمل حقن تلقائي
    public GenesysScheduler(GenesysService genesysService) {
        this.genesysService = genesysService;
    }

    // @Scheduled بتحدد إن الدالة دي هتشتغل بجدول زمني
    // fixedRate = 600000 يعني كل 600000 ملي ثانية (يعني 10 دقائق)
    @Scheduled(fixedRate = 600000)
    public void runGenesysDataSyncJob() {
        // هنا بنستدعي الدالة اللي بتعمل كل الشغل بتاع سحب وتخزين الداتا
        genesysService.syncContactsFromGenesysApi();
    }
}
