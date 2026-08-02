package com.hubertstudios.coredsc;

import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.config.ConfigManager;
import com.hubertstudios.coredsc.api.CoreDSCApi;
import com.hubertstudios.coredsc.api.CoreDSCApiImpl;
import com.hubertstudios.coredsc.service.DoctorService;
import com.hubertstudios.coredsc.service.DiscordSrvMigrationService;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.module.impl.ServerEventsModule;
import com.hubertstudios.coredsc.module.impl.StatusChannelModule;
import com.hubertstudios.coredsc.module.impl.PythonBotModule;
import com.hubertstudios.coredsc.module.impl.WebEditorModule;
import com.hubertstudios.coredsc.service.PlaceholderService;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.metrics.MetricsService;
import com.hubertstudios.coredsc.scheduler.CoreScheduler;
import com.hubertstudios.coredsc.scheduler.CoreSchedulers;
import com.hubertstudios.coredsc.voice.VoiceChatBridgeBootstrap;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;


public final class CoreDSCPlugin extends JavaPlugin {
    public enum StartupState {
        INITIALIZING,
        STARTING_STORAGE,
        STARTING_SERVICES,
        READY,
        FAILED,
        DISABLING
    }

    public record ReloadResult(
            boolean success,
            boolean rollbackSucceeded,
            String message,
            int configurationWarnings,
            boolean discordReconnecting
    ) { }

    private static CoreDSCPlugin instance;

    private volatile StartupState startupState = StartupState.INITIALIZING;
    private volatile String startupFailure = "";
    private volatile ConfigManager configManager;
    private volatile SQLiteStorage storage;
    private volatile DiscordBotService discordService;
    private volatile ModuleManager moduleManager;
    private volatile PlaceholderService placeholderService;
    private volatile DoctorService doctorService;
    private volatile DiscordSrvMigrationService migrationService;
    private volatile CoreDSCApiImpl apiProvider;
    private volatile VoiceChatBridgeBootstrap voiceChatBridge;
    private volatile MetricsService metricsService;
    private volatile CoreScheduler coreScheduler;
    private final AtomicBoolean startupAnnouncementPending = new AtomicBoolean();
    private final AtomicBoolean startupAnnouncementSent = new AtomicBoolean();
    private final Set<String> startupWarnings = Collections.synchronizedSet(new LinkedHashSet<>());

    public static CoreDSCPlugin getInstance() {
        return instance;
    }

    public SQLiteStorage getStorage() {
        return storage;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public FileConfiguration getAppConfig() {
        ConfigManager current = configManager;
        if (current == null) {
            return new org.bukkit.configuration.file.YamlConfiguration();
        }
        return current.getConfig();
    }

    public DiscordBotService getDiscordService() {
        return discordService;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public MetricsService getMetricsService() {
        return metricsService;
    }

    public CoreScheduler getCoreScheduler() {
        CoreScheduler current = coreScheduler;
        if (current == null) {
            throw new IllegalStateException("CoreDSC scheduler is not initialised");
        }
        return current;
    }

    public void addStartupWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            startupWarnings.add(warning.trim());
        }
    }

    public List<String> getStartupWarnings() {
        synchronized (startupWarnings) {
            return List.copyOf(startupWarnings);
        }
    }

    public void recordFeatureUse(String feature) {
        MetricsService current = metricsService;
        if (current != null) {
            current.recordFeatureUse(feature);
        }
        ModuleManager modules = moduleManager;
        String moduleId = moduleForFeature(feature);
        if (modules != null && moduleId != null) {
            modules.recordSuccessfulAction(moduleId);
        }
    }

    public void recordModuleFailure(String moduleId, Throwable error) {
        ModuleManager modules = moduleManager;
        if (modules != null) {
            modules.recordFailedAction(moduleId, error);
        }
    }

    public void recordModuleFailure(String moduleId, String detail) {
        ModuleManager modules = moduleManager;
        if (modules != null) {
            modules.recordFailedAction(moduleId, detail);
        }
    }

    public VoiceChatBridgeBootstrap getVoiceChatBridge() {
        VoiceChatBridgeBootstrap current = voiceChatBridge;
        if (current == null) {
            throw new IllegalStateException("Voice bridge bootstrap is not initialised");
        }
        return current;
    }

    public PlaceholderService getPlaceholderService() {
        PlaceholderService current = placeholderService;
        return current == null ? PlaceholderService.disabled(this) : current;
    }

    public void setPlaceholderService(PlaceholderService placeholderService) {
        this.placeholderService = placeholderService;
    }

    public boolean claimServerStartupAnnouncement() {
        if (!startupAnnouncementPending.compareAndSet(true, false)) {
            return false;
        }
        startupAnnouncementSent.set(true);
        return true;
    }

    public boolean wasServerStartupAnnouncementSent() {
        return startupAnnouncementSent.get();
    }

    public StartupState getStartupState() {
        return startupState;
    }

    @Override
    public void onEnable() {
        instance = this;
        startupWarnings.clear();
        coreScheduler = CoreSchedulers.create(this);
        getLogger().info("CoreDSC scheduler runtime: " + coreScheduler.runtime());
        startupAnnouncementPending.set(true);
        startupAnnouncementSent.set(false);
        placeholderService = PlaceholderService.disabled(this);
        try {
            configManager = new ConfigManager(this);
            configManager.initialize();
        } catch (Throwable throwable) {
            failAndDisable("Configuration initialisation failed", throwable);
            return;
        }
        
        
        
        voiceChatBridge = VoiceChatBridgeBootstrap.registerEarly(this);

        printBanner("Starting up");
        initialiseStorage();
    }

    private void initialiseStorage() {
        if (!isEnabled()) {
            return;
        }
        startupState = StartupState.STARTING_STORAGE;
        storage = new SQLiteStorage(this);
        storage.initAsync().whenComplete((ignored, error) -> runSync(() -> {
            if (!isEnabled()) {
                return;
            }
            if (error != null) {
                failAndDisable("SQLite initialisation failed", error);
                return;
            }
            initialiseServices();
        }));
    }

    private void initialiseServices() {
        startupState = StartupState.STARTING_SERVICES;
        try {
            discordService = new DiscordBotService(this);
            moduleManager = new ModuleManager(this);
            moduleManager.loadEnabledModules();
            doctorService = new DoctorService(this);
            migrationService = new DiscordSrvMigrationService(this);
            apiProvider = new CoreDSCApiImpl(this);
            getServer().getServicesManager().register(
                    CoreDSCApi.class, apiProvider, this, ServicePriority.Normal);
            if (!moduleManager.isModuleEnabled("server-events")) {
                startupAnnouncementPending.set(false);
            }

            
            
            discordService.start();
            startupState = StartupState.READY;
            startupFailure = "";
            metricsService = new MetricsService(this);
            metricsService.start();
            getLogger().info("CoreDSC v" + getDescription().getVersion()
                    + " is ready. Discord=" + discordService.getState()
                    + ", modules=" + moduleManager.enabledModuleSummary());
            getCoreScheduler().runGlobalLater(this::printStartupActionSummary, 200L);
        } catch (Throwable throwable) {
            failAndDisable("CoreDSC service initialisation failed", throwable);
        }
    }

    @Override
    public void onDisable() {
        startupState = StartupState.DISABLING;

        if (moduleManager != null) {
            ServerEventsModule serverEvents = moduleManager.getModule(ServerEventsModule.class);
            if (serverEvents != null && startupAnnouncementSent.get()) {
                serverEvents.sendShutdownEvent().whenComplete((ignored, error) -> {
                    if (error != null) getLogger().warning(
                            "Shutdown event delivery failed asynchronously: " + rootMessage(error));
                });
            }

            
            
            
            if (getServer().isStopping()) {
                StatusChannelModule statusChannels = moduleManager.getModule(StatusChannelModule.class);
                if (statusChannels != null) {
                    statusChannels.publishOfflineStatus();
                }
            }
        }
        if (metricsService != null) {
            metricsService.stop();
        }
        if (moduleManager != null) {
            moduleManager.disableModules();
        }
        if (discordService != null) {
            discordService.stop();
        }
        if (voiceChatBridge != null) {
            voiceChatBridge.shutdown();
        }
        if (coreScheduler != null) {
            coreScheduler.shutdown();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (storage != null) {
            storage.closeAsync().whenComplete((ignored, error) -> {
                if (error != null) getLogger().warning(
                        "SQLite shutdown failed asynchronously: " + rootMessage(error));
            });
        }

        moduleManager = null;
        configManager = null;
        placeholderService = null;
        doctorService = null;
        migrationService = null;
        apiProvider = null;
        discordService = null;
        voiceChatBridge = null;
        metricsService = null;
        coreScheduler = null;
        storage = null;
        instance = null;
        getLogger().info("CoreDSC disabled.");
    }

    
    public void runSync(Runnable task) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return;
        }
        try {
            current.runGlobal(task);
        } catch (Throwable throwable) {
            getLogger().warning("Could not schedule a global task: " + rootMessage(throwable));
        }
    }

    
    public <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CoreDSC is disabled"));
        }
        return current.callGlobal(supplier);
    }

    
    public void runForEntity(Entity entity, Runnable task) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled() || entity == null) {
            return;
        }
        try {
            current.runForEntity(entity, task);
        } catch (Throwable throwable) {
            getLogger().warning("Could not schedule an entity task: " + rootMessage(throwable));
        }
    }

    
    public <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> supplier) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CoreDSC is disabled"));
        }
        if (entity == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Entity is required"));
        }
        return current.callForEntity(entity, supplier);
    }

    




    public CompletableFuture<Boolean> runForPlayer(UUID playerId, Consumer<Player> action) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CoreDSC is disabled"));
        }
        return current.runForPlayer(playerId, action);
    }

    
    public <T> CompletableFuture<Optional<T>> callForPlayer(
            UUID playerId,
            Function<Player, T> function
    ) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CoreDSC is disabled"));
        }
        return current.callForPlayer(playerId, function);
    }

    
    public void runForSender(CommandSender sender, Runnable task) {
        if (sender instanceof Entity entity) {
            runForEntity(entity, task);
        } else if (sender instanceof BlockCommandSender blockSender) {
            runAtLocation(blockSender.getBlock().getLocation(), task);
        } else {
            runSync(task);
        }
    }

    
    public void runAtLocation(Location location, Runnable task) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled() || location == null) {
            return;
        }
        try {
            current.runAtLocation(location, task);
        } catch (Throwable throwable) {
            getLogger().warning("Could not schedule a region task: " + rootMessage(throwable));
        }
    }

    
    public <T> CompletableFuture<T> callAtLocation(Location location, Supplier<T> supplier) {
        CoreScheduler current = coreScheduler;
        if (current == null || !isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CoreDSC is disabled"));
        }
        if (location == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Location is required"));
        }
        return current.callAtLocation(location, supplier);
    }

    



    public ReloadResult reloadConfiguration() {
        if (startupState != StartupState.READY
                || configManager == null || moduleManager == null || discordService == null) {
            return new ReloadResult(false, false,
                    "CoreDSC is not ready and cannot be reloaded safely.", 0, false);
        }

        YamlConfiguration previous = configManager.snapshotActive();
        List<ConfigManager.ConfigIssue> previousConfigIssues = configManager.getConfigIssues();
        List<String> previouslyEnabledModules = moduleManager.enabledModuleIdsSnapshot();
        try {
            configManager.reload();
            discordService.validateConfiguration();
            moduleManager.loadEnabledModules();
            moduleManager.requireNoFailedModules("reload");
            discordService.reload();
            if (metricsService != null) {
                metricsService.reloadConfiguration();
            }
            boolean reconnecting = discordService.getState() == DiscordBotService.State.CONNECTING;
            int warningCount = configManager.getConfigIssues().size();
            String message = "CoreDSC configuration and modules were reloaded."
                    + (warningCount == 0 ? "" : " " + warningCount
                    + " configuration warning(s) remain; see /coredsc doctor.")
                    + (reconnecting ? " Discord is reconnecting; verify it with /coredsc doctor." : "");
            return new ReloadResult(true, true, message, warningCount, reconnecting);
        } catch (Throwable throwable) {
            boolean rollbackSucceeded = false;
            try {
                configManager.restoreActive(previous, previousConfigIssues);
                moduleManager.loadEnabledModules();
                moduleManager.requirePreviouslyEnabledModules(
                        previouslyEnabledModules, "reload rollback");
                discordService.reload();
                if (metricsService != null) {
                    metricsService.reloadConfiguration();
                }
                rollbackSucceeded = true;
            } catch (Throwable rollbackFailure) {
                throwable.addSuppressed(rollbackFailure);
                startupState = StartupState.FAILED;
                startupFailure = "Reload rollback failed: " + rootMessage(rollbackFailure);
                getLogger().log(Level.SEVERE,
                        "CoreDSC reload rollback also failed; restart Paper before making further changes",
                        rollbackFailure);
            }
            if (rollbackSucceeded) {
                getLogger().log(Level.SEVERE,
                        "CoreDSC reload failed; previous runtime configuration restored", throwable);
                return new ReloadResult(false, true,
                        "CoreDSC reload failed and the previous runtime configuration was restored: "
                                + rootMessage(throwable),
                        previousConfigIssues.size(), false);
            }
            getLogger().log(Level.SEVERE,
                    "CoreDSC reload failed and rollback was incomplete", throwable);
            return new ReloadResult(false, false,
                    "CoreDSC reload and rollback both failed. Restart Paper before making further changes. Cause: "
                            + rootMessage(throwable),
                    previousConfigIssues.size(), false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("link") || commandName.equals("unlink")) {
            sender.sendMessage("§cThe CoreDSC Link module is disabled or not ready.");
            return true;
        }
        if (List.of("ticket", "report", "case", "appeal", "apply", "application").contains(commandName)) {
            sender.sendMessage("§cThe corresponding CoreDSC module is disabled or not ready.");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("coredsc")) {
            return false;
        }

        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "status" -> {
                if (!sender.hasPermission("coredsc.status")) {
                    sender.sendMessage("§cYou do not have permission to view CoreDSC status.");
                    return true;
                }
                sendStatus(sender);
                return true;
            }
            case "doctor", "setup" -> {
                if (!sender.hasPermission("coredsc.doctor")) {
                    sender.sendMessage("§cYou do not have permission to run diagnostics.");
                    return true;
                }
                if (doctorService == null) {
                    sender.sendMessage("§cCoreDSC diagnostics are not ready.");
                    return true;
                }
                if (subcommand.equals("setup")) {
                    doctorService.setup(sender, args);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("test")) {
                    doctorService.test(sender, args.length >= 3 ? args[2] : "");
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("fix")) {
                    doctorService.fix(sender);
                } else {
                    doctorService.diagnose(sender);
                }
                return true;
            }
            case "queue" -> {
                if (!sender.hasPermission("coredsc.queue")) {
                    sender.sendMessage("§cYou do not have permission to manage the queue.");
                    return true;
                }
                com.hubertstudios.coredsc.module.impl.DeliveryQueueModule queue = moduleManager == null
                        ? null : moduleManager.getModule(com.hubertstudios.coredsc.module.impl.DeliveryQueueModule.class);
                if (queue == null) {
                    sender.sendMessage("§cThe delivery queue module is disabled.");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("retry")) {
                    queue.retryFailed().whenComplete((count, error) -> runForSender(sender, () -> sender.sendMessage(
                            error == null ? "§aRequeued " + count + " failed message(s)."
                                    : "§cQueue retry failed: " + rootMessage(error))));
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                    queue.clearFailed().whenComplete((count, error) -> runForSender(sender, () -> sender.sendMessage(
                            error == null ? "§aRemoved " + count + " permanently failed message(s)."
                                    : "§cQueue cleanup failed: " + rootMessage(error))));
                } else {
                    queue.counts().whenComplete((counts, error) -> runForSender(sender, () -> sender.sendMessage(
                            error == null ? "§7Queue: §f" + counts[0] + " pending, " + counts[1] + " failed"
                                    : "§cQueue status failed: " + rootMessage(error))));
                }
                return true;
            }
            case "emit" -> {
                if (!sender.hasPermission("coredsc.emit")) {
                    sender.sendMessage("§cYou do not have permission to publish Python events.");
                    return true;
                }
                PythonBotModule python = moduleManager == null ? null : moduleManager.getModule(PythonBotModule.class);
                if (python == null) {
                    sender.sendMessage("§cThe Python developer feature is disabled or unavailable.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§eUsage: /coredsc emit <event> [key=value ...]");
                    return true;
                }
                if (args.length - 2 > 50) {
                    sender.sendMessage("§cToo many fields. A maximum of 50 key=value fields is allowed.");
                    return true;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                for (int index = 2; index < args.length; index++) {
                    String pair = args[index];
                    int equals = pair.indexOf('=');
                    if (equals <= 0) {
                        sender.sendMessage("§cInvalid field '" + pair + "'. Use key=value without spaces.");
                        return true;
                    }
                    String key = pair.substring(0, equals).trim();
                    String value = pair.substring(equals + 1).trim();
                    if (!key.matches("[A-Za-z0-9_.-]{1,64}")) {
                        sender.sendMessage("§cInvalid field name '" + key + "'.");
                        return true;
                    }
                    if (data.containsKey(key)) {
                        sender.sendMessage("§cDuplicate field name '" + key + "'.");
                        return true;
                    }
                    data.put(key, value.length() <= 500 ? value : value.substring(0, 500));
                }
                String source = "command:" + sender.getName();
                python.publishExternalEvent(args[1], data, source)
                        .whenComplete((accepted, error) -> runForSender(sender, () -> {
                            if (error != null) {
                                sender.sendMessage("§cPython event failed: " + rootMessage(error));
                            } else if (Boolean.TRUE.equals(accepted)) {
                                sender.sendMessage("§aPython event '" + args[1].toLowerCase(Locale.ROOT) + "' published.");
                            } else {
                                sender.sendMessage("§eNo loaded Python script handles event '"
                                        + args[1].toLowerCase(Locale.ROOT) + "'.");
                            }
                        }));
                return true;
            }
            case "bot" -> {
                if (!sender.hasPermission("coredsc.bot")) {
                    sender.sendMessage("§cYou do not have permission to manage Python extensions.");
                    return true;
                }
                PythonBotModule python = moduleManager == null ? null : moduleManager.getModule(PythonBotModule.class);
                if (python == null) {
                    sender.sendMessage("§cThe Python developer feature is disabled. Enable it in plugins/CoreDSC/bot/config.yml and reload.");
                    return true;
                }
                String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
                switch (action) {
                    case "status" -> {
                        sender.sendMessage("§7Python: §f" + python.workerState() + " §8(" + python.workerDetail() + ")");
                        sender.sendMessage("§7Scripts: §f" + (python.loadedScripts().isEmpty()
                                ? "none" : String.join(", ", python.loadedScripts())));
                    }
                    case "scripts" -> sender.sendMessage("§7Loaded Python scripts: §f"
                            + (python.loadedScripts().isEmpty() ? "none" : String.join(", ", python.loadedScripts())));
                    case "reload", "restart" -> {
                        sender.sendMessage("§7Restarting the Python worker...");
                        python.restartWorker().whenComplete((snapshot, error) -> runForSender(sender, () -> sender.sendMessage(
                                error == null ? "§aPython scripts reloaded: " + snapshot.scripts().size() + " script(s)."
                                        : "§cPython restart failed: " + rootMessage(error))));
                    }
                    case "stop" -> python.stopWorker().whenComplete((ignored, error) -> runForSender(sender, () -> sender.sendMessage(
                            error == null ? "§ePython worker stopped." : "§cPython stop failed: " + rootMessage(error))));
                    case "start" -> python.startWorker().whenComplete((snapshot, error) -> runForSender(sender, () -> sender.sendMessage(
                            error == null ? "§aPython worker started with " + snapshot.scripts().size() + " script(s)."
                                    : "§cPython start failed: " + rootMessage(error))));
                    default -> sender.sendMessage("§eUsage: /coredsc bot <status|scripts|start|stop|restart>");
                }
                return true;
            }
            case "telemetry" -> {
                if (!sender.hasPermission("coredsc.status")) {
                    sender.sendMessage("§cYou do not have permission to view telemetry status.");
                    return true;
                }
                MetricsService current = metricsService;
                sender.sendMessage("§8§m--------------- §bCoreDSC Telemetry §8§m---------------");
                sender.sendMessage("§7Provider: §fofficial bStats only");
                sender.sendMessage("§7State: §f" + (current == null
                        ? "NOT INITIALISED" : current.getState() + " (" + current.getDetail() + ")"));
                sender.sendMessage("§7Configuration: §fplugins/CoreDSC/telemetry.yml");
                sender.sendMessage("§7Privacy: §fno guild/channel/user IDs, messages, tokens or free-form values");
                return true;
            }
            case "migrate" -> {
                if (!sender.hasPermission("coredsc.migrate")) {
                    sender.sendMessage("§cYou do not have permission to migrate another plugin.");
                    return true;
                }
                if (migrationService == null || startupState != StartupState.READY) {
                    sender.sendMessage("§cCoreDSC migration services are not ready.");
                    return true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("DiscordSRV")) {
                    sender.sendMessage("§eUsage: /coredsc migrate DiscordSRV [preview]");
                    return true;
                }
                boolean preview = args.length >= 3 && args[2].equalsIgnoreCase("preview");
                if (args.length >= 3 && !preview) {
                    sender.sendMessage("§eUsage: /coredsc migrate DiscordSRV [preview]");
                    return true;
                }
                migrationService.migrate(sender, preview);
                return true;
            }
            case "webeditor" -> {
                if (!sender.hasPermission("coredsc.webeditor")) {
                    sender.sendMessage("§cYou do not have permission to manage WebEditor sessions.");
                    return true;
                }
                if (!(sender instanceof ConsoleCommandSender)
                        || sender instanceof RemoteConsoleCommandSender) {
                    sender.sendMessage("§cWebEditor can only be managed from the local server console; RCON is rejected.");
                    return true;
                }
                WebEditorModule editor = moduleManager == null
                        ? null : moduleManager.getModule(WebEditorModule.class);
                if (editor == null) {
                    sender.sendMessage("§cWebEditor is disabled or failed. Enable web-editor and reload CoreDSC first.");
                    return true;
                }
                String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
                switch (action) {
                    case "status" -> sender.sendMessage("§7WebEditor: §f" + editor.statusDetail());
                    case "stop" -> sender.sendMessage(editor.stopSession()
                            ? "§aWebEditor session stopped and its token invalidated."
                            : "§7No WebEditor session was active.");
                    case "start" -> {
                        if (args.length > 3) {
                            sender.sendMessage("§eUsage: /coredsc webeditor start [minutes]");
                            return true;
                        }
                        int minutes = 0;
                        if (args.length == 3) {
                            try {
                                minutes = Integer.parseInt(args[2]);
                            } catch (NumberFormatException error) {
                                sender.sendMessage("§cSession duration must be a whole number of minutes.");
                                return true;
                            }
                        }
                        try {
                            WebEditorModule.SessionInfo info = editor.startSession(minutes);
                            sender.sendMessage("§aTemporary WebEditor session started for "
                                    + info.durationMinutes() + " minute(s).");
                            sender.sendMessage("§eTreat the following URL as a temporary administrator password:");
                            sender.sendMessage(info.url());
                            sender.sendMessage("§7The listener is loopback-only. On a remote host, use an SSH tunnel.");
                        } catch (Exception error) {
                            sender.sendMessage("§cCould not start WebEditor: " + rootMessage(error));
                        }
                    }
                    default -> sender.sendMessage(
                            "§eUsage: /coredsc webeditor <status|start [minutes]|stop>");
                }
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("coredsc.reload")) {
                    sender.sendMessage("§cYou do not have permission to reload CoreDSC.");
                    return true;
                }
                ReloadResult result = reloadConfiguration();
                sender.sendMessage((result.success() ? "§a" : result.rollbackSucceeded() ? "§c" : "§4")
                        + result.message());
                return true;
            }
            default -> {
                sender.sendMessage("§eUsage: /coredsc <status|setup|doctor|queue|bot|emit|telemetry|migrate|webeditor|reload>");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("coredsc")) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setup")) {
            return List.of("check", "set", "enable", "disable").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setup") && args[1].equalsIgnoreCase("set")) {
            return List.of("guild", "link-role", "chat", "events", "console", "voice-category", "voice-lobby", "booster-role", "tickets", "reports", "applications", "appeals").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setup")
                && (args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))) {
            return List.of("placeholderapi", "delivery-queue", "network", "link", "link-rewards",
                            "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync", "chat-sync", "console",
                            "server-events", "custom-commands", "status-channels", "cases", "moderation-bridge",
                            "tickets", "reports", "applications", "workflows", "authme", "voicechat-sync",
                            "economy-market", "lore-sync", "competitive", "web-editor", "python-bot").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("doctor")) {
            return List.of("test", "fix").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("doctor")
                && args[1].equalsIgnoreCase("test")) {
            return List.of("chat", "events", "console", "tickets", "rolesync", "link", "link-rewards", "nickname", "booster", "bans", "voice").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("queue")) {
            return List.of("status", "retry", "clear").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bot")) {
            return List.of("status", "scripts", "start", "stop", "restart").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            return "DiscordSRV".toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("DiscordSRV") : List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("migrate")
                && args[1].equalsIgnoreCase("DiscordSRV")) {
            return "preview".startsWith(args[2].toLowerCase(Locale.ROOT))
                    ? List.of("preview") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("webeditor")
                && sender.hasPermission("coredsc.webeditor")) {
            return List.of("status", "start", "stop").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (sender.hasPermission("coredsc.status") && "status".startsWith(prefix)) {
            suggestions.add("status");
        }
        if (sender.hasPermission("coredsc.reload") && "reload".startsWith(prefix)) {
            suggestions.add("reload");
        }
        if (sender.hasPermission("coredsc.doctor") && "doctor".startsWith(prefix)) suggestions.add("doctor");
        if (sender.hasPermission("coredsc.doctor") && "setup".startsWith(prefix)) suggestions.add("setup");
        if (sender.hasPermission("coredsc.queue") && "queue".startsWith(prefix)) {
            suggestions.add("queue");
        }
        if (sender.hasPermission("coredsc.bot") && "bot".startsWith(prefix)) suggestions.add("bot");
        if (sender.hasPermission("coredsc.emit") && "emit".startsWith(prefix)) suggestions.add("emit");
        if (sender.hasPermission("coredsc.status") && "telemetry".startsWith(prefix)) suggestions.add("telemetry");
        if (sender.hasPermission("coredsc.migrate") && "migrate".startsWith(prefix)) suggestions.add("migrate");
        if (sender.hasPermission("coredsc.webeditor") && "webeditor".startsWith(prefix)) {
            suggestions.add("webeditor");
        }
        return suggestions;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§8§m---------------- §bCoreDSC §8§m----------------");
        sender.sendMessage("§7Core: §f" + startupState
                + (startupFailure.isBlank() ? "" : " §8(" + startupFailure + ")"));

        ConfigManager.MigrationSummary migration = configManager == null
                ? null : configManager.getMigrationSummary();
        sender.sendMessage("§7Configuration schema: §f" + ConfigManager.CURRENT_CONFIG_VERSION
                + (migration != null && migration.migrated() ? " §8(migrated this startup)" : ""));
        if (configManager != null && !configManager.getConfigIssues().isEmpty()) {
            sender.sendMessage("§eConfiguration warnings: §f" + configManager.getConfigIssues().size()
                    + " §8(run /coredsc doctor)");
        }
// did you know that if you go to https://coredsc.pages.dev/ooo you can play a fun little game?
        sender.sendMessage("§7Storage: §f" + (storage == null
                ? "NOT INITIALISED"
                : storage.getState() + " §8(queue " + storage.getQueuedOperationCount()
                + "/" + storage.getQueueCapacity() + ", peak " + storage.getQueueHighWaterMark()
                + ", rejected " + storage.getRejectedOperationCount() + ")"
                + (storage.getFailureReason().isBlank()
                ? "" : " §c(" + storage.getFailureReason() + ")")));
        sender.sendMessage("§7Discord: §f" + (discordService == null
                ? "NOT INITIALISED"
                : discordService.getState() + (discordService.getFailureReason().isBlank()
                ? "" : " (" + discordService.getFailureReason() + ")")));
        if (discordService != null) {
            sender.sendMessage("§7Guild resolution: §f" + discordService.getGuildResolutionState()
                    + " §8(" + discordService.getGuildResolutionDetail() + ")");
            sender.sendMessage("§7Discord commands: §f" + discordService.getCommandRegistrationState()
                    + " §8(" + discordService.getCommandRegistrationDetail() + ")");
        }
        sender.sendMessage("§7Enabled modules: §f" + (moduleManager == null
                ? "none"
                : moduleManager.enabledModuleSummary()));

        if (moduleManager != null) {
            moduleManager.getStatuses().forEach((id, status) -> {
                if (status.state() == ModuleManager.ModuleState.FAILED) {
                    sender.sendMessage("§cModule " + id + ": FAILED (" + status.detail() + ")");
                }
            });
        }
        sender.sendMessage("§8§m-----------------------------------------");
    }


    private void printStartupActionSummary() {
        if (!isEnabled() || startupState != StartupState.READY) {
            return;
        }
        LinkedHashSet<String> unresolved = new LinkedHashSet<>(getStartupWarnings());
        ConfigManager currentConfig = configManager;
        if (currentConfig != null) {
            for (ConfigManager.ConfigIssue issue : currentConfig.getConfigIssues()) {
                unresolved.add(issue.file() + ": " + issue.message());
            }
        }
        ModuleManager currentModules = moduleManager;
        if (currentModules != null) {
            currentModules.getStatuses().forEach((id, status) -> {
                if (status.state() == ModuleManager.ModuleState.FAILED) {
                    unresolved.add("Module " + id + " failed: " + status.detail());
                }
            });
        }
        DiscordBotService currentDiscord = discordService;
        if (currentDiscord != null) {
            if (currentDiscord.getState() == DiscordBotService.State.FAILED) {
                unresolved.add("Discord connection failed: " + currentDiscord.getFailureReason());
            } else if (currentDiscord.getState() == DiscordBotService.State.CONNECTING) {
                unresolved.add("Discord is still connecting; run /coredsc doctor if this persists.");
            }
            DiscordBotService.GuildResolutionState guildState = currentDiscord.getGuildResolutionState();
            if (guildState == DiscordBotService.GuildResolutionState.NOT_VISIBLE
                    || (guildState == DiscordBotService.GuildResolutionState.WAITING
                    && currentDiscord.getState() == DiscordBotService.State.READY)) {
                unresolved.add("Guild resolution is " + guildState + ": "
                        + currentDiscord.getGuildResolutionDetail());
            }
            if (currentDiscord.getCommandRegistrationState()
                    == DiscordBotService.CommandRegistrationState.FAILED) {
                unresolved.add("Discord command registration failed: "
                        + currentDiscord.getCommandRegistrationDetail());
            }
        }
        if (unresolved.isEmpty()) {
            return;
        }
        getLogger().warning("Startup completed with " + unresolved.size() + " action item(s):");
        int shown = 0;
        for (String warning : unresolved) {
            if (shown++ >= 12) {
                getLogger().warning("- " + (unresolved.size() - 12)
                        + " additional item(s); run /coredsc doctor for details.");
                break;
            }
            getLogger().warning("- " + warning);
        }
        getLogger().warning("Run /coredsc doctor for the complete diagnostic report.");
    }

    private static String moduleForFeature(String feature) {
        if (feature == null) {
            return null;
        }
        return switch (feature) {
            case "application_created" -> "applications";
            case "ban_sync" -> "ban-sync";
            case "booster_reward" -> "booster-rewards";
            case "chat_discord_to_mc", "chat_mc_to_discord" -> "chat-sync";
            case "custom_command" -> "custom-commands";
            case "competitive_match", "competitive_leaderboard" -> "competitive";
            case "delivery_queued" -> "delivery-queue";
            case "economy_balance", "economy_inventory", "economy_market" -> "economy-market";
            case "link_code_created", "account_linked", "account_unlinked" -> "link";
            case "link_reward" -> "link-rewards";
            case "lore_event" -> "lore-sync";
            case "role_sync" -> "luckperms-sync";
            case "nickname_sync" -> "nickname-sync";
            case "python_execution" -> "python-bot";
            case "report_created" -> "reports";
            case "server_event" -> "server-events";
            case "status_update" -> "status-channels";
            case "smart_console_incident" -> "console";
            case "ticket_created" -> "tickets";
            case "workflow_run" -> "workflows";
            case "web_editor_session", "web_editor_save", "web_editor_structured_save" -> "web-editor";
            default -> null;
        };
    }

    private void failAndDisable(String context, Throwable throwable) {
        startupState = StartupState.FAILED;
        startupFailure = context + ": " + rootMessage(throwable);
        getLogger().log(Level.SEVERE, startupFailure, throwable);
        if (isEnabled()) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void printBanner(String status) {
        String mode = getAppConfig().getString("startup-banner.mode", "FULL");
        mode = mode == null ? "FULL" : mode.trim().toUpperCase(Locale.ROOT);
        if (mode.equals("OFF")) {
            return;
        }
        boolean colors = getAppConfig().getBoolean("startup-banner.colors", true);
        if (mode.equals("COMPACT")) {
            bannerLog("CoreDSC v" + getDescription().getVersion() + " | HubertStudios | " + status,
                    ChatColor.AQUA, colors);
            return;
        }

        List<String> logo = List.of(
                "██╗  ██╗██╗   ██╗██████╗ ███████╗██████╗ ████████╗",
                "██║  ██║██║   ██║██╔══██╗██╔════╝██╔══██╗╚══██╔══╝",
                "███████║██║   ██║██████╔╝█████╗  ██████╔╝   ██║   ",
                "██╔══██║██║   ██║██╔══██╗██╔══╝  ██╔══██╗   ██║   ",
                "██║  ██║╚██████╔╝██████╔╝███████╗██║  ██║   ██║   ",
                "╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝   ╚═╝   ",
                ".             S  T  U  D  I  O  S"
        );
        int width = 67;
        bannerLog("╔" + "═".repeat(width) + "╗", ChatColor.DARK_AQUA, colors);
        bannerLog(box("", width), ChatColor.DARK_AQUA, colors);
        for (String row : logo) {
            bannerLog(box(row, width), ChatColor.AQUA, colors);
        }
        bannerLog(box("", width), ChatColor.DARK_AQUA, colors);
        bannerLog(box("Plugin   » CoreDSC v" + getDescription().getVersion(), width), ChatColor.WHITE, colors);
        bannerLog(box("Author   » HubertStudios", width), ChatColor.WHITE, colors);
        bannerLog(box("Status   » " + status, width), ChatColor.GREEN, colors);
        bannerLog(box("", width), ChatColor.DARK_AQUA, colors);
        bannerLog("╚" + "═".repeat(width) + "╝", ChatColor.DARK_AQUA, colors);
    }

    private void bannerLog(String line, ChatColor color, boolean colors) {
        getLogger().info((colors ? color.toString() : "") + line + (colors ? ChatColor.RESET : ""));
    }

    private static String box(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() > width - 2) {
            text = text.substring(0, width - 2);
        }
        int left = text.isBlank() ? 0 : 2;
        int right = width - text.length() - left;
        return "║" + " ".repeat(left) + text + " ".repeat(Math.max(0, right)) + "║";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
