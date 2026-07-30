package com.emporia.userpreferences;

import com.emporia.userpreferences.WorkspacePreferenceService.WorkspacePreferenceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspacePreferenceControllerTest {
    private final WorkspacePreferenceService service = mock(WorkspacePreferenceService.class);
    private final Jwt jwt = mock(Jwt.class);
    private WorkspacePreferenceController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkspacePreferenceController(service);
        when(jwt.getSubject()).thenReturn("user-1");
    }

    @Test
    void getWorkspacePreferences() {
        WorkspacePreferenceView view = new WorkspacePreferenceView("{}", Instant.now());
        when(service.get("user-1")).thenReturn(view);

        assertThat(controller.get(jwt)).isEqualTo(view);
    }

    @Test
    void storeWorkspacePreferences() {
        WorkspacePreferenceView view = new WorkspacePreferenceView("{\"theme\":\"dark\"}", Instant.now());
        when(service.store("user-1", "{\"theme\":\"dark\"}")).thenReturn(view);

        WorkspacePreferenceController.StoreWorkspacePreference request =
                new WorkspacePreferenceController.StoreWorkspacePreference("{\"theme\":\"dark\"}");

        assertThat(controller.store(jwt, request)).isEqualTo(view);
    }
}
