package com.hubertstudios.coredsc.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service-provider contract for shop plugins which want their listings exposed
 * through CoreDSC's Discord economy terminal.
 */
public interface EconomyMarketProvider {
    record MarketListing(
            String id,
            String name,
            String description,
            double price,
            String currency,
            String iconUrl,
            String purchaseHint
    ) { }

    String providerId();

    CompletableFuture<List<MarketListing>> listings();
}
