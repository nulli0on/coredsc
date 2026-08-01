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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

 
                                                                              
                                                                            
                                                                 
   
public final class DiscordBotService {

    public enum State {
        DISABLED,
        STOPPED,
        CONNECTING,
        READY,
        FAILED
    }

    public enum GuildResolutionState {
        NOT_CONFIGURED,
        WAITING,
        READY,
        NOT_VISIBLE
    }

    public enum CommandRegistrationState {
        DISABLED,
        NOT_STARTED,
        REGISTERING,
        READY,
        FAILED
    }

    private final CoreDSCPlugin plugin;
    private final CopyOnWriteArrayList<Object> eventListeners = new CopyOnWriteArrayList<>();
    private final Object listenerLock = new Object();
    private final Set<CommandScope> knownCommandScopes = ConcurrentHashMap.newKeySet();
    private final Object commandRegistrationLock = new Object();
    private final Object commandScopeStateLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong commandRegistrationGeneration = new AtomicLong();
    private CompletableFuture<Void> commandRegistrationChain = CompletableFuture.completedFuture(null);

    private volatile JDA jda;
    private volatile State state = State.STOPPED;
    private volatile String failureReason = "";
    private volatile ConnectionSettings currentSettings;
    private volatile GuildResolutionState guildResolutionState = GuildResolutionState.NOT_CONFIGURED;
    private volatile String guildResolutionDetail = "no guild configured";
    private volatile long resolvedGuildId;
    private volatile String resolvedGuildName = "";
    private volatile CommandRegistrationState commandRegistrationState = CommandRegistrationState.NOT_STARTED;
    private volatile String commandRegistrationDetail = "not started";

    public DiscordBotService(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        loadKnownCommandScopes();
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

    public long getConfiguredGuildId() {
        ConnectionSettings settings = currentSettings;
        return settings == null ? 0L : settings.guildId();
    }

    public GuildResolutionState getGuildResolutionState() {
        return guildResolutionState;
    }

    public String getGuildResolutionDetail() {
        return guildResolutionDetail;
    }

    public long getResolvedGuildId() {
        return resolvedGuildId;
    }

    public String getResolvedGuildName() {
        return resolvedGuildName;
    }

    public CommandRegistrationState getCommandRegistrationState() {
        return commandRegistrationState;
    }

    public String getCommandRegistrationDetail() {
        return commandRegistrationDetail;
    }

    public void addEventListener(Object listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (listenerLock) {
            if (!eventListeners.addIfAbsent(listener)) {
                return;
            }
            JDA current = jda;
            if (current != null) {
                try {
                    current.addEventListener(listener);
                } catch (RuntimeException exception) {
                    eventListeners.remove(listener);
                    throw exception;
                }
            }
        }
    }

    public void removeEventListener(Object listener) {
        if (listener == null) {
            return;
        }
        synchronized (listenerLock) {
            eventListeners.remove(listener);
            JDA current = jda;
            if (current != null) {
                try {
                    current.removeEventListener(listener);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Could not detach a Discord event listener: "
                            + rootMessage(exception));
                }
            }
        }
    }

                                                        
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
            stop();
            start(next);
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
            updateGuildResolution(jda, next);
            if ("guild".equals(next.registrationMode())
                    && guildResolutionState != GuildResolutionState.READY) {
                throw new IllegalStateException("Configured Discord guild could not be resolved: "
                        + guildResolutionDetail);
            }
            registerSlashCommands();
        }
    }

                                                                               
    private void start(ConnectionSettings settings) {
        currentSettings = settings;
        if (!settings.enabled()) {
            state = State.DISABLED;
            failureReason = "";
            guildResolutionState = settings.guildId() > 0L
                    ? GuildResolutionState.WAITING : GuildResolutionState.NOT_CONFIGURED;
            guildResolutionDetail = settings.guildId() > 0L
                    ? "Discord integration is disabled; guild was not resolved" : "no guild configured";
            resolvedGuildId = 0L;
            resolvedGuildName = "";
            commandRegistrationState = CommandRegistrationState.DISABLED;
            commandRegistrationDetail = "Discord integration is disabled";
            plugin.getLogger().info("Discord integration is disabled.");
            return;
        }

        long expectedGeneration = generation.incrementAndGet();
        state = State.CONNECTING;
        failureReason = "";
        guildResolutionState = settings.guildId() > 0L
                ? GuildResolutionState.WAITING : GuildResolutionState.NOT_CONFIGURED;
        guildResolutionDetail = settings.guildId() > 0L
                ? "waiting for Discord READY" : "no guild configured";
        resolvedGuildId = 0L;
        resolvedGuildName = "";
        commandRegistrationState = CommandRegistrationState.NOT_STARTED;
        commandRegistrationDetail = "waiting for Discord READY";

        ListenerAdapter lifecycleListener = new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                if (generation.get() != expectedGeneration) {
                    return;
                }
                synchronized (listenerLock) {
                    jda = event.getJDA();
                }
                state = State.READY;
                failureReason = "";
                updateGuildResolution(event.getJDA(), currentSettings);
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
                    ConnectionSettings activeSettings = currentSettings;
                    long activeGuildId = activeSettings == null ? 0L : activeSettings.guildId();
                    guildResolutionState = activeGuildId > 0L
                            ? GuildResolutionState.WAITING : GuildResolutionState.NOT_CONFIGURED;
                    guildResolutionDetail = activeGuildId > 0L
                            ? "waiting for Discord reconnect" : "no guild configured";
                    commandRegistrationState = CommandRegistrationState.NOT_STARTED;
                    commandRegistrationDetail = "waiting for Discord reconnect";
                }
            }

            @Override
            public void onSessionResume(SessionResumeEvent event) {
                if (generation.get() == expectedGeneration) {
                    state = State.READY;
                    failureReason = "";
                    updateGuildResolution(event.getJDA(), currentSettings);
                    plugin.getLogger().info("Discord gateway session resumed.");
                    registerSlashCommands();
                }
            }

            @Override
            public void onShutdown(ShutdownEvent event) {
                if (generation.get() == expectedGeneration) {
                    synchronized (listenerLock) {
                        if (jda == event.getJDA()) {
                            jda = null;
                        }
                    }
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
                                                                                              
                                                                                       
                                builder.enableCache(CacheFlag.VOICE_STATE);
                            }
                            if (settings.intents().contains(GatewayIntent.GUILD_MEMBERS)) {
                                                                                               
                                                                                       
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
                            synchronized (listenerLock) {
                                if (generation.get() != expectedGeneration || state == State.FAILED) {
                                    built.shutdownNow();
                                    return;
                                }
                                                                                                    
                                                                                                  
                                                                         
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
                            }
                        } catch (Throwable throwable) {
                            if (generation.get() == expectedGeneration) {
                                synchronized (listenerLock) {
                                    jda = null;
                                }
                                fail("Discord connection could not start: "
                                        + rootMessage(throwable));
                            }
                        }
                    });
        } catch (Throwable throwable) {
            if (generation.get() == expectedGeneration) {
                synchronized (listenerLock) {
                    jda = null;
                }
                fail("Discord startup thread could not be created: "
                        + rootMessage(throwable));
            }
        }
    }

                                                                 
    public void stop() {
        generation.incrementAndGet();
        commandRegistrationGeneration.incrementAndGet();
        JDA current;
        synchronized (listenerLock) {
            current = jda;
            jda = null;
        }
        if (current != null) {
            try {
                current.shutdown();
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Discord shutdown request failed: " + rootMessage(throwable));
                try {
                    current.shutdownNow();
                } catch (Throwable ignored) {
                                                                            
                }
            }
        }
        if (state != State.DISABLED) {
            state = State.STOPPED;
        }
        resolvedGuildId = 0L;
        resolvedGuildName = "";
        guildResolutionState = GuildResolutionState.NOT_CONFIGURED;
        guildResolutionDetail = "Discord service stopped";
        commandRegistrationState = CommandRegistrationState.NOT_STARTED;
        commandRegistrationDetail = "Discord service stopped";
    }

                                                                               
    public void registerSlashCommands() {
        JDA current = jda;
        if (current == null || state != State.READY) {
            return;
        }

        long expectedRegistration = commandRegistrationGeneration.incrementAndGet();
        commandRegistrationState = CommandRegistrationState.REGISTERING;
        commandRegistrationDetail = "preparing command definitions";
        List<CommandData> commands;
        CommandScope target;
        try {
            commands = buildSlashCommands();
            target = resolveCommandScope(current);
        } catch (RuntimeException exception) {
            failCommandRegistration("Could not prepare Discord slash commands: "
                    + rootMessage(exception));
            return;
        }

                                                                          
                                                                           
                                            
        synchronized (commandRegistrationLock) {
            commandRegistrationChain = commandRegistrationChain
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> synchronizeCommandScopes(
                            current, target, commands, expectedRegistration))
                    .whenComplete((ignored, error) -> {
                        if (error != null && isCurrentRegistration(current, expectedRegistration)) {
                            failCommandRegistration("Unexpected slash-command synchronization failure: "
                                    + rootMessage(error));
                        }
                    });
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

        List<CompletableFuture<String>> clearOperations = obsoleteScopes.stream()
                .map(scope -> clearCommandScope(current, scope))
                .toList();
        return CompletableFuture.allOf(clearOperations.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> {
                    if (!isCurrentRegistration(current, expectedRegistration)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    List<String> cleanupFailures = clearOperations.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .toList();
                    return updateCommandScope(current, target, commands,
                            expectedRegistration, cleanupFailures);
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

        if ("global".equals(registrationMode)) {
            return CommandScope.forGlobal();
        }
        if (guildId <= 0L) {
            throw new IllegalStateException(
                    "discord.command-registration is 'guild' but discord.guild-id is not configured");
        }
        Guild guild = current.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("configured Discord guild " + guildId
                    + " is not visible to the bot; global fallback is intentionally disabled");
        }
        return CommandScope.forGuild(guildId);
    }

    private CompletableFuture<Void> updateCommandScope(
            JDA current,
            CommandScope scope,
            List<CommandData> commands,
            long expectedRegistration,
            List<String> cleanupFailures
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
                    failCommandRegistration("Cannot register guild commands because guild "
                            + scope.guildId() + " is no longer visible.");
                    return CompletableFuture.completedFuture(null);
                }
                action = guild.updateCommands().addCommands(commands).submit();
                location = "guild " + guild.getName();
            }
        } catch (RuntimeException exception) {
            failCommandRegistration("Could not submit Discord command registration: "
                    + rootMessage(exception));
            return CompletableFuture.completedFuture(null);
        }

        return action.handle((ignored, error) -> {
            if (error != null) {
                if (isCurrentRegistration(current, expectedRegistration)) {
                    failCommandRegistration("Failed to register " + location
                            + " commands: " + rootMessage(error));
                }
                return null;
            }
            if (isCurrentRegistration(current, expectedRegistration)) {
                knownCommandScopes.add(scope);
                boolean persisted = persistKnownCommandScopes();
                if (persisted && cleanupFailures.isEmpty()) {
                    commandRegistrationState = CommandRegistrationState.READY;
                    commandRegistrationDetail = commands.size() + " command(s) in " + location;
                } else {
                    commandRegistrationState = CommandRegistrationState.FAILED;
                    List<String> problems = new ArrayList<>(cleanupFailures);
                    if (!persisted) {
                        problems.add("the command-scope state could not be persisted");
                    }
                    commandRegistrationDetail = commands.size() + " command(s) registered in " + location
                            + ", but cleanup/state tracking is incomplete: " + String.join("; ", problems)
                            + ". Stale commands may require manual cleanup";
                }
                plugin.getLogger().info("Registered " + commands.size()
                        + " Discord command(s) in " + location + ".");
            }
            return null;
        });
    }

    private CompletableFuture<String> clearCommandScope(JDA current, CommandScope scope) {
        CompletableFuture<?> action;
        String location;
        try {
            if (scope.global()) {
                action = current.updateCommands().submit();
                location = "global";
            } else {
                Guild guild = current.getGuildById(scope.guildId());
                if (guild == null) {
                    String failure = "obsolete guild " + scope.guildId() + " is not visible to the bot";
                    plugin.getLogger().warning("Could not clear commands because " + failure + ".");
                    return CompletableFuture.completedFuture(failure);
                }
                action = guild.updateCommands().submit();
                location = "guild " + scope.guildId();
            }
        } catch (RuntimeException exception) {
            String failure = "could not submit cleanup for " + serializeCommandScope(scope)
                    + ": " + rootMessage(exception);
            plugin.getLogger().warning("Could not submit obsolete-command cleanup: "
                    + rootMessage(exception));
            return CompletableFuture.completedFuture(failure);
        }

        return action.handle((ignored, error) -> {
            if (error != null) {
                String failure = "could not clear obsolete " + location + " commands: "
                        + rootMessage(error);
                plugin.getLogger().warning(failure);
                return failure;
            }
            knownCommandScopes.remove(scope);
            if (!persistKnownCommandScopes()) {
                return "cleared obsolete " + location
                        + " commands, but could not persist the updated command-scope state";
            }
            return null;
        });
    }

    private void loadKnownCommandScopes() {
        Path stateFile = commandScopeStateFile();
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            for (String rawLine : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                CommandScope parsed = parseCommandScope(line);
                if (parsed == null) {
                    plugin.getLogger().warning("Ignoring malformed command scope in " + stateFile + ": " + line);
                    continue;
                }
                knownCommandScopes.add(parsed);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read persisted Discord command scopes from " + stateFile
                    + ": " + rootMessage(exception));
        }
    }

     
                                                                              
                                                                         
       
    private boolean persistKnownCommandScopes() {
        synchronized (commandScopeStateLock) {
            Path stateFile = commandScopeStateFile();
            Path parent = stateFile.getParent();
            try {
                if (parent == null) {
                    throw new IOException("Command-scope state has no parent directory");
                }
                Files.createDirectories(parent);
                List<String> lines = knownCommandScopes.stream()
                        .sorted((left, right) -> serializeCommandScope(left)
                                .compareTo(serializeCommandScope(right)))
                        .map(DiscordBotService::serializeCommandScope)
                        .toList();
                Path temporary = Files.createTempFile(parent, ".command-scopes-", ".tmp");
                try {
                    Files.write(temporary, lines, StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return true;
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not persist Discord command scopes to " + stateFile
                        + ": " + rootMessage(exception));
                return false;
            }
        }
    }

    private Path commandScopeStateFile() {
        return new File(plugin.getDataFolder(), "state/discord-command-scopes.txt")
                .toPath().toAbsolutePath().normalize();
    }

    private static String serializeCommandScope(CommandScope scope) {
        return scope.global() ? "global" : "guild:" + scope.guildId();
    }

    private static CommandScope parseCommandScope(String value) {
        if (value.equalsIgnoreCase("global")) {
            return CommandScope.forGlobal();
        }
        if (!value.regionMatches(true, 0, "guild:", 0, "guild:".length())) {
            return null;
        }
        try {
            long guildId = Long.parseLong(value.substring("guild:".length()).trim());
            return guildId > 0L ? CommandScope.forGuild(guildId) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void updateGuildResolution(JDA current, ConnectionSettings settings) {
        long guildId = settings == null ? 0L : settings.guildId();
        resolvedGuildId = 0L;
        resolvedGuildName = "";
        if (guildId <= 0L) {
            guildResolutionState = GuildResolutionState.NOT_CONFIGURED;
            guildResolutionDetail = "discord.guild-id is not configured";
            return;
        }
        Guild guild = current.getGuildById(guildId);
        if (guild == null) {
            guildResolutionState = GuildResolutionState.NOT_VISIBLE;
            guildResolutionDetail = "bot is not a member of guild " + guildId
                    + " or JDA cannot see it";
            return;
        }
        resolvedGuildId = guild.getIdLong();
        resolvedGuildName = guild.getName();
        guildResolutionState = GuildResolutionState.READY;
        guildResolutionDetail = guild.getName() + " (" + guild.getId() + ")";
    }

    private void failCommandRegistration(String reason) {
        commandRegistrationState = CommandRegistrationState.FAILED;
        commandRegistrationDetail = reason;
        plugin.getLogger().severe(reason);
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
        if (enabled && mode.equals("guild") && guildId <= 0L) {
            throw new IllegalArgumentException(
                    "discord.guild-id must be configured when discord.command-registration is 'guild'");
        }

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
                YamlConfiguration secrets = new YamlConfiguration();
                try {
                    secrets.load(file);
                } catch (Exception error) {
                    throw new IllegalStateException("secrets.yml could not be parsed: "
                            + rootMessage(error), error);
                }
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
        resolvedGuildId = 0L;
        resolvedGuildName = "";
        ConnectionSettings settings = currentSettings;
        guildResolutionState = settings != null && settings.guildId() > 0L
                ? GuildResolutionState.WAITING : GuildResolutionState.NOT_CONFIGURED;
        guildResolutionDetail = "Discord failed before guild resolution completed: " + reason;
        commandRegistrationState = CommandRegistrationState.FAILED;
        commandRegistrationDetail = "Discord is unavailable: " + reason;
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
