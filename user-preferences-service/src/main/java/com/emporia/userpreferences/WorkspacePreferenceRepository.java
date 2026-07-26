package com.emporia.userpreferences;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspacePreferenceRepository extends JpaRepository<WorkspacePreference, String> {
}
