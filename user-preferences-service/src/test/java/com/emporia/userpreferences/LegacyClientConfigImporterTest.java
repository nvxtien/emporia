package com.emporia.userpreferences;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyClientConfigImporterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsInvalidSchema() {
        assertThatThrownBy(() -> new LegacyClientConfigImporter(jdbc, mapper, true, "bad schema!", "clientconfig"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsWhenDisabled() {
        LegacyClientConfigImporter importer = new LegacyClientConfigImporter(jdbc, mapper, false, "emporia_client_config", "clientconfig");
        importer.run(new DefaultApplicationArguments());
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void skipsWhenLegacyTableDoesNotExist() {
        LegacyClientConfigImporter importer = new LegacyClientConfigImporter(jdbc, mapper, true, "emporia_client_config", "clientconfig");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false);

        importer.run(new DefaultApplicationArguments());
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void convertLegacyConfigJSON() {
        LegacyClientConfigImporter.Conversion conversion = LegacyClientConfigImporter.convert(mapper, "{\"component\":\"instrument-watch\",\"listingIds\":[10,20]}");
        assertThat(conversion.layoutJson()).contains("watchlist");
        assertThat(conversion.listingIds()).containsExactly(10L, 20L);
    }

    @Test
    void convertInvalidLegacyConfigThrows() {
        assertThatThrownBy(() -> LegacyClientConfigImporter.convert(mapper, "[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runImportsLegacyRowsWhenTableExists() throws Exception {
        LegacyClientConfigImporter importer = new LegacyClientConfigImporter(jdbc, mapper, true, "emporia_client_config", "clientconfig");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);

        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("userid")).thenReturn("user-1");
        when(rs.getString("config")).thenReturn("{\"component\":\"instrument-watch\",\"listingIds\":[10,20]}");

        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        importer.run(new DefaultApplicationArguments());
        verify(jdbc).query(anyString(), any(RowCallbackHandler.class));
    }
}
