package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.automod.AutoModResponse;
import net.dv8tion.jda.api.entities.automod.AutoModRule;
import net.dv8tion.jda.api.entities.automod.build.AutoModRuleData;
import net.dv8tion.jda.api.entities.automod.build.TriggerConfig;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Typed Discord AutoMod management through the customer's local JDA session.
 * CoreDSC owns only rules carrying its explicit name prefix and created by the
 * configured bot; unrelated guild rules can never be mutated by this service.
 */
public final class AutoModOperationService {
    private static final String NAME_PREFIX = "CoreDSC · ";

    private final CoreDSCPlugin plugin;

    public AutoModOperationService(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public CompletableFuture<Map<String, Object>> execute(
            String operation,
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        return switch (operation) {
            case "automod.rules" -> rules();
            case "automod.upsert" -> upsert(payload, actor, reason);
            case "automod.delete" -> delete(payload, actor, reason);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported AutoMod operation " + operation));
        };
    }

    private CompletableFuture<Map<String, Object>> rules() {
        Guild guild = requireGuild();
        return guild.retrieveAutoModRules().submit().thenApply(rules -> Map.of(
                "rules", rules.stream().map(AutoModOperationService::rule).toList()));
    }

    private CompletableFuture<Map<String, Object>> upsert(
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        Guild guild = requireGuild();
        requirePermission(guild, Permission.MANAGE_SERVER);
        String logicalId = text(payload.get("ruleId")).toLowerCase(Locale.ROOT);
        if (!logicalId.matches("[a-z0-9_-]{2,40}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "ruleId must contain 2-40 lowercase letters, digits, underscores, or dashes"));
        }
        String displayName = bounded(payload.get("name"), 72, "name", true);
        boolean enabled = booleanValue(payload.get("enabled"), true);
        List<String> keywords;
        try {
            keywords = stringList(payload.get("blockedKeywords"), 1_000, 60);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        int mentionLimit = integer(payload.get("mentionLimit"), 0, 50, "mentionLimit");
        if (keywords.isEmpty() && mentionLimit == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "At least one blocked keyword or a mention limit is required"));
        }

        TextChannel alertChannel;
        try {
            alertChannel = optionalAlertChannel(guild, text(payload.get("alertChannelId")));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        String base = NAME_PREFIX + displayName;
        List<AutoModRuleData> desired = new ArrayList<>(2);
        List<String> desiredRuleNames = new ArrayList<>(2);
        if (!keywords.isEmpty()) {
            String name = base + " · Keywords";
            desiredRuleNames.add(name);
            desired.add(ruleData(name, TriggerConfig.keywordFilter(keywords),
                    enabled, alertChannel));
        }
        if (mentionLimit > 0) {
            String name = base + " · Mentions";
            desiredRuleNames.add(name);
            desired.add(ruleData(name, TriggerConfig.mentionSpam(mentionLimit),
                    enabled, alertChannel));
        }

        String audit = audit(actor, reason);
        return guild.retrieveAutoModRules().submit().thenCompose(existing -> {
            Set<String> desiredNames = Set.copyOf(desiredRuleNames);
            List<AutoModRule> previous = existing.stream()
                    .filter(rule -> desiredNames.contains(rule.getName()))
                    .filter(rule -> rule.getCreatorIdLong() == guild.getSelfMember().getIdLong())
                    .toList();

            List<CompletableFuture<AutoModRule>> creates = desired.stream()
                    .map(data -> guild.createAutoModRule(data).reason(audit).submit())
                    .toList();
            return CompletableFuture.allOf(creates.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> creates.stream().map(CompletableFuture::join).toList())
                    .thenCompose(created -> deleteRules(previous, audit).thenApply(ignored -> created));
        }).thenApply(created -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("logicalId", logicalId);
            result.put("rules", created.stream().map(AutoModOperationService::rule).toList());
            result.put("enabled", enabled);
            result.put("message", "Discord AutoMod policy published through the local CoreDSC bot.");
            return Map.copyOf(result);
        });
    }

    private CompletableFuture<Map<String, Object>> delete(
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        Guild guild = requireGuild();
        requirePermission(guild, Permission.MANAGE_SERVER);
        String ruleId = text(payload.get("ruleId"));
        if (!ruleId.matches("[0-9]{15,22}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "ruleId must be a Discord AutoMod rule ID"));
        }
        String audit = audit(actor, reason);
        return guild.retrieveAutoModRules().submit().thenCompose(rules -> {
            AutoModRule rule = rules.stream().filter(value -> value.getId().equals(ruleId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "The configured bot cannot see AutoMod rule " + ruleId));
            if (!rule.getName().startsWith(NAME_PREFIX)
                    || rule.getCreatorIdLong() != guild.getSelfMember().getIdLong()) {
                throw new SecurityException("CoreDSC will delete only its own bot-created AutoMod rules");
            }
            return rule.delete().reason(audit).submit();
        }).thenApply(ignored -> Map.of("ruleId", ruleId, "deleted", true));
    }

    private static AutoModRuleData ruleData(
            String name,
            TriggerConfig trigger,
            boolean enabled,
            TextChannel alertChannel
    ) {
        AutoModRuleData data = AutoModRuleData.onMessage(name, trigger)
                .setEnabled(enabled)
                .putResponses(AutoModResponse.blockMessage(
                        "This message was blocked by the server's community safety policy."));
        if (alertChannel != null) data.putResponses(AutoModResponse.sendAlert(alertChannel));
        return data;
    }

    private static CompletableFuture<Void> deleteRules(List<AutoModRule> rules, String audit) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (AutoModRule rule : rules) {
            chain = chain.thenCompose(ignored -> rule.delete().reason(audit).submit());
        }
        return chain;
    }

    private Guild requireGuild() {
        var service = plugin.getDiscordService();
        if (service == null || !service.isReady() || service.getJda() == null) {
            throw new IllegalStateException("Discord is not ready; run /coredsc doctor");
        }
        Guild guild = service.getJda().getGuildById(service.getConfiguredGuildId());
        if (guild == null) {
            throw new IllegalStateException("The configured Discord guild is not visible to the bot");
        }
        return guild;
    }

    private static TextChannel optionalAlertChannel(Guild guild, String id) {
        if (id.isBlank()) return null;
        if (!id.matches("[0-9]{15,22}")) {
            throw new IllegalArgumentException("alertChannelId must be a Discord channel ID");
        }
        TextChannel channel = guild.getTextChannelById(id);
        if (channel == null) throw new IllegalArgumentException(
                "The bot cannot see AutoMod alert channel " + id + " in the configured guild");
        if (!channel.canTalk()) throw new SecurityException(
                "The bot cannot send AutoMod alerts in #" + channel.getName());
        return channel;
    }

    private static void requirePermission(Guild guild, Permission permission) {
        if (!guild.getSelfMember().hasPermission(permission)) {
            throw new SecurityException("The Discord bot needs " + permission.getName()
                    + " to manage AutoMod rules");
        }
    }

    private static Map<String, Object> rule(AutoModRule rule) {
        return Map.of(
                "id", rule.getId(),
                "name", rule.getName(),
                "enabled", rule.isEnabled(),
                "trigger", rule.getTriggerType().name(),
                "keywords", rule.getFilteredKeywords(),
                "mentionLimit", rule.getMentionLimit(),
                "actions", rule.getActions().stream().map(action -> action.getType().name()).toList(),
                "managed", rule.getName().startsWith(NAME_PREFIX));
    }

    private static List<String> stringList(Object raw, int maximumItems, int maximumLength) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<String> result = values.stream().map(AutoModOperationService::text)
                .filter(value -> !value.isBlank()).distinct().toList();
        if (result.size() > maximumItems) {
            throw new IllegalArgumentException("blockedKeywords exceeds Discord's " + maximumItems + " item limit");
        }
        for (String value : result) {
            if (value.length() > maximumLength) {
                throw new IllegalArgumentException("Each blocked keyword must be at most "
                        + maximumLength + " characters");
            }
        }
        return List.copyOf(result);
    }

    private static int integer(Object value, int minimum, int maximum, String field) {
        long number = value instanceof Number raw ? raw.longValue() : minimum;
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) number;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String bounded(Object value, int maximum, String field, boolean required) {
        String text = text(value);
        if (required && text.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (text.length() > maximum) throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        return text;
    }

    private static String audit(String actor, String reason) {
        String value = "CoreDSC · " + bounded(actor, 80, "actor", true)
                + " · " + bounded(reason, 500, "reason", true);
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
