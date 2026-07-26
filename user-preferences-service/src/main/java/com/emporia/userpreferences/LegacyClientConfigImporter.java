package com.emporia.userpreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
class LegacyClientConfigImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyClientConfigImporter.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final List<String> DEFAULT_PANELS =
            List.of("watchlist", "market-depth", "order-ticket", "parent-orders", "child-orders");
    private static final Map<String, String> LEGACY_COLUMNS = Map.of(
            "owner", "owner",
            "tradedQuantity", "filled",
            "price", "price",
            "destination", "destination"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String targetSchema;
    private final String legacySchema;

    LegacyClientConfigImporter(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${emporia.user-preferences.legacy-import-enabled:true}") boolean enabled,
            @Value("${spring.flyway.default-schema:emporia_client_config}") String targetSchema,
            @Value("${emporia.user-preferences.legacy-schema:clientconfig}") String legacySchema) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.targetSchema = identifier(targetSchema);
        this.legacySchema = identifier(legacySchema);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || !legacyTableExists()) {
            log.info("Legacy client-configuration import skipped; {}.reactclientconfig was not found", legacySchema);
            return;
        }

        int[] importedLayouts = {0};
        int[] importedListings = {0};
        jdbc.query(
                "SELECT userid, config FROM " + legacySchema + ".reactclientconfig",
                result -> {
                    String userSubject = result.getString("userid");
                    String config = result.getString("config");
                    if (userSubject == null || userSubject.isBlank() || config == null || config.isBlank()) return;
                    try {
                        Conversion conversion = convert(objectMapper, config);
                        int layouts = jdbc.update("""
                                INSERT INTO %s.workspace_preference (user_subject, layout_json, updated_at)
                                VALUES (?, ?, ?)
                                ON CONFLICT (user_subject) DO NOTHING
                                """.formatted(targetSchema), userSubject, conversion.layoutJson(), Instant.now());
                        importedLayouts[0] += layouts;

                        int position = 0;
                        for (Long listingId : conversion.listingIds()) {
                            int displayOrder = position;
                            position++;
                            importedListings[0] += jdbc.update("""
                                    INSERT INTO %s.watchlist_entry
                                        (id, user_subject, listing_id, display_order, added_at)
                                    VALUES (?, ?, ?, ?, ?)
                                    ON CONFLICT (user_subject, listing_id) DO NOTHING
                                    """.formatted(targetSchema),
                                    UUID.randomUUID(), userSubject, listingId, displayOrder, Instant.now());
                        }
                    } catch (RuntimeException invalidConfig) {
                        log.warn("Could not migrate legacy workspace configuration for user {}", userSubject, invalidConfig);
                    }
                }
        );
        log.info("Imported {} legacy workspace layouts and {} watchlist entries",
                importedLayouts[0], importedListings[0]);
    }

    static Conversion convert(ObjectMapper objectMapper, String legacyConfig) {
        try {
            JsonNode root = objectMapper.readTree(legacyConfig);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Legacy layout must be a JSON object");

            Set<String> discoveredPanels = new LinkedHashSet<>();
            Set<Long> listingIds = new LinkedHashSet<>();
            Map<String, Boolean> columns = new LinkedHashMap<>();
            collect(root, discoveredPanels, listingIds, columns);

            if (discoveredPanels.isEmpty()) discoveredPanels.addAll(DEFAULT_PANELS);
            if (discoveredPanels.contains("parent-orders")) discoveredPanels.add("child-orders");
            List<String> panels = new ArrayList<>(discoveredPanels);
            int parentIndex = panels.indexOf("parent-orders");
            if (!panels.contains("order-ticket")) {
                panels.add(parentIndex < 0 ? panels.size() : parentIndex, "order-ticket");
            }

            ObjectNode target = objectMapper.createObjectNode();
            target.put("version", 1);
            var targetPanels = target.putArray("panels");
            panels.forEach(targetPanels::add);
            ObjectNode targetColumns = target.putObject("columns");
            columns.forEach(targetColumns::put);
            return new Conversion(objectMapper.writeValueAsString(target), List.copyOf(listingIds));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Legacy workspace configuration is invalid", exception);
        }
    }

    private static void collect(JsonNode node, Set<String> panels, Set<Long> listingIds,
                                Map<String, Boolean> columns) {
        if (node.isObject()) {
            String component = node.path("component").asText("");
            switch (component) {
                case "instrument-watch" -> panels.add("watchlist");
                case "market-depth" -> panels.add("market-depth");
                case "order-blotter" -> panels.add("parent-orders");
                default -> {
                }
            }

            String column = LEGACY_COLUMNS.get(node.path("colId").asText(""));
            if (column != null) columns.put(column, !node.path("hide").asBoolean(false));

            JsonNode configuredListings = node.get("listingIds");
            if (configuredListings != null && configuredListings.isArray()) {
                configuredListings.forEach(value -> {
                    if (value.canConvertToLong()) listingIds.add(value.asLong());
                });
            }
            node.properties().forEach(entry -> collect(entry.getValue(), panels, listingIds, columns));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, panels, listingIds, columns));
        }
    }

    private boolean legacyTableExists() {
        try {
            Boolean present = jdbc.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    legacySchema + ".reactclientconfig"
            );
            return Boolean.TRUE.equals(present);
        } catch (RuntimeException unsupportedDatabase) {
            log.debug("Database does not support PostgreSQL legacy-client-config discovery", unsupportedDatabase);
            return false;
        }
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Client-config schema must be a simple SQL identifier");
        }
        return value;
    }

    record Conversion(String layoutJson, List<Long> listingIds) {
    }
}
