package com.contacts.sheet.Repository;



import com.contacts.sheet.entity.Contact;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;



import java.time.LocalDateTime;

import java.util.List;

import java.util.Optional;



public interface ContactRepo extends JpaRepository<Contact, Integer> {



    Optional<Contact> findByPhone1AndLastAttempt1(String phone, LocalDateTime lastAttempt);

    Optional<Contact> findByPhone1AndConversationId(String phone, String conversationId);

    Optional<Contact> findTopByPhone1OrderByIdDesc(String phone);

    List<Contact> findByCreatedAtBetween(LocalDateTime startOfDay, LocalDateTime endOfDay);



    @Query("SELECT c FROM Contact c " +

            "WHERE c.conversationId IS NOT NULL AND c.conversationId <> '' " +

            "AND c.conversationStartTime IS NULL")

    List<Contact> findWithValidConversationIdAndMissingStartTime();



    Optional<Contact> findByConversationId(String conversationId);



// ✅ Constraint 1 (unique_contact)

    @Query("SELECT c FROM Contact c " +

            "WHERE c.phone1 = :phone1 " +

            "AND c.lastAttempt1 = :lastAttempt1 " +

            "AND c.lastResult1 = :lastResult1 " +

            "AND c.phone2 = :phone2 " +

            "AND c.lastAttempt2 = :lastAttempt2 " +

            "AND c.lastResult2 = :lastResult2 " +

            "AND c.conversationId = :conversationId " +

            "AND c.orderId = :orderId " +

            "AND c.contactCallable = :contactCallable")

    Optional<Contact> findByUniqueContact(

            @Param("phone1") String phone1,

            @Param("lastAttempt1") LocalDateTime lastAttempt1,

            @Param("lastResult1") String lastResult1,

            @Param("phone2") String phone2,

            @Param("lastAttempt2") LocalDateTime lastAttempt2,

            @Param("lastResult2") String lastResult2,

            @Param("conversationId") String conversationId,

            @Param("orderId") String orderId,

            @Param("contactCallable") String contactCallable

    );



// ✅ Constraint 2 (unique_contact2)

    @Query("SELECT c FROM Contact c " +

            "WHERE c.phone1 = :phone1 " +

            "AND c.lastAttempt1 = :lastAttempt1 " +

            "AND c.lastResult1 = :lastResult1 " +

            "AND c.conversationId = :conversationId " +

            "AND c.orderId = :orderId " +

            "AND c.contactCallable = :contactCallable")

    Optional<Contact> findByUniqueContact2(

            @Param("phone1") String phone1,

            @Param("lastAttempt1") LocalDateTime lastAttempt1,

            @Param("lastResult1") String lastResult1,

            @Param("conversationId") String conversationId,

            @Param("orderId") String orderId,

            @Param("contactCallable") String contactCallable

    );



// ✅ Constraint 3 (unique_contact3)

    @Query("SELECT c FROM Contact c " +

            "WHERE c.phone2 = :phone2 " +

            "AND c.lastAttempt2 = :lastAttempt2 " +

            "AND c.lastResult2 = :lastResult2 " +

            "AND c.conversationId = :conversationId " +

            "AND c.orderId = :orderId " +

            "AND c.contactCallable = :contactCallable")

    Optional<Contact> findByUniqueContact3(

            @Param("phone2") String phone2,

            @Param("lastAttempt2") LocalDateTime lastAttempt2,

            @Param("lastResult2") String lastResult2,

            @Param("conversationId") String conversationId,

            @Param("orderId") String orderId,

            @Param("contactCallable") String contactCallable

    );



// ✅ Helpers

    @Query("SELECT c FROM Contact c WHERE c.lastAttempt1 = :lastAttempt1")

    List<Contact> findByLastAttempt1(@Param("lastAttempt1") LocalDateTime lastAttempt1);



    @Query("SELECT c FROM Contact c WHERE c.lastAttempt2 = :lastAttempt2")

    List<Contact> findByLastAttempt2(@Param("lastAttempt2") LocalDateTime lastAttempt2);





    Optional<Contact> findByPhone1AndLastAttempt1AndLastResult1AndConversationIdAndOrderIdAndContactCallable(

            String phone1,

            LocalDateTime lastAttempt1,

            String lastResult1,

            String conversationId,

            String orderId,

            String contactCallable

    );



// ✅ for unique_contact3 (phone2 + lastAttempt2 + lastResult2 + conversationId + orderId + contactCallable)

    Optional<Contact> findByPhone2AndLastAttempt2AndLastResult2AndConversationIdAndOrderIdAndContactCallable(

            String phone2,

            LocalDateTime lastAttempt2,

            String lastResult2,

            String conversationId,

            String orderId,

            String contactCallable

    );



// ✅ helper for part of unique_contact2 (phone1 + lastAttempt1 + lastResult1)

    Optional<Contact> findByPhone1AndLastAttempt1AndLastResult1(

            String phone1,

            LocalDateTime lastAttempt1,

            String lastResult1

    );

    List<Contact> findByOrderId(String orderId);
    // 2) عشان نتحقق هل موجود نفس القيم ولا لأ
    boolean existsByPhone1AndLastAttempt1AndPhone2AndLastAttempt2(
            String phone1,
            LocalDateTime lastAttempt1,
            String phone2,
            LocalDateTime lastAttempt2
    );

    Optional<Contact> findByPhone1AndPhone2AndLastAttempt1AndLastAttempt2(String phone1, String phone2, LocalDateTime lastAttempt1, LocalDateTime lastAttempt2);


}