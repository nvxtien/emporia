package com.emporia.authentication.admin;

import com.emporia.authentication.admin.AdminUserService.AdminUserView;
import com.emporia.authentication.admin.AdminUserService.CreateUserRequest;
import com.emporia.authentication.admin.AdminUserService.UpdatePasswordRequest;
import com.emporia.authentication.admin.AdminUserService.UpdateTradingIdentityRequest;
import com.emporia.authentication.admin.AdminUserService.UpdateUserRequest;
import com.emporia.authentication.admin.AdminUserService.UpdateUserTierRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
class AdminUserController {
    private final AdminUserService users;

    AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    List<AdminUserView> list() {
        return users.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminUserView create(
            @RequestBody CreateUserRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return users.create(request, AdminAuditContext.from(authentication, requestId));
    }

    @GetMapping("/{userId}")
    AdminUserView get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PutMapping("/{userId}")
    AdminUserView update(
            @PathVariable UUID userId,
            @RequestBody UpdateUserRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return users.update(userId, request, AdminAuditContext.from(authentication, requestId));
    }

    @PutMapping("/{userId}/password")
    AdminUserView updatePassword(
            @PathVariable UUID userId,
            @RequestBody UpdatePasswordRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return users.updatePassword(userId, request, AdminAuditContext.from(authentication, requestId));
    }

    @PutMapping("/{userId}/trading-identity")
    AdminUserView updateTradingIdentity(
            @PathVariable UUID userId,
            @RequestBody UpdateTradingIdentityRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return users.updateTradingIdentity(userId, request, AdminAuditContext.from(authentication, requestId));
    }

    @PutMapping("/{userId}/tier")
    AdminUserView updateTier(
            @PathVariable UUID userId,
            @RequestBody UpdateUserTierRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return users.updateTier(userId, request, AdminAuditContext.from(authentication, requestId));
    }
}
