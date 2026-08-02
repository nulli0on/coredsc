# Java API

CoreDSC exposes stable contracts under `com.hubertstudios.coredsc.api` through Bukkit services. Add CoreDSC as a soft dependency, then resolve the API after both plugins are enabled.

```java
RegisteredServiceProvider<CoreDSCApi> registration =
        Bukkit.getServicesManager().getRegistration(CoreDSCApi.class);

if (registration == null) {
    getLogger().warning("CoreDSC API is unavailable; Discord integration is disabled.");
    return;
}

CoreDSCApi coreDsc = registration.getProvider();
```

## Main operations

All database, Discord and gameplay operations that can outlive the caller return `CompletableFuture`. Do not block a Paper/Folia scheduler with `join()`, `get()` or a Discord `complete()` call.

```java
coreDsc.findLinkedAccount(playerUuid);
coreDsc.createTicket(playerUuid, "Billing", "My purchase did not arrive");
coreDsc.createReport(reporterUuid, targetUuid, "Cheating", "Video available");
coreDsc.publishModerationAction(action);
coreDsc.publishPythonEvent("ARENA_FINISHED", Map.of("arena", "citadel"));
coreDsc.triggerLoreEvent("herald", "The gate has fallen", "CitadelPlugin");
coreDsc.recordCompetitiveResult(winnerUuid, winnerName, loserUuid, loserName);
coreDsc.competitiveLeaderboard(10);
```

Futures complete exceptionally with a descriptive policy/provider error. Module-unavailable behavior is explicit: query-style methods return an empty result where documented, while mutations that require the module fail.

## Market provider

A shop adapter can make its listings authoritative:

```java
public final class ShopMarketProvider implements EconomyMarketProvider {
    private final List<MarketListing> listings;

    public ShopMarketProvider(List<MarketListing> listings) {
        this.listings = List.copyOf(listings);
    }

    @Override
    public String providerId() {
        return "my-shop";
    }

    @Override
    public CompletableFuture<List<MarketListing>> listings() {
        return CompletableFuture.completedFuture(listings);
    }
}

Bukkit.getServicesManager().register(
        EconomyMarketProvider.class,
        new ShopMarketProvider(List.of(new EconomyMarketProvider.MarketListing(
                "starter-food", "Starter Food", "16 cooked beef",
                125.0D, "coins", "", "Use /shop in game"))),
        this,
        ServicePriority.Normal
);
```

Set `economy-market.market.source: SERVICE`. Return an immutable snapshot and perform storage/network work asynchronously. CoreDSC treats this contract as read-only presentation; purchases remain the shop plugin's responsibility.

## Competitive provider

Arena plugins that already own ratings can implement:

```java
public interface CompetitiveRatingProvider {
    String providerId();
    CompletableFuture<Optional<Rating>> rating(UUID minecraftUuid);
    CompletableFuture<List<Rating>> leaderboard(int limit);
}
```

Register it through `ServicesManager` and set `competitive.rating.source: SERVICE`. Results should be ordered best-first and honor the requested limit. If CoreDSC should own ELO, use `BUILT_IN` and call `recordCompetitiveResult(...)` at the arena's authoritative result boundary instead.

## Threading

CoreDSC's internal scheduler adapter is not part of the public API. An add-on must use Paper/Folia's global, region or entity scheduler that owns the Bukkit object it touches. It is safe to call the asynchronous CoreDSC API methods from any thread; continue Bukkit work on the correct owner after their futures complete.

Public events include account link/unlink, Discord readiness, tickets and reports. Depend only on API/event types—not module implementations, repositories or JDA internals.
