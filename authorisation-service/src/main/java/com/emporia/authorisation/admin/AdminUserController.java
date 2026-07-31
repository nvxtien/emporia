package com.emporia.authorisation.admin;

import com.emporia.authorisation.admin.AdminUserService.AdminUserView;
import com.emporia.authorisation.admin.AdminUserService.CreateUserRequest;
import com.emporia.authorisation.admin.AdminUserService.UpdatePasswordRequest;
import com.emporia.authorisation.admin.AdminUserService.UpdateTradingIdentityRequest;
import com.emporia.authorisation.admin.AdminUserService.UpdateUserRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    AdminUserView create(@RequestBody CreateUserRequest request) {
        return users.create(request);
    }

    @GetMapping("/{userId}")
    AdminUserView get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PutMapping("/{userId}")
    AdminUserView update(@PathVariable UUID userId, @RequestBody UpdateUserRequest request) {
        return users.update(userId, request);
    }

    @PutMapping("/{userId}/password")
    AdminUserView updatePassword(@PathVariable UUID userId, @RequestBody UpdatePasswordRequest request) {
        return users.updatePassword(userId, request);
    }

    @PutMapping("/{userId}/trading-identity")
    AdminUserView updateTradingIdentity(
            @PathVariable UUID userId,
            @RequestBody UpdateTradingIdentityRequest request
    ) {
        return users.updateTradingIdentity(userId, request);
    }
}
