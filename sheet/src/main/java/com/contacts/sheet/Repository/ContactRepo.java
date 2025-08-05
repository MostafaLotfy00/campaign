package com.contacts.sheet.Repository;

import com.contacts.sheet.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
public interface ContactRepo extends JpaRepository<Contact, Integer> {
    // ده method Spring Data JPA هيعرف ينفذه تلقائي: بيشوف لو فيه contact بالـ phone و lastAttempt ده
    // هذه الميثود ستظل كما هي وتدعم البحث بقيمة null لـ lastAttempt
    Optional<Contact> findByPhoneAndLastAttempt(String phone, LocalDateTime lastAttempt);

    List<Contact> findByCreatedAtBetween(LocalDateTime startOfDay, LocalDateTime endOfDay);

    @Query("SELECT c FROM Contact c " +
            "WHERE c.conversationId IS NOT NULL AND c.conversationId <> '' " +
            "AND c.conversationStartTime IS NULL")
    List<Contact> findWithValidConversationIdAndMissingStartTime();

    //     @Query("SELECT c FROM Contact c WHERE FUNCTION('CONVERT', DATE, c.createdAt) = :date")
//     List<Contact> findByCreatedAtDate(@Param("date") LocalDate date);
Optional<Contact> findByConversationId(String conversationId);
}
