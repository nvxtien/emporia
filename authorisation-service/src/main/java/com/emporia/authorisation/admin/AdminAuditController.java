package com.emporia.authorisation.admin;

import com.emporia.authorisation.admin.AdminAuditService.AdminAuditFilter;
import com.emporia.authorisation.admin.AdminAuditService.AdminAuditPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
class AdminAuditController {
    private final AdminAuditService audit;

    AdminAuditController(AdminAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    AdminAuditPage events(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return audit.list(new AdminAuditFilter(actor, action, entityType, entityId, result, page, size));
    }
}
