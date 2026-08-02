package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.api.CompetitiveRatingProvider;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.listener.PlayerDeathListener;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.scheduler.CoreTask;
import com.hubertstudios.coredsc.storage.CompetitiveRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;


public final class CompetitiveModule implements CoreModule, DiscordCommandContributor {
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]{1,32}");

    public record RatingView(
            UUID uuid,
            String name,
            int rating,
            int wins,
            int losses,
            int kills,
            int deaths,
            int matches
    ) { }

    private enum Source { BUILT_IN, SERVICE }

    private final CoreDSCPlugin plugin;
    private final AtomicBoolean leaderboardUpdateInFlight = new AtomicBoolean();
    private final AtomicLong lastWarning = new AtomicLong();
    private CompetitiveRepository ratings;
    private LinkedAccountRepository links;
    private CompetitiveRatingProvider provider;
    private Source source;
    private Listener bukkitListener;
    private ListenerAdapter discordListener;
    private CoreTask leaderboardTask;
    private boolean active;
    private boolean trackDeaths;
    private boolean notifyRatingChanges;
    private boolean responsesEphemeral;
    private boolean leaderboardEnabled;
    private int initialRating;
    private int normalK;
    private int provisionalK;
    private int provisionalMatches;
    private int minimumRating;
    private int leaderboardSize;
    private int minimumMatches;
    private String channelId;
    private String eloCommand;
    private String leaderboardCommand;
    private String leaderboardTitle;
    private String serverName;

    public CompetitiveModule(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String id() {
        return "competitive";
    }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite is not ready; competitive ratings cannot start");
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        FileConfiguration config = plugin.getAppConfig();
        try {
            source = Source.valueOf(value(config, "competitive.rating.source", "BUILT_IN")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "competitive.rating.source must be BUILT_IN or SERVICE", error);
        }
        initialRating = range(config.getInt("competitive.rating.initial", 1000), 100, 10_000,
                "competitive.rating.initial");
        normalK = range(config.getInt("competitive.rating.k-factor", 32), 1, 200,
                "competitive.rating.k-factor");
        provisionalK = range(config.getInt("competitive.rating.provisional-k-factor", 48), 1, 300,
                "competitive.rating.provisional-k-factor");
        provisionalMatches = range(config.getInt("competitive.rating.provisional-matches", 10), 0, 100,
                "competitive.rating.provisional-matches");
        minimumRating = range(config.getInt("competitive.rating.minimum", 100), 0, initialRating,
                "competitive.rating.minimum");
        trackDeaths = config.getBoolean("competitive.rating.track-player-deaths", true);
        notifyRatingChanges = config.getBoolean("competitive.rating.notify-rating-changes", false);
        channelId = value(config, "competitive.discord.channel-id", "");
        eloCommand = command(config, "competitive.discord.elo-command", "elo");
        leaderboardCommand = command(config, "competitive.discord.leaderboard-command", "leaderboard");
        if (eloCommand.equals(leaderboardCommand)) {
            throw new IllegalArgumentException("competitive Discord command names must be different");
        }
        responsesEphemeral = config.getBoolean("competitive.discord.responses-ephemeral", false);
        leaderboardEnabled = config.getBoolean("competitive.leaderboard.enabled", true);
        leaderboardTitle = value(config, "competitive.leaderboard.title",
                "%server_name% Competitive Rankings");
        leaderboardSize = range(config.getInt("competitive.leaderboard.size", 10), 3, 25,
                "competitive.leaderboard.size");
        minimumMatches = range(config.getInt("competitive.leaderboard.minimum-matches", 1), 0, 10_000,
                "competitive.leaderboard.minimum-matches");
        serverName = plugin.getServer().getName();
        if (leaderboardEnabled && !TextUtil.isPositiveSnowflake(channelId)) {
            throw new IllegalArgumentException("competitive.discord.channel-id must be mapped before enabling "
                    + "the auto-updating leaderboard; choose it in the WebEditor Channel Mapping page");
        }

        ratings = new CompetitiveRepository(storage);
        links = new LinkedAccountRepository(storage);
        if (source == Source.SERVICE) {
            provider = plugin.getServer().getServicesManager().load(CompetitiveRatingProvider.class);
            if (provider == null) {
                throw new IllegalStateException("competitive.rating.source is SERVICE, but no "
                        + "CompetitiveRatingProvider is registered. Install an adapter or use BUILT_IN.");
            }
        }

        active = true;
        if (source == Source.BUILT_IN && trackDeaths) {
            bukkitListener = new PlayerDeathListener(
                    plugin.getCoreScheduler(),
                    () -> active,
                    this::processPlayerDeath,
                    error -> warn("Could not record PvP result: " + rootMessage(error)));
            plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);
        }

        discordListener = new ListenerAdapter() {
            @Override
            public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                if (!active) return;
                if (event.getName().equals(eloCommand)) handleElo(event);
                else if (event.getName().equals(leaderboardCommand)) handleLeaderboard(event);
            }
        };
        discord.addEventListener(discordListener);

        if (leaderboardEnabled) {
            long seconds = range(config.getLong("competitive.leaderboard.update-interval-seconds", 300L),
                    60L, 86_400L, "competitive.leaderboard.update-interval-seconds");
            long ticks = Math.multiplyExact(seconds, 20L);
            leaderboardTask = plugin.getCoreScheduler().runGlobalTimer(
                    this::updateLeaderboardSafely, 40L, ticks);
        }
    }

    @Override
    public void disable() {
        active = false;
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        if (bukkitListener != null) {
            HandlerList.unregisterAll(bukkitListener);
            bukkitListener = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        provider = null;
        ratings = null;
        links = null;
        leaderboardUpdateInFlight.set(false);
    }

    @Override
    public String statusDetail() {
        return "source=" + source + ", auto-leaderboard=" + leaderboardEnabled;
    }

    @Override
    public List<CommandData> slashCommands() {
        return List.of(
                Commands.slash(eloCommand, "Show a player's competitive rating")
                        .addOptions(new OptionData(OptionType.STRING, "player",
                                "Minecraft player name; omit for your linked account", false)
                                .setMaxLength(64)),
                Commands.slash(leaderboardCommand, "Show the competitive leaderboard"));
    }

    public CompletableFuture<RatingView> recordResult(
            UUID winner,
            String winnerName,
            UUID loser,
            String loserName,
            boolean combatKill
    ) {
        return persistResult(winner, winnerName, loser, loserName,
                combatKill, System.currentTimeMillis()).thenApply(result -> view(result.winner()));
    }

    private CompletableFuture<CompetitiveRepository.MatchResult> persistResult(
            UUID winner,
            String winnerName,
            UUID loser,
            String loserName,
            boolean combatKill,
            long occurredAt
    ) {
        if (!active || source != Source.BUILT_IN || ratings == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Built-in competitive rating storage is not active"));
        }
        return ratings.recordWin(winner, winnerName, loser, loserName,
                        initialRating, normalK, provisionalK, provisionalMatches,
                        minimumRating, combatKill, occurredAt)
                .thenApply(result -> {
                    plugin.recordFeatureUse("competitive_match");
                    if (leaderboardEnabled) updateLeaderboardSafely();
                    return result;
                });
    }

    private CompletableFuture<PlayerDeathListener.DeathProcessingResult> processPlayerDeath(
            PlayerDeathListener.DeathSnapshot death
    ) {
        return persistResult(
                death.winnerId(), death.winnerName(),
                death.loserId(), death.loserName(),
                true, death.occurredAt()).thenApply(result -> {
                    if (!notifyRatingChanges) {
                        return PlayerDeathListener.DeathProcessingResult.silent();
                    }
                    String winnerMessage = "§aPvP rating: §f" + result.winner().rating()
                            + " §8(+" + result.winnerDelta() + ")";
                    String loserMessage = "§cPvP rating: §f" + result.loser().rating()
                            + " §8(" + result.loserDelta() + ")";
                    return new PlayerDeathListener.DeathProcessingResult(
                            winnerMessage, loserMessage);
                });
    }

    public CompletableFuture<List<RatingView>> leaderboard(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (!active) return CompletableFuture.completedFuture(List.of());
        if (source == Source.SERVICE) {
            CompetitiveRatingProvider current = provider;
            if (current == null) return CompletableFuture.completedFuture(List.of());
            return current.leaderboard(safeLimit).thenApply(list -> list.stream()
                    .map(CompetitiveModule::view).toList());
        }
        CompetitiveRepository repository = ratings;
        return repository == null ? CompletableFuture.completedFuture(List.of())
                : repository.top(safeLimit, minimumMatches)
                .thenApply(list -> list.stream().map(CompetitiveModule::view).toList());
    }

    private void handleElo(SlashCommandInteractionEvent event) {
        event.deferReply(responsesEphemeral).queue(hook -> {
            String requested = event.getOption("player") == null
                    ? "" : event.getOption("player").getAsString().trim();
            CompletableFuture<Optional<RatingView>> lookup;
            if (!requested.isBlank()) {
                lookup = findByName(requested);
            } else {
                lookup = links.findByDiscordUserId(event.getUser().getId()).thenCompose(link -> {
                    if (link.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
                    UUID uuid = UUID.fromString(link.get().minecraftUuid());
                    return findByUuid(uuid);
                });
            }
            lookup.whenComplete((found, error) -> {
                if (error != null) {
                    hook.editOriginal("Rating lookup failed: " + rootMessage(error)).queue();
                } else if (found.isEmpty()) {
                    hook.editOriginal(requested.isBlank()
                            ? "Link your Minecraft account first, or provide a player name."
                            : "No competitive rating exists for that player.").queue();
                } else {
                    hook.editOriginalEmbeds(playerEmbed(found.get())).queue();
                }
            });
        });
    }

    private void handleLeaderboard(SlashCommandInteractionEvent event) {
        event.deferReply(responsesEphemeral).queue(hook -> leaderboard(leaderboardSize)
                .whenComplete((entries, error) -> {
                    if (error != null) hook.editOriginal("Leaderboard lookup failed: " + rootMessage(error)).queue();
                    else hook.editOriginalEmbeds(leaderboardEmbed(entries)).queue();
                }));
    }

    private CompletableFuture<Optional<RatingView>> findByUuid(UUID uuid) {
        if (source == Source.SERVICE) {
            return provider.rating(uuid).thenApply(optional -> optional.map(CompetitiveModule::view));
        }
        return ratings.find(uuid).thenApply(optional -> optional.map(CompetitiveModule::view));
    }

    private CompletableFuture<Optional<RatingView>> findByName(String name) {
        if (source == Source.BUILT_IN) {
            return ratings.findByName(name).thenApply(optional -> optional.map(CompetitiveModule::view));
        }
        return provider.leaderboard(100).thenApply(entries -> entries.stream()
                .filter(entry -> entry.minecraftName().equalsIgnoreCase(name))
                .findFirst().map(CompetitiveModule::view));
    }

    private void updateLeaderboardSafely() {
        if (!active || !leaderboardEnabled || !leaderboardUpdateInFlight.compareAndSet(false, true)) return;
        leaderboard(leaderboardSize).thenCompose(this::publishLeaderboard)
                .whenComplete((ignored, error) -> {
                    leaderboardUpdateInFlight.set(false);
                    if (error != null && active) warn("Leaderboard update failed: " + rootMessage(error));
                });
    }

    private CompletableFuture<Void> publishLeaderboard(List<RatingView> entries) {
        DiscordBotService service = plugin.getDiscordService();
        JDA jda = service == null ? null : service.getJda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The competitive channel is unavailable or the bot cannot send there. "
                            + "Remap it in WebEditor and grant View Channel, Send Messages and Embed Links."));
        }
        MessageEmbed embed = leaderboardEmbed(entries);
        return ratings.leaderboardMessage(channelId).thenCompose(stored -> {
            if (stored.isEmpty()) return createLeaderboardMessage(channel, embed);
            return channel.retrieveMessageById(stored.get()).submit()
                    .thenCompose(message -> message.editMessageEmbeds(embed)
                            .setAllowedMentions(Collections.emptyList()).submit())
                    .thenApply(ignored -> (Void) null)
                    .exceptionallyCompose(error -> isUnknownMessage(error)
                            ? ratings.deleteLeaderboardMessage(channelId)
                            .thenCompose(ignored -> createLeaderboardMessage(channel, embed))
                            : CompletableFuture.failedFuture(unwrap(error)));
        });
    }

    private CompletableFuture<Void> createLeaderboardMessage(TextChannel channel, MessageEmbed embed) {
        return channel.sendMessageEmbeds(embed).setAllowedMentions(Collections.emptyList()).submit()
                .thenCompose(message -> ratings.saveLeaderboardMessage(
                        channelId, message.getId(), System.currentTimeMillis()))
                .thenRun(() -> plugin.recordFeatureUse("competitive_leaderboard"));
    }

    private MessageEmbed playerEmbed(RatingView rating) {
        double winRate = rating.matches() == 0 ? 0.0D : rating.wins() * 100.0D / rating.matches();
        return new EmbedBuilder()
                .setColor(0x5865F2)
                .setTitle("⚔ " + rating.name() + " · " + rating.rating() + " ELO")
                .addField("Record", rating.wins() + "W · " + rating.losses() + "L", true)
                .addField("Win rate", String.format(Locale.ROOT, "%.1f%%", winRate), true)
                .addField("PvP", rating.kills() + " kills · " + rating.deaths() + " deaths", true)
                .setFooter("CoreDSC Competitive")
                .setTimestamp(Instant.now())
                .build();
    }

    private MessageEmbed leaderboardEmbed(List<RatingView> entries) {
        StringBuilder chart = new StringBuilder();
        int maximum = entries.stream().mapToInt(RatingView::rating).max().orElse(initialRating);
        for (int index = 0; index < entries.size(); index++) {
            RatingView entry = entries.get(index);
            String medal = switch (index) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> "`" + String.format(Locale.ROOT, "%02d", index + 1) + "`";
            };
            int bars = Math.max(1, (int) Math.round(entry.rating() * 10.0D / Math.max(1, maximum)));
            chart.append(medal).append(" **").append(TextUtil.sanitizeMassMentions(entry.name()))
                    .append("**  `").append(entry.rating()).append(" ELO`\n")
                    .append("　").append("▰".repeat(bars)).append("▱".repeat(10 - bars))
                    .append("　").append(entry.wins()).append("W/").append(entry.losses()).append("L\n");
        }
        if (chart.isEmpty()) chart.append("No ranked matches have been recorded yet.");
        String title = TextUtil.replace(leaderboardTitle,
                java.util.Map.of("server_name", serverName));
        return new EmbedBuilder()
                .setColor(0xF1C40F)
                .setTitle(TextUtil.truncate(title, 256))
                .setDescription(TextUtil.truncate(chart.toString(), 4096))
                .setFooter("Auto-updated · " + (source == Source.SERVICE
                        ? provider.providerId() : "CoreDSC built-in ELO"))
                .setTimestamp(Instant.now())
                .build();
    }

    private void warn(String message) {
        plugin.recordModuleFailure("competitive", message);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous >= 60_000L && lastWarning.compareAndSet(previous, now)) {
            plugin.getLogger().warning("[Competitive] " + message);
        }
    }

    private static boolean isUnknownMessage(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof ErrorResponseException response
                && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static RatingView view(CompetitiveRepository.Rating rating) {
        return new RatingView(rating.minecraftUuid(), rating.minecraftName(), rating.rating(),
                rating.wins(), rating.losses(), rating.kills(), rating.deaths(), rating.matches());
    }

    private static RatingView view(CompetitiveRatingProvider.Rating rating) {
        return new RatingView(rating.minecraftUuid(), rating.minecraftName(), rating.rating(),
                rating.wins(), rating.losses(), 0, 0, rating.matches());
    }

    private static String command(FileConfiguration config, String path, String fallback) {
        String command = value(config, path, fallback).toLowerCase(Locale.ROOT);
        if (!COMMAND.matcher(command).matches()) {
            throw new IllegalArgumentException(path + " must match " + COMMAND.pattern());
        }
        return command;
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured.trim();
    }

    private static int range(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static long range(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = unwrap(Objects.requireNonNullElseGet(
                throwable, () -> new IllegalStateException("unknown error")));
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
