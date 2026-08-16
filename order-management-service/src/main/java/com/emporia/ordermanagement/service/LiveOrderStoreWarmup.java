package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fills {@link OrderStateCache} with every live order before the service takes
 * traffic, so the store holds the whole live set rather than the part this
 * process happens to have seen.
 *
 * <h2>Why this runs in {@code @PostConstruct} and not on ApplicationReadyEvent</h2>
 * <p>{@code DedupIndexWarmup} loads after the application is ready, and can:
 * its filter only ever answers "never seen", so a command arriving mid-load is
 * still handled correctly by falling through to the database.
 *
 * <p>This load cannot. If the web server were already accepting orders, a fill
 * could update an order in memory while the loader still held the older row
 * from the database, and writing that row over the newer one would lose the
 * fill. Loading before the port opens removes the race rather than guarding
 * against it, at the cost of startup time - which is bounded by the live set,
 * not by history.
 *
 * <h2>What a failure means</h2>
 * <p>The store is left incomplete and {@link OrderStateCache#isLiveSetComplete()}
 * stays false. Reads still work: they fall through to the database exactly as
 * they did before this class existed. What must not happen is anything
 * answering a question negatively from an incomplete store - see that method.
 */
@Component
public class LiveOrderStoreWarmup {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderStoreWarmup.class);

    private static final List<OrderStatus> LIVE = List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

    private final OrderStateCache cache;
    private final TradingOrderRepository orders;
    private final int pageSize;

    public LiveOrderStoreWarmup(OrderStateCache cache, TradingOrderRepository orders,
                                @Value("${emporia.orders.warmup-page-size:5000}") int pageSize) {
        this.cache = cache;
        this.orders = orders;
        this.pageSize = pageSize;
    }

    @PostConstruct
    public void load() {
        long startedAt = System.nanoTime();
        long loaded = 0;
        try {
            // Sorted by id so successive pages do not overlap. Nothing is
            // writing concurrently - the port is not open yet - so a plain
            // offset page is sound here in a way it would not be later.
            Page<TradingOrder> page = orders.findByStatusIn(
                    LIVE, PageRequest.of(0, pageSize, Sort.by("id")));
            while (true) {
                for (TradingOrder order : page.getContent()) {
                    cache.admitExisting(order);
                    loaded++;
                }
                if (!page.hasNext()) break;
                page = orders.findByStatusIn(LIVE, page.nextPageable());
            }
            cache.markLiveSetComplete();
            log.info("Live-order store ready: {} order(s) loaded in {} ms. Lookups for live orders "
                            + "now answer from memory, and indexes over the store may be trusted.",
                    loaded, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException loadFailure) {
            // Never fatal, and never marked complete on failure: a partially
            // loaded store reports "not live" for orders that are, and anything
            // trusting it would act on that. Staying incomplete costs database
            // reads and nothing else.
            log.error("Live-order store load failed after {} order(s); lookups stay on Postgres and "
                    + "indexes over the store must not be trusted", loaded, loadFailure);
        }
    }
}
