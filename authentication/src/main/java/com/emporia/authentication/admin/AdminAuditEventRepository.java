package com.emporia.authentication.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, UUID> {

    @Query("""
            select event
            from AdminAuditEvent event
            where (:actor = '' or lower(event.actorSubject) like lower(concat('%', :actor, '%'))
                    or lower(event.actorUsername) like lower(concat('%', :actor, '%')))
                and (:action = '' or event.action = :action)
                and (:entityType = '' or event.entityType = :entityType)
                and (:entityId = '' or lower(event.entityId) like lower(concat('%', :entityId, '%')))
                and (:result = '' or event.result = :result)
            """)
    Page<AdminAuditEvent> findForAdmin(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("result") String result,
            Pageable pageable
    );
}
