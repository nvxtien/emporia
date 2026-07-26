package com.emporia.userpreferences;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workspace_preference")
class WorkspacePreference {
    @Id
    @Column(name = "user_subject", length = 200)
    private String userSubject;

    @Column(name = "layout_json", nullable = false, columnDefinition = "text")
    private String layoutJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkspacePreference() {
    }

    WorkspacePreference(String userSubject, String layoutJson) {
        this.userSubject = userSubject;
        update(layoutJson);
    }

    void update(String layoutJson) {
        this.layoutJson = layoutJson;
        this.updatedAt = Instant.now();
    }

    String layoutJson() {
        return layoutJson;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
