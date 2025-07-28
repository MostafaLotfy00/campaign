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
    // ده الـ Scheduler الأساسي اللي بيسحب بيانات الـ CSV وبيحدث الـ conversationId
    @Scheduled(fixedRate = 600000)
    public void runGenesysCsvSyncJob() {
        System.out.println("--- Scheduler: بدء تشغيل مهمة مزامنة بيانات Genesys من CSV ---");
        genesysService.syncContactsFromGenesysApi();
        System.out.println("--- Scheduler: انتهاء تشغيل مهمة مزامنة بيانات Genesys من CSV ---");
    }

    // <<<<<<<<<<<<<<< هنا التعديل الجديد >>>>>>>>>>>>>>>
    // مهمة جديدة للـ Scheduler عشان تحدث تفاصيل المكالمات
    // هتشتغل بنفس الـ fixedRate (كل 10 دقايق)
    // initialDelay = 60000 يعني هتبدأ بعد دقيقة واحدة من بدء التطبيق (أو بعد بدء الـ Scheduler)
    // ده بيضمن إن الـ CSV sync يكون بدأ يشتغل ويسجل الـ conversationId قبل ما نبدأ نطلب التفاصيل
    @Scheduled(fixedRate = 600000, initialDelay = 60000)
    public void runConversationDetailsUpdateJob() {
        System.out.println("--- Scheduler: بدء تشغيل مهمة تحديث تفاصيل المكالمات ---");
        genesysService.updateContactsWithConversationDetails();
        System.out.println("--- Scheduler: انتهاء تشغيل مهمة تحديث تفاصيل المكالمات ---");
    }
}
