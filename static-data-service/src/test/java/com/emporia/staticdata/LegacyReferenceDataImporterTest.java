package com.emporia.staticdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyReferenceDataImporterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void rejectsInvalidSchemaIdentifier() {
        assertThatThrownBy(() -> new LegacyReferenceDataImporter(jdbc, true, "bad schema!", "referencedata"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyReferenceDataImporter(jdbc, true, "emporia_static_data", "bad schema!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesUnsupportedDatabaseExceptionGracefully() {
        LegacyReferenceDataImporter importer = new LegacyReferenceDataImporter(jdbc, true, "emporia_static_data", "referencedata");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.UncategorizedDataAccessException("Unsupported H2 to_regclass", null) {});

        importer.run(new DefaultApplicationArguments());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void skipsWhenDisabled() {
        LegacyReferenceDataImporter importer = new LegacyReferenceDataImporter(jdbc, false, "emporia_static_data", "referencedata");
        importer.run(new DefaultApplicationArguments());

        verify(jdbc, never()).update(anyString());
    }

    @Test
    void skipsWhenLegacyTablesDoNotExist() {
        LegacyReferenceDataImporter importer = new LegacyReferenceDataImporter(jdbc, true, "emporia_static_data", "referencedata");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString(), anyString())).thenReturn(false);

        importer.run(new DefaultApplicationArguments());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void importsWhenLegacyTablesExist() {
        LegacyReferenceDataImporter importer = new LegacyReferenceDataImporter(jdbc, true, "emporia_static_data", "referencedata");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString(), anyString())).thenReturn(true);
        when(jdbc.update(anyString())).thenReturn(10);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);

        importer.run(new DefaultApplicationArguments());
        verify(jdbc).update(anyString());
    }
}
