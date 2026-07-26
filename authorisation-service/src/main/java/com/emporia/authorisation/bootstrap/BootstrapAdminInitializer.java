package com.emporia.authorisation.bootstrap;

import com.emporia.authorisation.user.UserAccount;
import com.emporia.authorisation.user.UserAccountRepository;
import com.emporia.authorisation.user.UserAuthority;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final BootstrapAdminProperties properties;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminInitializer(
            BootstrapAdminProperties properties,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }

        requireValue(properties.username(), "BOOTSTRAP_ADMIN_USERNAME");
        requireValue(properties.email(), "BOOTSTRAP_ADMIN_EMAIL");
        requireValue(properties.password(), "BOOTSTRAP_ADMIN_PASSWORD");

        if (userAccountRepository.existsByUsernameIgnoreCase(properties.username())) {
            return;
        }

        UserAccount administrator = new UserAccount(
                properties.username(),
                properties.email(),
                passwordEncoder.encode(properties.password()),
                StringUtils.hasText(properties.desk()) ? properties.desk().strip() : "default",
                properties.canTrade(),
                Set.of(UserAuthority.ROLE_USER, UserAuthority.ROLE_ADMIN)
        );
        userAccountRepository.save(administrator);
    }

    private static void requireValue(String value, String environmentVariable) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(environmentVariable + " must be set when admin bootstrap is enabled");
        }
    }
}
