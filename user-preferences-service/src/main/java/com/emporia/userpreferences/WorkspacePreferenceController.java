package com.emporia.userpreferences;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workspace-preferences")
class WorkspacePreferenceController {
    private final WorkspacePreferenceService preferences;

    WorkspacePreferenceController(WorkspacePreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    WorkspacePreferenceService.WorkspacePreferenceView get(@AuthenticationPrincipal Jwt jwt) {
        return preferences.get(jwt.getSubject());
    }

    @PutMapping
    WorkspacePreferenceService.WorkspacePreferenceView store(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody StoreWorkspacePreference request) {
        return preferences.store(jwt.getSubject(), request.layoutJson());
    }

    record StoreWorkspacePreference(@NotBlank String layoutJson) {
    }
}
