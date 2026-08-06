package com.emporia.authentication.admin;

import com.emporia.authentication.user.UserAccount;
import com.emporia.authentication.user.UserAccountRepository;
import com.emporia.authentication.user.UserAuthority;
import com.emporia.authentication.user.UserTier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminUserService {
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_DESK_LENGTH = 100;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService audit;

    public AdminUserService(UserAccountRepository users, PasswordEncoder passwordEncoder, AdminAuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> list() {
        return users.findAllByOrderByUsernameAsc().stream()
                .map(AdminUserView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserView get(UUID userId) {
        return AdminUserView.from(find(userId));
    }

    @Transactional
    public AdminUserView create(CreateUserRequest request, AdminAuditContext auditContext) {
        requireBody(request);
        String username = username(request.username());
        String email = email(request.email());
        ensureUniqueUsername(username, null);
        ensureUniqueEmail(email, null);

        UserAccount account = new UserAccount(
                username,
                email,
                passwordEncoder.encode(password(request.password())),
                desk(request.desk()),
                Boolean.TRUE.equals(request.canTrade()),
                tier(request.tier()),
                authorities(request.authorities())
        );
        AdminUserView created = AdminUserView.from(users.save(account));
        audit.recordUserEvent(auditContext, "USER_CREATED", null, created, null);
        return created;
    }

    @Transactional
    public AdminUserView update(UUID userId, UpdateUserRequest request, AdminAuditContext auditContext) {
        requireBody(request);
        UserAccount account = find(userId);
        AdminUserView before = AdminUserView.from(account);
        String username = username(request.username());
        String email = email(request.email());
        Set<UserAuthority> nextAuthorities = authorities(request.authorities());
        boolean nextEnabled = request.enabled() == null || request.enabled();

        ensureUniqueUsername(username, userId);
        ensureUniqueEmail(email, userId);
        ensureAdminRemains(account, nextEnabled, nextAuthorities);

        account.updateAccount(username, email, nextEnabled, nextAuthorities);
        account.updateTradingIdentity(desk(request.desk()), Boolean.TRUE.equals(request.canTrade()));
        if (request.tier() != null) {
            account.updateTier(request.tier());
        }
        AdminUserView updated = AdminUserView.from(account);
        audit.recordUserEvent(auditContext, "USER_UPDATED", before, updated, null);
        return updated;
    }

    @Transactional
    public AdminUserView updatePassword(UUID userId, UpdatePasswordRequest request, AdminAuditContext auditContext) {
        requireBody(request);
        UserAccount account = find(userId);
        AdminUserView before = AdminUserView.from(account);
        account.updatePasswordHash(passwordEncoder.encode(password(request.password())));
        AdminUserView updated = AdminUserView.from(account);
        audit.recordUserEvent(auditContext, "USER_PASSWORD_CHANGED", before, updated, Map.of("passwordChanged", true));
        return updated;
    }

    @Transactional
    public AdminUserView updateTradingIdentity(
            UUID userId,
            UpdateTradingIdentityRequest request,
            AdminAuditContext auditContext
    ) {
        requireBody(request);
        UserAccount account = find(userId);
        AdminUserView before = AdminUserView.from(account);
        account.updateTradingIdentity(desk(request.desk()), request.canTrade());
        AdminUserView updated = AdminUserView.from(account);
        audit.recordUserEvent(auditContext, "USER_TRADING_IDENTITY_UPDATED", before, updated, null);
        return updated;
    }

    @Transactional
    public AdminUserView updateTier(
            UUID userId,
            UpdateUserTierRequest request,
            AdminAuditContext auditContext
    ) {
        requireBody(request);
        UserAccount account = find(userId);
        AdminUserView before = AdminUserView.from(account);
        account.updateTier(tier(request.tier()));
        AdminUserView updated = AdminUserView.from(account);
        audit.recordUserEvent(auditContext, "USER_TIER_UPDATED", before, updated, null);
        return updated;
    }

    private UserAccount find(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found"));
    }

    private void ensureUniqueUsername(String username, UUID currentUserId) {
        users.findByUsernameIgnoreCase(username)
                .filter(account -> !account.getId().equals(currentUserId))
                .ifPresent(account -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already in use");
                });
    }

    private void ensureUniqueEmail(String email, UUID currentUserId) {
        users.findByEmailIgnoreCase(email)
                .filter(account -> !account.getId().equals(currentUserId))
                .ifPresent(account -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use");
                });
    }

    private void ensureAdminRemains(UserAccount account, boolean nextEnabled, Set<UserAuthority> nextAuthorities) {
        boolean currentlyAdmin = account.isEnabled() && account.getAuthorities().contains(UserAuthority.ROLE_ADMIN);
        boolean remainsAdmin = nextEnabled && nextAuthorities.contains(UserAuthority.ROLE_ADMIN);
        if (currentlyAdmin && !remainsAdmin && users.countEnabledByAuthority(UserAuthority.ROLE_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At least one enabled administrator is required");
        }
    }

    private static String username(String value) {
        String result = required(value, "Username is required");
        if (result.length() > MAX_USERNAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is too long");
        }
        return result;
    }

    private static String email(String value) {
        String result = required(value, "Email is required").toLowerCase(Locale.ROOT);
        if (result.length() > MAX_EMAIL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is too long");
        }
        if (!result.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email must be valid");
        }
        return result;
    }

    private static String desk(String value) {
        String result = StringUtils.hasText(value) ? value.strip() : "default";
        if (result.length() > MAX_DESK_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Desk is too long");
        }
        return result;
    }

    private static UserTier tier(UserTier value) {
        return value == null ? UserTier.RETAIL : value;
    }

    private static String password(String value) {
        String result = required(value, "Password is required");
        if (result.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        return result;
    }

    private static Set<UserAuthority> authorities(Set<String> values) {
        Set<UserAuthority> result = values == null || values.isEmpty()
                ? new HashSet<>(Set.of(UserAuthority.ROLE_USER))
                : values.stream()
                .map(AdminUserService::authority)
                .collect(Collectors.toSet());
        result.add(UserAuthority.ROLE_USER);
        return result;
    }

    private static UserAuthority authority(String value) {
        try {
            return UserAuthority.valueOf(required(value, "Authority is required").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidAuthority) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported authority " + value,
                    invalidAuthority);
        }
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.strip();
    }

    private static void requireBody(Object request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
    }

    public record CreateUserRequest(
            String username,
            String email,
            String password,
            String desk,
            Boolean canTrade,
            UserTier tier,
            Set<String> authorities
    ) {
    }

    public record UpdateUserRequest(
            String username,
            String email,
            Boolean enabled,
            String desk,
            Boolean canTrade,
            UserTier tier,
            Set<String> authorities
    ) {
    }

    public record UpdatePasswordRequest(String password) {
    }

    public record UpdateTradingIdentityRequest(String desk, boolean canTrade) {
    }

    public record UpdateUserTierRequest(UserTier tier) {
    }

    public record AdminUserView(
            UUID id,
            String username,
            String email,
            boolean enabled,
            String desk,
            boolean canTrade,
            UserTier tier,
            List<String> authorities,
            Instant createdAt
    ) {
        static AdminUserView from(UserAccount account) {
            return new AdminUserView(
                    account.getId(),
                    account.getUsername(),
                    account.getEmail(),
                    account.isEnabled(),
                    account.getDesk(),
                    account.canTrade(),
                    account.getTier(),
                    account.getAuthorities().stream()
                            .map(Enum::name)
                            .sorted(Comparator.naturalOrder())
                            .toList(),
                    account.getCreatedAt()
            );
        }
    }
}
