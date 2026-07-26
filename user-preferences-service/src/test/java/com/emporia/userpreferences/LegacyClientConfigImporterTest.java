package com.emporia.userpreferences;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyClientConfigImporterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsFlexLayoutPanelsColumnsAndWatchlistIds() throws Exception {
        String legacy = """
                {
                  "layout": {
                    "children": [
                      {
                        "component": "instrument-watch",
                        "config": {
                          "listingIds": [95174, 104094, 95174],
                          "colState": [
                            {"colId": "price", "hide": true},
                            {"colId": "destination", "hide": false}
                          ]
                        }
                      },
                      {"component": "market-depth"},
                      {
                        "component": "order-blotter",
                        "config": [
                          {"colId": "owner", "hide": true},
                          {"colId": "tradedQuantity", "hide": false}
                        ]
                      }
                    ]
                  }
                }
                """;

        LegacyClientConfigImporter.Conversion converted =
                LegacyClientConfigImporter.convert(objectMapper, legacy);
        JsonNode layout = objectMapper.readTree(converted.layoutJson());

        assertThat(layout.path("version").asInt()).isEqualTo(1);
        assertThat(layout.path("panels")).isEqualTo(objectMapper.readTree(
                "[\"watchlist\",\"market-depth\",\"order-ticket\",\"parent-orders\",\"child-orders\"]"
        ));
        assertThat(layout.path("columns").path("owner").asBoolean()).isFalse();
        assertThat(layout.path("columns").path("filled").asBoolean()).isTrue();
        assertThat(layout.path("columns").path("price").asBoolean()).isFalse();
        assertThat(layout.path("columns").path("destination").asBoolean()).isTrue();
        assertThat(converted.listingIds()).isEqualTo(List.of(95174L, 104094L));
    }

    @Test
    void rejectsNonObjectLegacyConfiguration() {
        assertThatThrownBy(() -> LegacyClientConfigImporter.convert(objectMapper, "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }
}
