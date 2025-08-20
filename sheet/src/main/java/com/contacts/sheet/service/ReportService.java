package com.contacts.sheet.service;

 // تأكد من الـ package الصحيح لـ ContactRepository
import com.contacts.sheet.Repository.ContactRepo;
import com.contacts.sheet.controller.ReportController;
import com.contacts.sheet.entity.Contact;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
@Service
public class ReportService {

    private final ContactRepo contactRepository;
    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);
    public ReportService(ContactRepo contactRepository) {
        this.contactRepository = contactRepository;
    }

    /**
     * بيجلب الـ contacts من قاعدة البيانات بتاريخ معين.
     *
     * @param date التاريخ المطلوب لجلب البيانات منه.
     * @return قائمة بـ Contact objects.
     */
    public List<Contact> getContactsByDate(LocalDate date) {
        System.out.println("جاري جلب البيانات من الداتابيز بتاريخ: " + date);
        // بما إن created_at في الـ Entity بتاعك نوعه LocalDateTime أو Date،
        // وJpaRepo بيعمل query على تاريخ اليوم بس لو اديته LocalDate، فده المفروض يشتغل:
        return contactRepository.findByCreatedAtBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        // البديل لو الـ above مش شغال: ممكن تعرف custom query في الـ repository
        // @Query("SELECT c FROM Contact c WHERE FUNCTION('CONVERT', DATE, c.createdAt) = :date")
        // List<Contact> findByCreatedAtDate(@Param("date") LocalDate date);
    }

    /**
     * بيولد محتوى ملف CSV من قائمة الـ Contacts.
     *
     * @param contacts قائمة الـ Contact objects.
     * @return String يحتوي على محتوى CSV.
     * @throws IOException لو حدث خطأ أثناء كتابة الـ CSV.
     */
    public String generateCsvContent(List<Contact> contacts) throws IOException {
        StringWriter writer = new StringWriter();

        // تحديد الـ headers اللي هتظهر في الـ CSV
        String[] headers = {"ID", "Phone", "Last Attempt", "Last Result", "Created At"};

        // تحديد الـ CSV format
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader(headers) // استخدام الـ headers اللي عرفناها
                .build();

        try (CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {
            for (Contact contact : contacts) {
                csvPrinter.printRecord(
                        contact.getId(),
                        contact.getPhone1(),
                        contact.getLastAttempt1(), // غالباً هيكون LocalDateTime
                        contact.getLastResult1(),
                        contact.getCreatedAt()   // غالباً هيكون LocalDateTime
                );
            }
        }
        logger.info("CSV content generated successfully.");

        return writer.toString();
    }
}
