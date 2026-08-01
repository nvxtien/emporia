package com.emporia.authentication.bootstrap;

import com.emporia.authentication.user.UserAccount;
import com.emporia.authentication.user.UserAccountRepository;
import com.emporia.authentication.user.UserAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
class LegacyUserImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyUserImporter.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final boolean enabled;
    private final String legacySchema;
    private final String initialPassword;

    LegacyUserImporter(
            JdbcTemplate jdbc,
            UserAccountRepository users,
            PasswordEncoder passwords,
            @Value("${emporia.auth.legacy-user-import.enabled:true}") boolean enabled,
            @Value("${emporia.auth.legacy-user-import.schema:users}") String legacySchema,
            @Value("${emporia.auth.legacy-user-import.initial-password:}") String initialPassword) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwords = passwords;
        this.enabled = enabled;
        this.legacySchema = identifier(legacySchema);
        this.initialPassword = initialPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || !legacyTableExists()) {
            log.info("Legacy OpenTP user import skipped; {}.users was not found", legacySchema);
            return;
        }
        String encodedPassword = initialPassword == null || initialPassword.isBlank()
                ? null
                : passwords.encode(initialPassword);
        int[] imported = {0};
        int[] updated = {0};
        int[] skipped = {0};
        jdbc.query(
                "SELECT id, desk, permissionflags FROM " + legacySchema + ".users",
                result -> {
                    String username = result.getString("id");
                    if (username == null || username.isBlank()) return;
                    String desk = result.getString("desk");
                    String permissionFlags = result.getString("permissionflags");
                    String migratedDesk = desk == null || desk.isBlank() ? username : desk;
                    boolean canTrade = permissionFlags != null && permissionFlags.contains("T");
                    UserAccount existing = users.findByUsernameIgnoreCase(username).orElse(null);
                    if (existing != null) {
                        existing.updateTradingIdentity(migratedDesk, canTrade);
                        users.save(existing);
                        updated[0]++;
                        return;
                    }
                    if (encodedPassword == null) {
                        skipped[0]++;
                        return;
                    }
                    users.save(new UserAccount(
                            username,
                            legacyEmail(username),
                            encodedPassword,
                            migratedDesk,
                            canTrade,
                            Set.of(UserAuthority.ROLE_USER)
                    ));
                    imported[0]++;
                }
        );
        log.info("Imported {} legacy OpenTP users and refreshed {} existing trading identities",
                imported[0], updated[0]);
        if (skipped[0] > 0) {
            log.warn("Skipped {} new legacy users because LEGACY_USER_INITIAL_PASSWORD is unset", skipped[0]);
        }
    }

    private boolean legacyTableExists() {
        try {
            Boolean present = jdbc.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    legacySchema + ".users"
            );
            return Boolean.TRUE.equals(present);
        } catch (RuntimeException unsupportedDatabase) {
            log.debug("Database does not support PostgreSQL legacy-user discovery", unsupportedDatabase);
            return false;
        }
    }

    private static String legacyEmail(String username) {
        UUID stableId = UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8));
        return "legacy-" + stableId + "@migration.invalid";
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Legacy user schema must be a simple SQL identifier");
        }
        return value;
    }
}
