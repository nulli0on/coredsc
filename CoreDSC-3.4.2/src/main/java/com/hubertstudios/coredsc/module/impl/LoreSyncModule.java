package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.scheduler.CommandReplyTarget;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Configured, cinematic NPC personas delivered through reusable Discord webhooks. */
public final class LoreSyncModule implements CoreModule {
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9_-]{1,40}");

    private record Profile(
            String id,
            String channelId,
            String displayName,
            String avatarUrl,
            int color,
            String title,
            String description,
            String thumbnailUrl,
            String imageUrl,
            String footer
    ) { }

    private final CoreDSCPlugin plugin;
    private final Map<String, CompletableFuture<Webhook>> webhooks = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private Map<String, Profile> profiles = Map.of();
    private boolean active;
    private boolean autoCreate;
    private boolean fallbackToBot;
    private boolean allowConsole;
    private int maximumMessageLength;
    private long cooldownMillis;
    private String permission;
    private String webhookNamePrefix;
    private String serverName;

    public LoreSyncModule(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String id() {
        return "lore-sync";
    }

    @Override
    public void enable() {
        FileConfiguration config = plugin.getAppConfig();
        String defaultChannel = value(config, "lore-sync.default-channel-id", "");
        autoCreate = config.getBoolean("lore-sync.webhooks.auto-create", true);
        fallbackToBot = config.getBoolean("lore-sync.webhooks.fallback-to-bot", true);
        allowConsole = config.getBoolean("lore-sync.security.allow-console", true);
        webhookNamePrefix = TextUtil.truncate(value(config,
                "lore-sync.webhooks.name-prefix", "CoreDSC NPC"), 60);
        permission = value(config, "lore-sync.security.permission", "coredsc.lore.trigger");
        if (permission.isBlank()) throw new IllegalArgumentException(
                "lore-sync.security.permission cannot be blank");
        maximumMessageLength = range(config.getInt(
                "lore-sync.security.maximum-message-length", 1500), 1, 3_500,
                "lore-sync.security.maximum-message-length");
        cooldownMillis = Math.multiplyExact(range(config.getLong(
                "lore-sync.security.cooldown-seconds", 2L), 0L, 300L,
                "lore-sync.security.cooldown-seconds"), 1_000L);
        serverName = plugin.getServer().getName();
        profiles = parseProfiles(config, defaultChannel);
        if (profiles.isEmpty()) throw new IllegalArgumentException(
                "lore-sync is enabled but has no enabled profiles. Add one in modules/lore-sync.yml.");

        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        PluginCommand command = plugin.getCommand("lore");
        if (command == null) throw new IllegalStateException("The lore command is missing from plugin.yml");
        active = true;
        command.setExecutor((sender, ignored, label, arguments) -> execute(sender, label, arguments));
        command.setTabCompleter((sender, ignored, alias, arguments) -> arguments.length == 1
                ? profiles.keySet().stream().filter(id -> id.startsWith(arguments[0].toLowerCase(Locale.ROOT))).toList()
                : List.of());
    }

    @Override
    public void disable() {
        active = false;
        webhooks.clear();
        cooldowns.clear();
        profiles = Map.of();
        PluginCommand command = plugin.getCommand("lore");
        if (command != null) {
            command.setExecutor((sender, ignored, label, arguments) -> {
                sender.sendMessage("§cLore Sync is disabled. Enable modules/lore-sync.yml and reload CoreDSC.");
                return true;
            });
            command.setTabCompleter(null);
        }
    }

    @Override
    public String statusDetail() {
        return "profiles=" + profiles.size() + ", webhooks=" + (autoCreate ? "managed" : "existing-only");
    }

    /** Public module operation used by the CoreDSC developer API. */
    public CompletableFuture<Boolean> trigger(String profileId, String message, String actor) {
        if (!active) return CompletableFuture.completedFuture(false);
        String normalizedId = profileId == null ? "" : profileId.trim().toLowerCase(Locale.ROOT);
        Profile profile = profiles.get(normalizedId);
        if (profile == null) return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unknown lore profile '" + normalizedId + "'. Available profiles: " + String.join(", ", profiles.keySet())));
        String cleanMessage = TextUtil.truncate(TextUtil.sanitizeMassMentions(
                TextUtil.singleLine(message)), maximumMessageLength);
        if (cleanMessage.isBlank()) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Lore message cannot be blank"));
        String cleanActor = TextUtil.truncate(TextUtil.sanitizeMassMentions(
                TextUtil.singleLine(actor)), 80);
        if (cleanActor.isBlank()) cleanActor = "CoreDSC API";
        if (!takeCooldown(cleanActor.toLowerCase(Locale.ROOT))) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Lore trigger is cooling down; wait a moment and retry."));
        }
        return publish(profile, cleanMessage, cleanActor);
    }

    private boolean execute(CommandSender sender, String label, String[] arguments) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§cYou need §f" + permission + "§c to trigger lore events.");
            return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player) && !allowConsole) {
            sender.sendMessage("§cConsole lore triggers are disabled in modules/lore-sync.yml.");
            return true;
        }
        if (arguments.length < 2) {
            sender.sendMessage("§e/" + label + " <profile> <message>");
            sender.sendMessage("§7Profiles: " + String.join(", ", profiles.keySet()));
            return true;
        }
        String profile = arguments[0];
        String message = String.join(" ", java.util.Arrays.copyOfRange(arguments, 1, arguments.length));
        String actor = sender.getName();
        CommandReplyTarget replyTarget = CommandReplyTarget.capture(plugin, sender);
        trigger(profile, message, actor).whenComplete((sent, error) ->
                replyTarget.send(error == null && Boolean.TRUE.equals(sent)
                        ? "§aLore event sent as §f" + profile + "§a."
                        : "§cLore event failed: " + rootMessage(error)));
        return true;
    }

    private CompletableFuture<Boolean> publish(Profile profile, String message, String actor) {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(profile.channelId());
        if (channel == null || !channel.canTalk()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Profile '" + profile.id() + "' channel is unavailable or not writable. "
                            + "Remap it in WebEditor and grant View Channel, Send Messages and Embed Links."));
        }
        MessageEmbed embed = render(profile, message, actor);
        return resolveWebhook(channel, profile).handle((webhook, resolutionError) -> {
            if (resolutionError == null && webhook != null) {
                try {
                    var action = webhook.sendMessageEmbeds(embed)
                            .setUsername(profile.displayName())
                            .setAllowedMentions(Collections.emptyList());
                    if (validHttpsUrl(profile.avatarUrl())) action.setAvatarUrl(profile.avatarUrl());
                    return action.submit().thenApply(ignored -> true);
                } catch (RuntimeException error) {
                    return CompletableFuture.<Boolean>failedFuture(error);
                }
            }
            if (!fallbackToBot) return CompletableFuture.<Boolean>failedFuture(unwrap(resolutionError));
            return channel.sendMessageEmbeds(embed).setAllowedMentions(Collections.emptyList()).submit()
                    .thenApply(ignored -> true);
        }).thenCompose(future -> future).whenComplete((sent, error) -> {
            if (error == null && Boolean.TRUE.equals(sent)) {
                plugin.recordFeatureUse("lore_event");
            } else if (error != null) plugin.recordModuleFailure("lore-sync", rootMessage(error));
        });
    }

    private CompletableFuture<Webhook> resolveWebhook(TextChannel channel, Profile profile) {
        String key = channel.getId();
        return webhooks.computeIfAbsent(key, ignored -> channel.retrieveWebhooks().submit()
                .thenCompose(available -> {
                    long selfId = channel.getJDA().getSelfUser().getIdLong();
                    Webhook selected = available.stream()
                            .filter(item -> Objects.equals(item.getName(), webhookName(profile)))
                            .filter(item -> item.getOwnerAsUser() != null
                                    && item.getOwnerAsUser().getIdLong() == selfId)
                            .filter(item -> item.getToken() != null && !item.getToken().isBlank())
                            .findFirst().orElse(null);
                    if (selected != null) return CompletableFuture.completedFuture(selected);
                    if (!autoCreate) return CompletableFuture.failedFuture(new IllegalStateException(
                            "No CoreDSC webhook exists in #" + channel.getName() + " and auto-create is disabled."));
                    return channel.createWebhook(webhookName(profile)).submit();
                }).whenComplete((ignoredWebhook, error) -> {
                    if (error != null) webhooks.remove(key);
                }));
    }

    private MessageEmbed render(Profile profile, String message, String actor) {
        Map<String, Object> values = Map.of(
                "server_name", serverName,
                "message", message,
                "actor", actor,
                "profile", profile.id());
        EmbedBuilder embed = new EmbedBuilder().setColor(profile.color())
                .setDescription(TextUtil.truncate(TextUtil.sanitizeMassMentions(
                        TextUtil.replace(profile.description(), values)), 4096))
                .setTimestamp(Instant.now());
        String title = TextUtil.replace(profile.title(), values);
        if (!title.isBlank()) embed.setTitle(TextUtil.truncate(TextUtil.sanitizeMassMentions(title), 256));
        String footer = TextUtil.replace(profile.footer(), values);
        if (!footer.isBlank()) embed.setFooter(TextUtil.truncate(TextUtil.sanitizeMassMentions(footer), 2048));
        if (validHttpsUrl(profile.thumbnailUrl())) embed.setThumbnail(profile.thumbnailUrl());
        if (validHttpsUrl(profile.imageUrl())) embed.setImage(profile.imageUrl());
        return embed.build();
    }

    private boolean takeCooldown(String key) {
        long now = System.currentTimeMillis();
        Long previous = cooldowns.put(key, now);
        if (cooldowns.size() > 10_000) cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMillis);
        return previous == null || now - previous >= cooldownMillis;
    }

    private String webhookName(Profile profile) {
        return TextUtil.truncate(webhookNamePrefix + " " + profile.id(), 80);
    }

    private static Map<String, Profile> parseProfiles(FileConfiguration config, String defaultChannel) {
        Map<String, Profile> parsed = new LinkedHashMap<>();
        List<Map<?, ?>> entries = new ArrayList<>(config.getMapList("lore-sync.profiles"));
        for (Map<?, ?> raw : entries) {
            if (!booleanValue(raw.get("enabled"), true)) continue;
            String id = text(raw.get("id")).toLowerCase(Locale.ROOT);
            if (!PROFILE_ID.matcher(id).matches()) throw new IllegalArgumentException(
                    "lore-sync profile id '" + id + "' must match " + PROFILE_ID.pattern());
            if (parsed.containsKey(id)) throw new IllegalArgumentException(
                    "Duplicate lore-sync profile id: " + id);
            String channel = blankTo(text(raw.get("channel-id")), defaultChannel);
            if (!TextUtil.isPositiveSnowflake(channel)) throw new IllegalArgumentException(
                    "lore-sync profile '" + id + "' needs a channel. Map it in WebEditor.");
            String displayName = TextUtil.truncate(blankTo(text(raw.get("display-name")), id), 80);
            String avatar = optionalHttps(text(raw.get("avatar-url")), "profiles." + id + ".avatar-url");
            String thumbnail = optionalHttps(text(raw.get("thumbnail-url")), "profiles." + id + ".thumbnail-url");
            String image = optionalHttps(text(raw.get("image-url")), "profiles." + id + ".image-url");
            parsed.put(id, new Profile(id, channel, displayName, avatar,
                    parseColor(text(raw.get("color")), "profiles." + id + ".color"),
                    text(raw.get("title")), blankTo(text(raw.get("description")), "%message%"),
                    thumbnail, image, text(raw.get("footer"))));
        }
        return Map.copyOf(parsed);
    }

    private static int parseColor(String input, String path) {
        String value = blankTo(input, "#9B59B6").replace("#", "");
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException(
                "lore-sync." + path + " must be a six-digit hex color such as #9B59B6");
        return Integer.parseInt(value, 16);
    }

    private static String optionalHttps(String value, String path) {
        if (value.isBlank()) return "";
        if (!validHttpsUrl(value)) throw new IllegalArgumentException(
                "lore-sync." + path + " must be blank or an https:// URL");
        return value;
    }

    private static boolean validHttpsUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2_048) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured.trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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

    private static Throwable unwrap(Throwable error) {
        Throwable current = error == null ? new IllegalStateException("unknown error") : error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = unwrap(error);
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
