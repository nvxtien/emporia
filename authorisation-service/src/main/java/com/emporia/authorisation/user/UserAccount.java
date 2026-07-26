package com.emporia.authorisation.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 100)
    private String desk = "default";

    @Column(name = "can_trade", nullable = false)
    private boolean canTrade;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_authority", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "authority", nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private Set<UserAuthority> authorities = new HashSet<>();

    protected UserAccount() {
    }

    public UserAccount(String username, String email, String passwordHash, Set<UserAuthority> authorities) {
        this(username, email, passwordHash, "default", false, authorities);
    }

    public UserAccount(String username, String email, String passwordHash, String desk, boolean canTrade,
                       Set<UserAuthority> authorities) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.desk = desk;
        this.canTrade = canTrade;
        this.authorities = new HashSet<>(authorities);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDesk() {
        return desk;
    }

    public boolean canTrade() {
        return canTrade;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<UserAuthority> getAuthorities() {
        return Set.copyOf(authorities);
    }

    public void updateTradingIdentity(String desk, boolean canTrade) {
        this.desk = desk;
        this.canTrade = canTrade;
    }
}
