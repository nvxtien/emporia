package com.emporia.authentication.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    List<UserAccount> findAllByOrderByUsernameAsc();

    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            select count(account)
            from UserAccount account join account.authorities authority
            where account.enabled = true and authority = :authority
            """)
    long countEnabledByAuthority(@Param("authority") UserAuthority authority);
}
