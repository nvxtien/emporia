package com.emporia.userpreferences;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspacePreferenceServiceTest {
    private final WorkspacePreferenceRepository repository = mock(WorkspacePreferenceRepository.class);
    private final WorkspacePreferenceService service =
            new WorkspacePreferenceService(repository, new ObjectMapper());

    @Test
    void returnsTheEmporiaLayoutWhenAUserHasNoSavedPreference() {
        when(repository.findById("new-user")).thenReturn(Optional.empty());

        WorkspacePreferenceService.WorkspacePreferenceView preference = service.get("new-user");

        assertThat(preference.layoutJson()).isEqualTo(WorkspacePreferenceService.DEFAULT_LAYOUT);
        assertThat(preference.updatedAt()).isNull();
    }

    @Test
    void normalizesAndStoresAWorkspaceObject() {
        when(repository.findById("trader")).thenReturn(Optional.empty());
        when(repository.save(any(WorkspacePreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspacePreferenceService.WorkspacePreferenceView saved =
                service.store("trader", "{ \"version\" : 1, \"panels\" : [\"watchlist\"] }");

        assertThat(saved.layoutJson()).isEqualTo("{\"version\":1,\"panels\":[\"watchlist\"]}");
        assertThat(saved.updatedAt()).isNotNull();
        verify(repository).save(any(WorkspacePreference.class));
    }

    @Test
    void rejectsArraysMalformedJsonAndOversizedLayouts() {
        assertThatThrownBy(() -> service.store("trader", "[]"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> service.store("trader", "{broken"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> service.store("trader", "{\"layout\":\"" + "x".repeat(200_001) + "\"}"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non-empty JSON object");
    }
}
