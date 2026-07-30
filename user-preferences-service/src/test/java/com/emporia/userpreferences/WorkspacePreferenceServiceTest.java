package com.emporia.userpreferences;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspacePreferenceServiceTest {
    private final WorkspacePreferenceRepository repository = mock(WorkspacePreferenceRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private WorkspacePreferenceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspacePreferenceService(repository, mapper);
    }

    @Test
    void getExistingPreferenceReturnsView() {
        WorkspacePreference entity = new WorkspacePreference("user-1", "{\"layout\":\"default\"}");
        when(repository.findById("user-1")).thenReturn(Optional.of(entity));

        WorkspacePreferenceService.WorkspacePreferenceView view = service.get("user-1");
        assertThat(view.layoutJson()).isEqualTo("{\"layout\":\"default\"}");
    }

    @Test
    void getMissingPreferenceReturnsEmptyDefaultView() {
        when(repository.findById("user-1")).thenReturn(Optional.empty());

        WorkspacePreferenceService.WorkspacePreferenceView view = service.get("user-1");
        assertThat(view.layoutJson()).isEqualTo(WorkspacePreferenceService.DEFAULT_LAYOUT);
    }

    @Test
    void storeUpdatesOrCreatePreference() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspacePreferenceService.WorkspacePreferenceView view = service.store("user-1", "{\"theme\":\"dark\"}");
        assertThat(view.layoutJson()).isEqualTo("{\"theme\":\"dark\"}");
        verify(repository).save(any());
    }
}
