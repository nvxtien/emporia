package com.emporia.userpreferences;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {
    List<WatchlistEntry> findByUserSubjectOrderByDisplayOrderAscAddedAtAsc(String userSubject);
    Optional<WatchlistEntry> findByUserSubjectAndListingId(String userSubject, long listingId);
    long countByUserSubject(String userSubject);
    void deleteByUserSubjectAndListingId(String userSubject, long listingId);

    @Modifying
    @Query(value = """
            INSERT INTO watchlist_entry (id, user_subject, listing_id, display_order, added_at)
            VALUES (:id, :userSubject, :listingId, :displayOrder, :addedAt)
            ON CONFLICT (user_subject, listing_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userSubject") String userSubject,
                       @Param("listingId") long listingId, @Param("displayOrder") int displayOrder,
                       @Param("addedAt") Instant addedAt);
}
