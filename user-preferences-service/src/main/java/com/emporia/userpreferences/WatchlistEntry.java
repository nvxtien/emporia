package com.emporia.userpreferences;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watchlist_entry")
class WatchlistEntry {
    @Id private UUID id;
    @Column(name = "user_subject", nullable = false, length = 200) private String userSubject;
    @Column(name = "listing_id", nullable = false) private long listingId;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "added_at", nullable = false) private Instant addedAt;

    protected WatchlistEntry() {
    }

    WatchlistEntry(String userSubject, long listingId, int displayOrder) {
        this.id = UUID.randomUUID();
        this.userSubject = userSubject;
        this.listingId = listingId;
        this.displayOrder = displayOrder;
        this.addedAt = Instant.now();
    }

    UUID id() { return id; }
    long listingId() { return listingId; }
    int displayOrder() { return displayOrder; }
    Instant addedAt() { return addedAt; }
}
