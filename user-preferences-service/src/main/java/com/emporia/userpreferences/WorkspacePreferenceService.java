package com.emporia.userpreferences;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
class WorkspacePreferenceService {
    static final String DEFAULT_LAYOUT = """
            {"version":1,"panels":["watchlist","market-depth","order-ticket","parent-orders","child-orders"],"columns":{}}
            """.strip();
    private static final int MAX_LAYOUT_LENGTH = 200_000;

    private final WorkspacePreferenceRepository preferences;
    private final ObjectMapper objectMapper;

    WorkspacePreferenceService(WorkspacePreferenceRepository preferences, ObjectMapper objectMapper) {
        this.preferences = preferences;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    WorkspacePreferenceView get(String userSubject) {
        return preferences.findById(userSubject)
                .map(preference -> new WorkspacePreferenceView(preference.layoutJson(), preference.updatedAt()))
                .orElseGet(() -> new WorkspacePreferenceView(DEFAULT_LAYOUT, null));
    }

    @Transactional
    WorkspacePreferenceView store(String userSubject, String layoutJson) {
        String normalized = validate(layoutJson);
        WorkspacePreference preference = preferences.findById(userSubject)
                .orElseGet(() -> new WorkspacePreference(userSubject, normalized));
        preference.update(normalized);
        WorkspacePreference saved = preferences.save(preference);
        return new WorkspacePreferenceView(saved.layoutJson(), saved.updatedAt());
    }

    private String validate(String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank() || layoutJson.length() > MAX_LAYOUT_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "Workspace layout must be a non-empty JSON object");
        }
        try {
            JsonNode parsed = objectMapper.readTree(layoutJson);
            if (!parsed.isObject()) {
                throw new ResponseStatusException(BAD_REQUEST, "Workspace layout must be a JSON object");
            }
            return objectMapper.writeValueAsString(parsed);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Workspace layout is not valid JSON", exception);
        }
    }

    record WorkspacePreferenceView(String layoutJson, java.time.Instant updatedAt) {
    }
}
