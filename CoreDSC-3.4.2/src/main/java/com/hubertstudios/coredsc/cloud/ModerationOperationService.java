package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.impl.CaseModule;
import com.hubertstudios.coredsc.scripting.MiniJson;
import com.hubertstudios.coredsc.storage.CloudOperationRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.UserSnowflake;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed cross-platform moderation with identity resolution, hierarchy checks, and expiry state. */
public final class ModerationOperationService {
    private record Target(String minecraftUuid, String minecraftName, String discordUserId) { }
    private record Outcome(String platform, boolean success, String detail) { }
    private record ParsedDuration(String source, long milliseconds, boolean permanent) { }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,5})([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final long MAXIMUM_DURATION_MILLIS = Duration.ofDays(3650).toMillis();

    private final CoreDSCPlugin plugin;
    private final CloudOperationRepository repository;
    private final LinkedAccountRepository links;
    private final Set<String> protectedMinecraftNames;
    private final Set<String> protectedMinecraftUuids;
    private final Set<String> protectedDiscordIds;

    public ModerationOperationService(CoreDSCPlugin plugin, CloudOperationRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.links = new LinkedAccountRepository(plugin.getStorage());
        this.protectedMinecraftNames = upperSet(plugin.getAppConfig().getStringList(
                "cloud-control.moderation.protected-minecraft-names"));
        this.protectedMinecraftUuids = lowerSet(plugin.getAppConfig().getStringList(
                "cloud-control.moderation.protected-minecraft-uuids"));
        this.protectedDiscordIds = Set.copyOf(plugin.getAppConfig().getStringList(
                "cloud-control.moderation.protected-discord-user-ids"));
    }

    public CompletableFuture<Map<String, Object>> execute(
            String operation,
            Map<String, Object> payload,
            String actorDiscordId,
            String actorDisplayName,
            String idempotencyKey
    ) {
        String action = operation.substring("moderation.".length()).toLowerCase(Locale.ROOT);
        if (action.equals("history")) return history(payload);
        String reason = requireText(payload.get("reason"), 500, "reason");
        String platform = requirePlatform(payload.get("platform"));
        ParsedDuration duration = parseDuration(text(payload.get("duration")), action);
        return resolveTarget(payload).thenCompose(target -> {
            protect(target);
            List<CompletableFuture<Outcome>> actions = new ArrayList<>();
            if ((platform.equals("minecraft") || platform.equals("both")) && !action.equals("note")) {
                actions.add(minecraft(action, target, duration, reason));
            }
            if ((platform.equals("discord") || platform.equals("both")) && !action.equals("note")) {
                actions.add(discord(action, target, duration, reason, actorDiscordId, actorDisplayName));
            }
            if (action.equals("note")) {
                actions.add(CompletableFuture.completedFuture(new Outcome("case", true, "Note recorded")));
            }
            if (actions.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "No resolvable target exists for platform " + platform));
            }
            return CompletableFuture.allOf(actions.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> actions.stream().map(CompletableFuture::join).toList())
                    .thenCompose(outcomes -> recordCase(action, target, actorDisplayName,
                            reason, duration.source(), idempotencyKey,
                            outcomes.stream().anyMatch(Outcome::success))
                            .thenCompose(caseId -> persistExpiry(action, target, duration, idempotencyKey)
                                    .thenApply(nothing -> result(action, target, outcomes, caseId, duration))));
        });
    }

    public CompletableFuture<Integer> expireDueSanctions() {
        return repository.listStates("sanction:").thenCompose(states -> {
            long now = System.currentTimeMillis();
            List<CompletableFuture<Boolean>> operations = new ArrayList<>();
            states.forEach((key, json) -> {
                try {
                    Map<String, Object> state = MiniJson.parseObject(json);
                    long expiresAt = number(state.get("expiresAt"));
                    if (expiresAt <= 0L || expiresAt > now) return;
                    String platform = text(state.get("platform"));
                    String minecraftName = text(state.get("minecraftName"));
                    String discordId = text(state.get("discordUserId"));
                    CompletableFuture<?> expire;
                    if (platform.equals("minecraft") && SAFE_NAME.matcher(minecraftName).matches()) {
                        expire = dispatchMinecraft(command("unban", minecraftName, "expired", "", ""));
                    } else if (platform.equals("discord") && discordId.matches("[0-9]{15,22}")) {
                        Guild guild = guild();
                        expire = guild.unban(UserSnowflake.fromId(discordId))
                                .reason("CoreDSC temporary sanction expired").submit();
                    } else {
                        expire = CompletableFuture.completedFuture(null);
                    }
                    operations.add(expire.handle((ignored, error) -> error == null)
                            .thenCompose(success -> success
                                    ? repository.removeState(key).thenApply(ignored -> true)
                                    : CompletableFuture.completedFuture(false)));
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("[Cloud] Ignoring malformed sanction state " + key
                            + ": " + rootMessage(error));
                }
            });
            return CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> (int) operations.stream().filter(CompletableFuture::join).count());
        });
    }

    private CompletableFuture<Target> resolveTarget(Map<String, Object> payload) {
        String uuid = text(payload.get("minecraftUuid")).toLowerCase(Locale.ROOT);
        String name = text(payload.get("target"));
        String discordId = text(payload.get("discordUserId"));
        if (!uuid.isBlank() && !validUuid(uuid)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("minecraftUuid is invalid"));
        }
        if (!name.isBlank() && !SAFE_NAME.matcher(name).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("target must be a Minecraft name"));
        }
        if (!discordId.isBlank() && !discordId.matches("[0-9]{15,22}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("discordUserId is invalid"));
        }

        if (!uuid.isBlank()) {
            String suppliedName = name;
            String suppliedDiscord = discordId;
            return links.findByMinecraftUuid(uuid).thenCompose(linked -> resolveMinecraftName(uuid, suppliedName)
                    .thenApply(resolvedName -> new Target(uuid, resolvedName,
                            suppliedDiscord.isBlank()
                                    ? linked.map(LinkedAccountRepository.LinkedAccount::discordUserId).orElse("")
                                    : suppliedDiscord)));
        }
        if (!discordId.isBlank()) {
            String suppliedName = name;
            String suppliedDiscord = discordId;
            return links.findByDiscordUserId(discordId).thenApply(linked -> linked
                    .map(account -> new Target(account.minecraftUuid(),
                            suppliedName.isBlank() ? account.minecraftName() : suppliedName,
                            suppliedDiscord))
                    .orElse(new Target("", suppliedName, suppliedDiscord)));
        }
        if (!name.isBlank()) return CompletableFuture.completedFuture(new Target("", name, ""));
        return CompletableFuture.failedFuture(new IllegalArgumentException(
                "A Minecraft name/UUID or Discord user ID is required"));
    }

    private CompletableFuture<String> resolveMinecraftName(String uuid, String supplied) {
        if (!supplied.isBlank()) return CompletableFuture.completedFuture(supplied);
        UUID parsed = UUID.fromString(uuid);
        return plugin.callSync(() -> Optional.ofNullable(Bukkit.getOfflinePlayer(parsed).getName()).orElse(""));
    }

    private CompletableFuture<Outcome> minecraft(
            String action,
            Target target,
            ParsedDuration duration,
            String reason
    ) {
        if (!SAFE_NAME.matcher(target.minecraftName()).matches()) {
            return CompletableFuture.completedFuture(new Outcome(
                    "minecraft", false, "No verified Minecraft name is available"));
        }
        if (action.equals("note")) {
            return CompletableFuture.completedFuture(new Outcome("minecraft", true, "Note only"));
        }
        String command;
        try {
            command = command(action, target.minecraftName(), reason, duration.source(), target.minecraftUuid());
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(new Outcome("minecraft", false, rootMessage(error)));
        }
        return dispatchMinecraft(command).handle((accepted, error) -> error == null && Boolean.TRUE.equals(accepted)
                ? new Outcome("minecraft", true, "Command accepted by the local server")
                : new Outcome("minecraft", false, error == null
                        ? "The configured command was not accepted" : rootMessage(error)));
    }

    private CompletableFuture<Outcome> discord(
            String action,
            Target target,
            ParsedDuration duration,
            String reason,
            String actorDiscordId,
            String actorDisplayName
    ) {
        if (!target.discordUserId().matches("[0-9]{15,22}")) {
            return CompletableFuture.completedFuture(new Outcome(
                    "discord", false, "No linked Discord identity is available"));
        }
        Guild guild;
        try {
            guild = guild();
            requireActorHierarchy(guild, actorDiscordId);
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(new Outcome("discord", false, rootMessage(error)));
        }
        String audit = truncate("CoreDSC · " + actorDisplayName + " · " + reason, 500);
        CompletableFuture<?> operation;
        try {
            operation = switch (action) {
                case "ban" -> {
                    requirePermission(guild, Permission.BAN_MEMBERS);
                    yield hierarchy(guild, target.discordUserId()).thenCompose(ignored -> guild
                            .ban(UserSnowflake.fromId(target.discordUserId()), 0, TimeUnit.SECONDS)
                            .reason(audit).submit());
                }
                case "unban" -> {
                    requirePermission(guild, Permission.BAN_MEMBERS);
                    yield guild.unban(UserSnowflake.fromId(target.discordUserId())).reason(audit).submit();
                }
                case "kick" -> {
                    requirePermission(guild, Permission.KICK_MEMBERS);
                    yield hierarchy(guild, target.discordUserId()).thenCompose(member -> member.kick().reason(audit).submit());
                }
                case "timeout" -> {
                    requirePermission(guild, Permission.MODERATE_MEMBERS);
                    if (duration.permanent() || duration.milliseconds() > Duration.ofDays(28).toMillis()) {
                        throw new IllegalArgumentException("Discord timeouts must be between 1 minute and 28 days");
                    }
                    yield hierarchy(guild, target.discordUserId()).thenCompose(member -> member
                            .timeoutFor(duration.milliseconds(), TimeUnit.MILLISECONDS).reason(audit).submit());
                }
                case "warn" -> CompletableFuture.completedFuture(null);
                default -> throw new IllegalArgumentException("Unsupported Discord moderation action " + action);
            };
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(new Outcome("discord", false, rootMessage(error)));
        }
        return operation.handle((ignored, error) -> error == null
                ? new Outcome("discord", true, action.equals("warn")
                        ? "Warning recorded in the unified case" : "Discord action completed")
                : new Outcome("discord", false, rootMessage(error)));
    }

    private CompletableFuture<Member> hierarchy(Guild guild, String discordId) {
        if (guild.getOwnerId().equals(discordId)) {
            return CompletableFuture.failedFuture(new SecurityException("The Discord guild owner is protected"));
        }
        return guild.retrieveMemberById(discordId).submit().thenApply(member -> {
            if (!guild.getSelfMember().canInteract(member)) {
                throw new SecurityException("The bot role is not above the target in Discord's role hierarchy");
            }
            return member;
        });
    }

    private void requireActorHierarchy(Guild guild, String actorDiscordId) {
        if (!actorDiscordId.matches("[0-9]{15,22}")) {
            throw new SecurityException("The cloud actor identity is invalid");
        }
        Member actor = guild.getMemberById(actorDiscordId);
        if (actor == null && !guild.getOwnerId().equals(actorDiscordId)) {
            throw new SecurityException("The acting staff member is not in the configured Discord guild");
        }
    }

    private CompletableFuture<Boolean> dispatchMinecraft(String command) {
        if (command.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No local command template is configured for this action in modules/cloud-control.yml"));
        }
        return plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String command(String action, String name, String reason, String duration, String uuid) {
        String configured = plugin.getAppConfig().getString(
                "cloud-control.moderation.minecraft-commands." + action, defaultCommand(action));
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Configure cloud-control.moderation.minecraft-commands."
                    + action + " before using this action on Minecraft");
        }
        String command = configured.replace("%player%", name)
                .replace("%uuid%", uuid)
                .replace("%duration%", duration)
                .replace("%reason%", sanitizeCommandValue(reason));
        command = command.startsWith("/") ? command.substring(1) : command;
        if (command.length() > 1_000 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0
                || command.indexOf('\0') >= 0) {
            throw new SecurityException("Rendered moderation command is invalid or too long");
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (Set.of("coredsc", "minecraft:stop", "stop", "reload", "rl").contains(root)) {
            throw new SecurityException("Unsafe moderation command root is blocked");
        }
        return command;
    }

    private CompletableFuture<Long> recordCase(
            String action,
            Target target,
            String actor,
            String reason,
            String duration,
            String externalId,
            boolean confirmed
    ) {
        CaseModule cases = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(CaseModule.class);
        if (cases == null) return CompletableFuture.completedFuture(0L);
        return cases.recordModerationAction(action.toUpperCase(Locale.ROOT),
                target.minecraftUuid(), target.minecraftName(), actor, reason, duration,
                "cloud-dashboard", externalId, confirmed);
    }

    private CompletableFuture<Void> persistExpiry(
            String action,
            Target target,
            ParsedDuration duration,
            String operationId
    ) {
        if (!action.equals("ban") || duration.permanent() || duration.milliseconds() <= 0L) {
            return CompletableFuture.completedFuture(null);
        }
        long expiresAt = System.currentTimeMillis() + duration.milliseconds();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        if (!target.minecraftName().isBlank()) {
            writes.add(repository.putState("sanction:" + operationId + ":minecraft", MiniJson.write(Map.of(
                    "platform", "minecraft", "minecraftName", target.minecraftName(),
                    "expiresAt", expiresAt)), System.currentTimeMillis()));
        }
        if (!target.discordUserId().isBlank()) {
            writes.add(repository.putState("sanction:" + operationId + ":discord", MiniJson.write(Map.of(
                    "platform", "discord", "discordUserId", target.discordUserId(),
                    "expiresAt", expiresAt)), System.currentTimeMillis()));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Map<String, Object>> history(Map<String, Object> payload) {
        CaseModule cases = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(CaseModule.class);
        if (cases == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Enable the cases module to query moderation history"));
        }
        String target = requireText(payload.get("target"), 64, "target");
        int limit = boundedInteger(payload.get("limit"), 1, 50, 20, "limit");
        return cases.cloudHistory(target, limit).thenApply(found -> Map.of(
                "target", target,
                "cases", found));
    }

    private static int boundedInteger(Object value, int minimum, int maximum, int fallback, String field) {
        long number = value instanceof Number raw ? raw.longValue() : fallback;
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) number;
    }

    private Map<String, Object> result(
            String action,
            Target target,
            List<Outcome> outcomes,
            long caseId,
            ParsedDuration duration
    ) {
        boolean anySuccess = outcomes.stream().anyMatch(Outcome::success);
        boolean allSuccess = outcomes.stream().allMatch(Outcome::success);
        if (!anySuccess) {
            throw new IllegalStateException("Moderation action failed: " + outcomes.stream()
                    .map(outcome -> outcome.platform() + "=" + outcome.detail()).toList());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("target", Map.of(
                "minecraftUuid", target.minecraftUuid(),
                "minecraftName", target.minecraftName(),
                "discordUserId", target.discordUserId()));
        result.put("duration", duration.source());
        result.put("caseId", caseId);
        result.put("complete", allSuccess);
        result.put("outcomes", outcomes.stream().map(outcome -> Map.of(
                "platform", outcome.platform(), "success", outcome.success(), "detail", outcome.detail())).toList());
        return Map.copyOf(result);
    }

    private void protect(Target target) {
        if (protectedMinecraftNames.contains(target.minecraftName().toUpperCase(Locale.ROOT))
                || protectedMinecraftUuids.contains(target.minecraftUuid().toLowerCase(Locale.ROOT))
                || protectedDiscordIds.contains(target.discordUserId())) {
            throw new SecurityException("The target is protected by the local CoreDSC moderation policy");
        }
        Guild guild = optionalGuild();
        if (guild != null && !target.discordUserId().isBlank()
                && guild.getOwnerId().equals(target.discordUserId())) {
            throw new SecurityException("The Discord guild owner is always protected");
        }
    }

    private Guild guild() {
        Guild guild = optionalGuild();
        if (guild == null) throw new IllegalStateException("The configured Discord guild is unavailable");
        return guild;
    }

    private Guild optionalGuild() {
        DiscordBotService discord = plugin.getDiscordService();
        return discord == null || discord.getJda() == null
                ? null : discord.getJda().getGuildById(discord.getConfiguredGuildId());
    }

    private static void requirePermission(Guild guild, Permission permission) {
        if (!guild.getSelfMember().hasPermission(permission)) {
            throw new SecurityException("The Discord bot needs " + permission.getName());
        }
    }

    private static ParsedDuration parseDuration(String value, String action) {
        String text = value.isBlank() ? defaultDuration(action) : value.toLowerCase(Locale.ROOT);
        if (Set.of("permanent", "perm", "forever", "0").contains(text)) {
            return new ParsedDuration("permanent", 0L, true);
        }
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("duration must look like 30m, 7d, 2w, or permanent");
        }
        long amount = Long.parseLong(matcher.group(1));
        long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> throw new IllegalArgumentException("Unsupported duration unit");
        };
        long milliseconds = Math.multiplyExact(amount, multiplier);
        if (milliseconds > MAXIMUM_DURATION_MILLIS) {
            throw new IllegalArgumentException("duration exceeds the local ten-year safety limit");
        }
        return new ParsedDuration(text, milliseconds, false);
    }

    private static String defaultDuration(String action) {
        return switch (action) {
            case "ban" -> "permanent";
            case "timeout" -> "1h";
            default -> "0";
        };
    }

    private static String defaultCommand(String action) {
        return switch (action) {
            case "ban" -> "ban %player% %reason%";
            case "unban" -> "pardon %player%";
            case "kick" -> "kick %player% %reason%";
            case "warn" -> "tell %player% [CoreDSC Warning] %reason%";
            default -> "";
        };
    }

    private static String requirePlatform(Object value) {
        String platform = text(value).toLowerCase(Locale.ROOT);
        if (platform.isBlank()) platform = "both";
        if (!Set.of("minecraft", "discord", "both").contains(platform)) {
            throw new IllegalArgumentException("platform must be minecraft, discord, or both");
        }
        return platform;
    }

    private static String requireText(Object value, int maximum, String field) {
        String text = text(value);
        if (text.length() < 3 || text.length() > maximum || containsControl(text)) {
            throw new IllegalArgumentException(field + " must contain 3-" + maximum + " safe characters");
        }
        return text;
    }

    private static String sanitizeCommandValue(String value) {
        return value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean containsControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static Set<String> upperSet(List<String> values) {
        return values.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> lowerSet(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
