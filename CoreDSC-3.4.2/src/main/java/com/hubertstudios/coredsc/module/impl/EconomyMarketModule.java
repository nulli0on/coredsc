package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.api.EconomyMarketProvider;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Privacy-first Vault balance, live inventory and server-market Discord terminal. */
public final class EconomyMarketModule implements CoreModule, DiscordCommandContributor {
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]{1,32}");

    private enum MarketSource { CONFIG, SERVICE }

    private record PlayerTarget(UUID uuid, String name) { }

    private record InventorySnapshot(String playerName, List<String> lines, int occupiedSlots) { }

    private final CoreDSCPlugin plugin;
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private LinkedAccountRepository links;
    private VaultEconomy vault;
    private EconomyMarketProvider marketProvider;
    private ListenerAdapter discordListener;
    private List<EconomyMarketProvider.MarketListing> configuredListings = List.of();
    private MarketSource marketSource;
    private boolean active;
    private boolean requireLinked;
    private boolean ephemeral;
    private boolean exposeLore;
    private boolean allowStaffLookup;
    private int maximumInventoryLines;
    private int pageSize;
    private long cooldownMillis;
    private String balanceCommand;
    private String inventoryCommand;
    private String marketCommand;
    private String marketTitle;
    private String serverName;

    public EconomyMarketModule(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String id() {
        return "economy-market";
    }

    @Override
    public void enable() {
        if (plugin.getStorage() == null) {
            throw new IllegalStateException("SQLite is unavailable; linked-account privacy checks cannot run");
        }
        FileConfiguration config = plugin.getAppConfig();
        requireLinked = config.getBoolean("economy-market.privacy.require-linked-account", true);
        ephemeral = config.getBoolean("economy-market.privacy.responses-ephemeral", true);
        exposeLore = config.getBoolean("economy-market.privacy.expose-item-lore", false);
        allowStaffLookup = config.getBoolean("economy-market.privacy.allow-staff-player-lookup", false);
        maximumInventoryLines = range(config.getInt(
                "economy-market.privacy.maximum-inventory-lines", 35), 9, 54,
                "economy-market.privacy.maximum-inventory-lines");
        cooldownMillis = Math.multiplyExact(range(config.getLong(
                "economy-market.commands.cooldown-seconds", 3L), 0L, 300L,
                "economy-market.commands.cooldown-seconds"), 1_000L);
        balanceCommand = command(config, "economy-market.commands.balance", "balance");
        inventoryCommand = command(config, "economy-market.commands.inventory", "inventory");
        marketCommand = command(config, "economy-market.commands.market", "market");
        if (List.of(balanceCommand, inventoryCommand, marketCommand).stream().distinct().count() != 3L) {
            throw new IllegalArgumentException("economy-market command names must be unique");
        }
        try {
            marketSource = MarketSource.valueOf(value(config,
                    "economy-market.market.source", "CONFIG").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("economy-market.market.source must be CONFIG or SERVICE", error);
        }
        pageSize = range(config.getInt("economy-market.market.page-size", 8), 1, 10,
                "economy-market.market.page-size");
        marketTitle = value(config, "economy-market.market.title", "%server_name% Market");
        serverName = plugin.getServer().getName();
        configuredListings = parseListings(config);
        links = new LinkedAccountRepository(plugin.getStorage());

        vault = VaultEconomy.resolve(plugin);
        if (vault == null) {
            throw new IllegalStateException("No Vault economy provider was found. Install Vault and an economy "
                    + "plugin, verify both are enabled, then run /coredsc reload.");
        }
        if (marketSource == MarketSource.SERVICE) {
            marketProvider = plugin.getServer().getServicesManager().load(EconomyMarketProvider.class);
            if (marketProvider == null) {
                throw new IllegalStateException("market.source is SERVICE, but no EconomyMarketProvider is "
                        + "registered. Install a shop adapter or choose CONFIG in modules/economy-market.yml.");
            }
        }

        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        active = true;
        discordListener = new ListenerAdapter() {
            @Override
            public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                if (!active) return;
                if (event.getName().equals(balanceCommand)) handleBalance(event);
                else if (event.getName().equals(inventoryCommand)) handleInventory(event);
                else if (event.getName().equals(marketCommand)) handleMarket(event);
            }
        };
        discord.addEventListener(discordListener);
    }

    @Override
    public void disable() {
        active = false;
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        marketProvider = null;
        configuredListings = List.of();
        vault = null;
        links = null;
        cooldowns.clear();
    }

    @Override
    public String statusDetail() {
        return "vault=" + (vault == null ? "unavailable" : vault.providerName())
                + ", market=" + marketSource;
    }

    @Override
    public List<CommandData> slashCommands() {
        OptionData player = new OptionData(OptionType.STRING, "player",
                "Staff lookup (requires Manage Server and explicit config opt-in)", false)
                .setMaxLength(64);
        return List.of(
                Commands.slash(balanceCommand, "Show your linked Minecraft economy balance")
                        .addOptions(player),
                Commands.slash(inventoryCommand, "Show a live snapshot of your Minecraft inventory")
                        .addOptions(new OptionData(OptionType.STRING, "player",
                                "Staff lookup (requires Manage Server and explicit config opt-in)", false)
                                .setMaxLength(64)),
                Commands.slash(marketCommand, "Browse current server market listings")
                        .addOptions(new OptionData(OptionType.INTEGER, "page", "Market page", false)
                                .setMinValue(1).setMaxValue(1000)));
    }

    private void handleBalance(SlashCommandInteractionEvent event) {
        if (!accept(event)) return;
        event.deferReply(ephemeral).queue(hook -> resolveTarget(event, false)
                .thenCompose(this::balanceSnapshot)
                .whenComplete((embed, error) -> {
                    if (error == null) {
                        plugin.recordFeatureUse("economy_balance");
                        hook.editOriginalEmbeds(embed).queue();
                    } else hook.editOriginal(userError(error)).queue();
                }));
    }

    private void handleInventory(SlashCommandInteractionEvent event) {
        if (!accept(event)) return;
        event.deferReply(ephemeral).queue(hook -> resolveTarget(event, true)
                .thenCompose(this::snapshotInventory)
                .whenComplete((snapshot, error) -> {
                    if (error == null) {
                        plugin.recordFeatureUse("economy_inventory");
                        hook.editOriginalEmbeds(inventoryEmbed(snapshot)).queue();
                    } else hook.editOriginal(userError(error)).queue();
                }));
    }

    private void handleMarket(SlashCommandInteractionEvent event) {
        if (!accept(event)) return;
        int page = event.getOption("page") == null ? 1 : event.getOption("page").getAsInt();
        event.deferReply(ephemeral).queue(hook -> listings()
                .thenApply(listings -> marketEmbed(listings, page))
                .whenComplete((embed, error) -> {
                    if (error == null) {
                        plugin.recordFeatureUse("economy_market");
                        hook.editOriginalEmbeds(embed).queue();
                    } else hook.editOriginal(userError(error)).queue();
                }));
    }

    private boolean accept(SlashCommandInteractionEvent event) {
        long now = System.currentTimeMillis();
        Long previous = cooldowns.put(event.getUser().getId(), now);
        if (previous != null && now - previous < cooldownMillis) {
            long remaining = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(cooldownMillis - (now - previous)) + 1L);
            event.reply("Please wait " + remaining + " second(s) before using another economy command.")
                    .setEphemeral(true).queue();
            return false;
        }
        if (cooldowns.size() > 10_000) cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMillis);
        return true;
    }

    private CompletableFuture<PlayerTarget> resolveTarget(
            SlashCommandInteractionEvent event,
            boolean mustBeOnline
    ) {
        String requested = event.getOption("player") == null
                ? "" : event.getOption("player").getAsString().trim();
        if (!requested.isBlank()) {
            boolean staff = event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
            if (!allowStaffLookup || !staff) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Named-player lookup is disabled or you do not have Manage Server."));
            }
            return resolveNamedTarget(requested, mustBeOnline);
        }
        if (!requireLinked) {
            // Personal economy data still needs an identity. Disabling the flag
            // permits staff lookups; it never guesses a Discord user's player.
        }
        return links.findByDiscordUserId(event.getUser().getId()).thenApply(link -> {
            if (link.isEmpty()) throw new CompletionException(new IllegalStateException(
                    "Link your Minecraft account with /link in game before using this command."));
            try {
                return new PlayerTarget(UUID.fromString(link.get().minecraftUuid()), link.get().minecraftName());
            } catch (IllegalArgumentException error) {
                throw new CompletionException(new IllegalStateException(
                        "Your stored link is invalid. Run /unlink and /link again, then retry.", error));
            }
        });
    }

    private CompletableFuture<InventorySnapshot> snapshotInventory(PlayerTarget target) {
        return plugin.callForPlayer(target.uuid(), player -> {
                List<String> lines = new ArrayList<>();
                int occupied = 0;
                for (ItemStack stack : player.getInventory().getStorageContents()) {
                    if (stack == null || stack.getType().isAir()) continue;
                    occupied++;
                    if (lines.size() < maximumInventoryLines) lines.add(itemLine(stack));
                }
                if (occupied > lines.size()) lines.add("… and " + (occupied - lines.size()) + " more occupied slot(s)");
                return new InventorySnapshot(player.getName(), List.copyOf(lines), occupied);
            }).thenCompose(snapshot -> snapshot
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                            "That player is offline. Inventory snapshots are only available while a player is online."))));
    }

    /**
     * Vault implementations commonly inspect an online Player internally. On
     * Folia that call must run on the player's owning entity scheduler. The
     * offline branch contains only an OfflinePlayer profile and executes on the
     * global scheduler; neither branch leaks a Bukkit object into a completion
     * stage or JDA thread.
     */
    private CompletableFuture<MessageEmbed> balanceSnapshot(PlayerTarget target) {
        return plugin.callForPlayer(target.uuid(), player -> {
                    PlayerTarget current = new PlayerTarget(player.getUniqueId(), player.getName());
                    return balanceEmbed(current, vault.balance(player));
                })
                .thenCompose(snapshot -> snapshot
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> plugin.callSync(() -> {
                            OfflinePlayer offline = Bukkit.getOfflinePlayer(target.uuid());
                            return balanceEmbed(target, vault.balance(offline));
                        })));
    }

    private CompletableFuture<PlayerTarget> resolveNamedTarget(String requested, boolean mustBeOnline) {
        return plugin.callSync(() -> {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(requested);
            if (cached == null && mustBeOnline) {
                throw new IllegalStateException(
                        "That player is offline. Inventory snapshots are only available while a player is online.");
            }
            if (cached == null) {
                @SuppressWarnings("deprecation")
                OfflinePlayer resolved = Bukkit.getOfflinePlayer(requested);
                cached = resolved;
            }
            if (!mustBeOnline && !cached.hasPlayedBefore()) {
                throw new IllegalStateException("No known player matches that name.");
            }
            return new PlayerTarget(cached.getUniqueId(),
                    Objects.requireNonNullElse(cached.getName(), requested));
        }).thenCompose(target -> {
            if (!mustBeOnline) return CompletableFuture.completedFuture(target);
            return plugin.callForPlayer(target.uuid(), player ->
                            new PlayerTarget(player.getUniqueId(), player.getName()))
                    .thenCompose(online -> online
                            .map(CompletableFuture::completedFuture)
                            .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                                    "That player is offline. Inventory snapshots are only available while a player is online."))));
        });
    }

    private String itemLine(ItemStack stack) {
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        String name = meta != null && meta.hasDisplayName()
                ? meta.getDisplayName() : prettify(stack.getType().name());
        StringBuilder line = new StringBuilder("**").append(TextUtil.truncate(
                TextUtil.sanitizeMassMentions(name), 80)).append("** × ").append(stack.getAmount());
        if (exposeLore && meta != null && meta.hasLore() && meta.getLore() != null) {
            String lore = meta.getLore().stream().filter(Objects::nonNull)
                    .map(TextUtil::sanitizeMassMentions).map(String::trim).filter(value -> !value.isBlank())
                    .limit(2).reduce((left, right) -> left + " · " + right).orElse("");
            if (!lore.isBlank()) line.append(" — ").append(TextUtil.truncate(lore, 160));
        }
        return line.toString();
    }

    private CompletableFuture<List<EconomyMarketProvider.MarketListing>> listings() {
        if (marketSource == MarketSource.CONFIG) return CompletableFuture.completedFuture(configuredListings);
        EconomyMarketProvider current = marketProvider;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Market provider is unavailable."));
        return current.listings().thenApply(list -> list == null ? List.of() : list.stream()
                .filter(Objects::nonNull).sorted(Comparator.comparing(EconomyMarketProvider.MarketListing::name,
                        String.CASE_INSENSITIVE_ORDER)).limit(1_000).toList());
    }

    private MessageEmbed balanceEmbed(PlayerTarget target, double balance) {
        return new EmbedBuilder().setColor(0x57F287)
                .setTitle("💰 " + TextUtil.truncate(TextUtil.sanitizeMassMentions(target.name()), 220))
                .setDescription("Current balance: **" + TextUtil.sanitizeMassMentions(vault.format(balance)) + "**")
                .setFooter("Live Vault balance · " + vault.providerName())
                .setTimestamp(Instant.now()).build();
    }

    private MessageEmbed inventoryEmbed(InventorySnapshot snapshot) {
        String body = snapshot.lines().isEmpty() ? "Inventory is empty." : String.join("\n", snapshot.lines());
        return new EmbedBuilder().setColor(0x3498DB)
                .setTitle("🎒 " + TextUtil.truncate(TextUtil.sanitizeMassMentions(snapshot.playerName()), 220))
                .setDescription(TextUtil.truncate(body, 4096))
                .setFooter(snapshot.occupiedSlots() + "/36 storage slots occupied · live snapshot")
                .setTimestamp(Instant.now()).build();
    }

    private MessageEmbed marketEmbed(List<EconomyMarketProvider.MarketListing> listings, int requestedPage) {
        int pages = Math.max(1, (listings.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int from = Math.min(listings.size(), (page - 1) * pageSize);
        int to = Math.min(listings.size(), from + pageSize);
        EmbedBuilder embed = new EmbedBuilder().setColor(0xFEE75C)
                .setTitle(TextUtil.truncate(TextUtil.replace(marketTitle,
                        java.util.Map.of("server_name", serverName)), 256));
        if (listings.isEmpty()) {
            embed.setDescription("No market listings are available right now.");
        } else {
            for (EconomyMarketProvider.MarketListing listing : listings.subList(from, to)) {
                String currency = blankTo(listing.currency(), vault.currencyNamePlural());
                String price = String.format(Locale.ROOT, "%,.2f %s", listing.price(), currency);
                String description = TextUtil.truncate(TextUtil.sanitizeMassMentions(blankTo(
                        listing.description(), "No description")), 650);
                String hint = TextUtil.sanitizeMassMentions(blankTo(listing.purchaseHint(), ""));
                if (!hint.isBlank()) description += "\n`" + TextUtil.truncate(hint.replace('`', '\''), 200) + "`";
                embed.addField(TextUtil.truncate(TextUtil.sanitizeMassMentions(
                        blankTo(listing.name(), listing.id())), 256), "**" + price + "**\n" + description, false);
            }
        }
        return embed.setFooter("Page " + page + "/" + pages + " · "
                        + (marketSource == MarketSource.SERVICE ? marketProvider.providerId() : "configured market"))
                .setTimestamp(Instant.now()).build();
    }

    private static List<EconomyMarketProvider.MarketListing> parseListings(FileConfiguration config) {
        List<EconomyMarketProvider.MarketListing> parsed = new ArrayList<>();
        for (java.util.Map<?, ?> raw : config.getMapList("economy-market.market.listings")) {
            String id = text(raw.get("id"));
            String name = text(raw.get("name"));
            if (id.isBlank() || name.isBlank()) continue;
            double price = number(raw.get("price"));
            if (!Double.isFinite(price) || price < 0.0D) {
                throw new IllegalArgumentException("economy-market listing '" + id + "' has an invalid price");
            }
            parsed.add(new EconomyMarketProvider.MarketListing(id, name, text(raw.get("description")),
                    price, text(raw.get("currency")), text(raw.get("icon-url")),
                    text(raw.get("purchase-hint"))));
        }
        return List.copyOf(parsed);
    }

    private static String userError(Throwable error) {
        return "Could not complete that request: " + rootMessage(error);
    }

    private static String command(FileConfiguration config, String path, String fallback) {
        String command = value(config, path, fallback).toLowerCase(Locale.ROOT);
        if (!COMMAND.matcher(command).matches()) throw new IllegalArgumentException(path + " must match " + COMMAND.pattern());
        return command;
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured.trim();
    }

    private static String prettify(String material) {
        String[] words = material.toLowerCase(Locale.ROOT).split("_");
        StringBuilder value = new StringBuilder();
        for (String word : words) {
            if (!value.isEmpty()) value.append(' ');
            value.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return value.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static int range(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static long range(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable == null ? new IllegalStateException("unknown error") : throwable;
        while ((current instanceof CompletionException || current instanceof InvocationTargetException)
                && current.getCause() != null) current = current.getCause();
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /** Reflection keeps Vault optional while retaining the canonical Vault service contract. */
    private static final class VaultEconomy {
        private final Object provider;
        private final Method getBalance;
        private final Method format;
        private final Method currencyNamePlural;

        private VaultEconomy(Object provider, Class<?> api) throws NoSuchMethodException {
            this.provider = provider;
            getBalance = api.getMethod("getBalance", OfflinePlayer.class);
            format = api.getMethod("format", double.class);
            currencyNamePlural = api.getMethod("currencyNamePlural");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static VaultEconomy resolve(CoreDSCPlugin plugin) {
            try {
                Class<?> api = Class.forName("net.milkbowl.vault.economy.Economy", false,
                        plugin.getClass().getClassLoader());
                RegisteredServiceProvider registration = plugin.getServer().getServicesManager()
                        .getRegistration((Class) api);
                if (registration == null || registration.getProvider() == null) return null;
                return new VaultEconomy(registration.getProvider(), api);
            } catch (ClassNotFoundException error) {
                return null;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Vault's Economy API is incompatible. Update Vault and your "
                        + "economy plugin, then restart the server.", error);
            }
        }

        private double balance(OfflinePlayer player) {
            Object value = invoke(getBalance, player);
            if (!(value instanceof Number number)) throw new IllegalStateException(
                    "Vault economy provider returned a non-numeric balance");
            return number.doubleValue();
        }

        private String format(double value) {
            Object rendered = invoke(format, value);
            return rendered == null ? String.format(Locale.ROOT, "%,.2f", value) : String.valueOf(rendered);
        }

        private String currencyNamePlural() {
            Object name = invoke(currencyNamePlural);
            return name == null || String.valueOf(name).isBlank() ? "currency" : String.valueOf(name);
        }

        private String providerName() {
            return provider.getClass().getSimpleName();
        }

        private Object invoke(Method method, Object... arguments) {
            try {
                return method.invoke(provider, arguments);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Vault denied access to " + method.getName(), error);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IllegalStateException("Vault provider " + providerName() + " failed during "
                        + method.getName() + ": " + rootMessage(cause), cause);
            }
        }
    }
}
