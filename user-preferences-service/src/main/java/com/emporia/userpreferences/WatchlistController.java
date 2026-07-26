package com.emporia.userpreferences;

import com.emporia.userpreferences.WatchlistService.WatchlistItem;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/watchlist")
class WatchlistController {
    private final WatchlistService watchlist;

    WatchlistController(WatchlistService watchlist) { this.watchlist = watchlist; }

    @GetMapping
    List<WatchlistItem> get(@AuthenticationPrincipal Jwt jwt,
                            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return watchlist.get(jwt.getSubject(), authorization);
    }

    @PostMapping("/{listingId}")
    @ResponseStatus(HttpStatus.CREATED)
    WatchlistItem add(@AuthenticationPrincipal Jwt jwt, @PathVariable long listingId,
                      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return watchlist.add(jwt.getSubject(), listingId, authorization);
    }

    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long listingId) {
        watchlist.remove(jwt.getSubject(), listingId);
    }
}
