package com.hubertstudios.coredsc.discord;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.event.DiscordReadyEvent;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.module.ModuleManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the JDA lifecycle. Startup and shutdown never wait on Discord from the
 * Minecraft main thread. Modules register listeners through this service so
 * they remain valid across reconnects and configuration reloads.
 */
public final class DiscordBotService {

    public enum State {
        DISABLED,
        STOPPED,
        CONNECTING,
        READY,
        FAILED
    }

    private final CoreDSCPlugin plugin;
    private final CopyOnWriteArrayList<Object> eventListeners = new CopyOnWriteArrayList<>();
    private final Set<CommandScope> knownCommandScopes = ConcurrentHashMap.newKeySet();
    private final Object commandRegistrationLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong commandRegistrationGeneration = new AtomicLong();
    private CompletableFuture<Void> commandRegistrationChain = CompletableFuture.completedFuture(null);

    private volatile JDA jda;
    private volatile State state = State.STOPPED;
    private volatile String failureReason = "";
    private volatile ConnectionSettings currentSettings;

    public DiscordBotService(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public JDA getJda() {
        return jda;
    }

    public State getState() {
        return state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isReady() {
        return state == State.READY && jda != null;
    }

    public void addEventListener(Object listener) {
        Objects.requireNonNull(listener, "listener");
        if (!eventListeners.addIfAbsent(listener)) {
            return;
        }
        JDA current = jda;
        if (current != null) {
            current.addEventListener(listener);
        }
    }

    public void removeEventListener(Object listener) {
        if (listener == null) {
            return;
        }
        eventListeners.remove(listener);
        JDA current = jda;
        if (current != null) {
            current.removeEventListener(listener);
        }
    }

    /** Initiates a connection and returns immediately. */
    public void start() {
        ConnectionSettings settings;
        try {
            settings = resolveSettings();
        } catch (Exception exception) {
            fail("Discord configuration error: " + rootMessage(exception));
            return;
        }
        start(settings);
    }

    /**
     * Applies changed settings. A reconnect only occurs when the token,
     * enabled state or required gateway intents changed.
     */
    public void validateConfiguration() {
        resolveSettings();
    }

    public void reload() {
        ConnectionSettings next;
        try {
            next = resolveSettings();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Discord configuration error after reload: " + rootMessage(exception),
                    exception);
        }

        ConnectionSettings previous = currentSettings;
        if (!next.enabled()) {
            currentSettings = next;
            stop();
            state = State.DISABLED;
            return;
        }

        if (previous == null || jda == null || !previous.connectionEquivalent(next)) {
            stop();
            start(next);
            if (state == State.FAILED) {
                throw new IllegalStateException(failureReason);
            }
            return;
        }

        currentSettings = next;
        if (isReady()) {
            registerSlashCommands();
        }
    }

    /** Starts Discord with already-resolved settings and returns immediately. */
    private void start(ConnectionSettings settings) {
        currentSettings = settings;
        if (!settings.enabled()) {
            state = State.DISABLED;
            failureReason = "";
            plugin.getLogger().info("Discord integration is disabled.");
            return;
        }

        long expectedGeneration = generation.incrementAndGet();
        state = State.CONNECTING;
        failureReason = "";

        ListenerAdapter lifecycleListener = new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                if (generation.get() != expectedGeneration) {
                    return;
                }
                jda = event.getJDA();
                state = State.READY;
                failureReason = "";
                plugin.getLogger().info("Discord bot connected as "
                        + event.getJDA().getSelfUser().getName()
                        + " (" + event.getJDA().getSelfUser().getId() + ").");
                plugin.runSync(() -> plugin.getServer().getPluginManager().callEvent(
                        new DiscordReadyEvent(event.getJDA().getSelfUser().getId())));
                registerSlashCommands();
            }

            @Override
            public void onSessionDisconnect(SessionDisconnectEvent event) {
                if (generation.get() == expectedGeneration) {
                    state = State.CONNECTING;
                    failureReason = "Discord gateway disconnected temporarily; JDA is reconnecting.";
                }
            }

            @Override
            public void onSessionResume(SessionResumeEvent event) {
                if (generation.get() == expectedGeneration) {
                    state = State.READY;
                    failureReason = "";
                    plugin.getLogger().info("Discord gateway session resumed.");
                }
            }

            @Override
            public void onShutdown(ShutdownEvent event) {
                if (generation.get() == expectedGeneration) {
                    jda = null;
                    String closeCode = event.getCloseCode() == null
                            ? Integer.toString(event.getCode())
                            : event.getCloseCode() + " (" + event.getCode() + ")";
                    fail("Discord disconnected permanently with close code " + closeCode + ".");
                }
            }
        };

        try {
            Thread.ofVirtual()
                    .name("CoreDSC-Discord-Startup-" + expectedGeneration)
                    .start(() -> {
                        try {
                            JDABuilder builder = JDABuilder.createLight(
                                    settings.token(), settings.intents());
                            if (settings.intents().contains(GatewayIntent.GUILD_VOICE_STATES)) {
                                // createLight disables the voice-state cache. Audio receive and
                                // GuildVoiceState echo protection require it explicitly.
                                builder.enableCache(CacheFlag.VOICE_STATE);
                            }
                            if (settings.intents().contains(GatewayIntent.GUILD_MEMBERS)) {
                                // JDA only emits reliable member/role events for cached members.
                                // These modules intentionally require full member state.
                                builder.setMemberCachePolicy(MemberCachePolicy.ALL);
                            } else if (settings.intents().contains(GatewayIntent.GUILD_VOICE_STATES)) {
                                builder.setMemberCachePolicy(MemberCachePolicy.VOICE);
                            }
                            builder.addEventListeners(lifecycleListener);
                            List<Object> startupListeners = List.copyOf(eventListeners);
                            if (!startupListeners.isEmpty()) {
                                builder.addEventListeners(startupListeners.toArray());
                            }
                            JDA built = builder.build();
                            if (generation.get() != expectedGeneration || state == State.FAILED) {
                                built.shutdownNow();
                                return;
                            }
                            // Publish the instance before reconciling listeners. Any listener
                            // added after this assignment attaches itself through addEventListener().
                            jda = built;
                            for (Object listener : eventListeners) {
                                if (!startupListeners.contains(listener)) {
                                    built.addEventListener(listener);
                                }
                            }
                            for (Object listener : startupListeners) {
                                if (!eventListeners.contains(listener)) {
                                    built.removeEventListener(listener);
                                }
                            }
                        } catch (Throwable throwable) {
                            if (generation.get() == expectedGeneration) {
                                jda = null;
                                fail("Discord connection could not start: "
                                        + rootMessage(throwable));
                            }
                        }
                    });
        } catch (Throwable throwable) {
            if (generation.get() == expectedGeneration) {
                jda = null;
                fail("Discord startup thread could not be created: "
                        + rootMessage(throwable));
            }
        }
    }

    /** Requests shutdown without blocking the Minecraft thread. */
    public void stop() {
        generation.incrementAndGet();
        commandRegistrationGeneration.incrementAndGet();
        JDA current = jda;
        jda = null;
        if (current != null) {
            try {
                current.shutdown();
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Discord shutdown request failed: " + rootMessage(throwable));
                try {
                    current.shutdownNow();
                } catch (Throwable ignored) {
                    // Nothing else can be done safely during server shutdown.
                }
            }
        }
        if (state != State.DISABLED) {
            state = State.STOPPED;
        }
    }

    /** Replaces the bot's command set to match the currently enabled modules. */
    public void registerSlashCommands() {
        JDA current = jda;
        if (current == null || state != State.READY) {
            return;
        }

        long expectedRegistration = commandRegistrationGeneration.incrementAndGet();
        List<CommandData> commands;
        CommandScope target;
        try {
            commands = buildSlashCommands();
            target = resolveCommandScope(current);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not prepare Discord slash commands: "
                    + rootMessage(exception));
            return;
        }

        // Discord REST requests can complete out of order. Chain the entire
        // clear-and-replace operation so an obsolete deletion can never land
        // after a newer command registration.
        synchronized (commandRegistrationLock) {
            commandRegistrationChain = commandRegistrationChain
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> synchronizeCommandScopes(
                            current, target, commands, expectedRegistration));
        }
    }

    private CompletableFuture<Void> synchronizeCommandScopes(
            JDA current,
            CommandScope target,
            List<CommandData> commands,
            long expectedRegistration
    ) {
        if (!isCurrentRegistration(current, expectedRegistration)) {
            return CompletableFuture.completedFuture(null);
        }

        Set<CommandScope> obsoleteScopes = ConcurrentHashMap.newKeySet();
        obsoleteScopes.addAll(knownCommandScopes);
        obsoleteScopes.remove(target);
        if (target.global()) {
            ConnectionSettings settings = currentSettings;
            if (settings != null && settings.guildId() > 0L) {
                obsoleteScopes.add(CommandScope.forGuild(settings.guildId()));
            }
        } else {
            obsoleteScopes.add(CommandScope.forGlobal());
        }

        CompletableFuture<?>[] clearOperations = obsoleteScopes.stream()
                .map(scope -> clearCommandScope(current, scope))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(clearOperations)
                .thenCompose(ignored -> {
                    if (!isCurrentRegistration(current, expectedRegistration)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return updateCommandScope(current, target, commands, expectedRegistration);
                });
    }

    private boolean isCurrentRegistration(JDA source, long expectedRegistration) {
        return jda == source
                && state == State.READY
                && commandRegistrationGeneration.get() == expectedRegistration;
    }

    private List<CommandData> buildSlashCommands() {
        List<CommandData> commands = new ArrayList<>();
        Set<String> names = new HashSet<>();
        ModuleManager modules = plugin.getModuleManager();
        if (modules == null) {
            return commands;
        }
        for (CoreModule module : modules.getEnabledModulesSnapshot()) {
            if (!(module instanceof DiscordCommandContributor contributor)) {
                continue;
            }
            for (CommandData command : contributor.slashCommands()) {
                String name = command.getName().toLowerCase(Locale.ROOT);
                if (!names.add(name)) {
                    throw new IllegalStateException("Duplicate Discord slash command /" + name
                            + " contributed by module " + module.id());
                }
                commands.add(command);
            }
        }
        return commands;
    }

    private CommandScope resolveCommandScope(JDA current) {
        ConnectionSettings settings = currentSettings;
        String registrationMode = settings == null ? "guild" : settings.registrationMode();
        long guildId = settings == null ? 0L : settings.guildId();

        if ("guild".equals(registrationMode) && guildId <= 0L) {
            plugin.getLogger().warning(
                    "discord.command-registration is 'guild' but discord.guild-id is not set; "
                            + "using global commands.");
        }
        if ("guild".equals(registrationMode) && guildId > 0L) {
            Guild guild = current.getGuildById(guildId);
            if (guild != null) {
                return CommandScope.forGuild(guildId);
            }
            plugin.getLogger().warning("Configured Discord guild " + guildId
                    + " is not visible to the bot; falling back to global commands.");
        }
        return CommandScope.forGlobal();
    }

    private CompletableFuture<Void> updateCommandScope(
            JDA current,
            CommandScope scope,
            List<CommandData> commands,
            long expectedRegistration
    ) {
        CompletableFuture<?> action;
        String location;
        try {
            if (scope.global()) {
                action = current.updateCommands().addCommands(commands).submit();
                location = "global";
            } else {
                Guild guild = current.getGuildById(scope.guildId());
                if (guild == null) {
                    plugin.getLogger().severe("Cannot register guild commands because guild "
                            + scope.guildId() + " is no longer visible.");
                    return CompletableFuture.completedFuture(null);
                }
                action = guild.updateCommands().addCommands(commands).submit();
                location = "guild " + guild.getName();
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not submit Discord command registration: "
                    + rootMessage(exception));
            return CompletableFuture.completedFuture(null);
        }

        return action.handle((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().severe("Failed to register " + location
                        + " commands: " + rootMessage(error));
                return null;
            }
            if (isCurrentRegistration(current, expectedRegistration)) {
                knownCommandScopes.add(scope);
                plugin.getLogger().info("Registered " + commands.size()
                        + " Discord command(s) in " + location + ".");
            }
            return null;
        });
    }

    private CompletableFuture<Void> clearCommandScope(JDA current, CommandScope scope) {
        CompletableFuture<?> action;
        String location;
        try {
            if (scope.global()) {
                action = current.updateCommands().submit();
                location = "global";
            } else {
                Guild guild = current.getGuildById(scope.guildId());
                if (guild == null) {
                    plugin.getLogger().warning("Could not clear obsolete commands in guild "
                            + scope.guildId() + " because it is not visible to the bot.");
                    return CompletableFuture.completedFuture(null);
                }
                action = guild.updateCommands().submit();
                location = "guild " + scope.guildId();
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not submit obsolete-command cleanup: "
                    + rootMessage(exception));
            return CompletableFuture.completedFuture(null);
        }

        return action.handle((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not clear obsolete " + location
                        + " commands: " + rootMessage(error));
            } else {
                knownCommandScopes.remove(scope);
            }
            return null;
        });
    }

    private ConnectionSettings resolveSettings() {
        FileConfiguration config = plugin.getAppConfig();
        boolean enabled = config.getBoolean("discord.enabled", true);
        String mode = config.getString("discord.command-registration", "guild");
        mode = mode == null ? "guild" : mode.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("guild") && !mode.equals("global")) {
            throw new IllegalArgumentException("discord.command-registration must be 'guild' or 'global'");
        }
        long guildId = readOptionalSnowflake(config, "discord.guild-id");

        if (!enabled) {
            return new ConnectionSettings(false, "", Set.of(), mode, guildId);
        }

        String source = config.getString("discord.token-source", "ENV");
        source = source == null ? "ENV" : source.trim().toUpperCase(Locale.ROOT);
        String token;
        switch (source) {
            case "ENV" -> {
                String environmentName = config.getString("discord.token-env-name", "COREDSC_BOT_TOKEN");
                if (environmentName == null || environmentName.isBlank()) {
                    throw new IllegalArgumentException("discord.token-env-name is blank");
                }
                token = System.getenv(environmentName.trim());
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException(
                            "Environment variable '" + environmentName.trim() + "' is not set");
                }
            }
            case "SECRETS.YML" -> {
                File file = new File(plugin.getDataFolder(), "secrets.yml");
                if (!file.isFile()) {
                    throw new IllegalStateException("secrets.yml does not exist");
                }
                FileConfiguration secrets = YamlConfiguration.loadConfiguration(file);
                token = secrets.getString("discord-token");
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException("discord-token in secrets.yml is blank");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported discord.token-source: " + source);
        }

        EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
        boolean chatEnabled = config.getBoolean("modules.chat-sync", true);
        String discordToMinecraftChannel = config.getString(
                "chat-sync.discord-to-minecraft.channel-id", "");
        boolean threadMessageFeatures = config.getBoolean("modules.tickets", false)
                || config.getBoolean("modules.reports", false);
        boolean remoteConsole = config.getBoolean("modules.console", false)
                && !"OFF".equalsIgnoreCase(config.getString("console.remote.mode", "OFF"));
        if ((chatEnabled && discordToMinecraftChannel != null
                && !discordToMinecraftChannel.isBlank()) || threadMessageFeatures || remoteConsole) {
            intents.add(GatewayIntent.GUILD_MESSAGES);
            intents.add(GatewayIntent.MESSAGE_CONTENT);
        }

        if (config.getBoolean("modules.luckperms-sync", false)
                || config.getBoolean("modules.nickname-sync", false)
                || config.getBoolean("modules.booster-rewards", false)) {
            intents.add(GatewayIntent.GUILD_MEMBERS);
        }
        if (config.getBoolean("modules.ban-sync", false)) {
            intents.add(GatewayIntent.GUILD_MODERATION);
        }
        if (config.getBoolean("modules.voicechat-sync", false)) {
            intents.add(GatewayIntent.GUILD_VOICE_STATES);
        }

        return new ConnectionSettings(
                true,
                token.trim(),
                Set.copyOf(intents),
                mode,
                guildId
        );
    }

    private static long readOptionalSnowflake(FileConfiguration config, String path) {
        Object raw = config.get(path);
        if (raw == null) {
            return 0L;
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || value.equals("0")) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " must be a positive Discord ID or 0", exception);
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        state = State.FAILED;
        plugin.getLogger().severe(reason);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record CommandScope(boolean global, long guildId) {
        private static CommandScope forGlobal() {
            return new CommandScope(true, 0L);
        }

        private static CommandScope forGuild(long guildId) {
            return new CommandScope(false, guildId);
        }
    }

    private record ConnectionSettings(
            boolean enabled,
            String token,
            Set<GatewayIntent> intents,
            String registrationMode,
            long guildId
    ) {
        private boolean connectionEquivalent(ConnectionSettings other) {
            return other != null
                    && enabled == other.enabled
                    && token.equals(other.token)
                    && intents.equals(other.intents);
        }
    }
}
