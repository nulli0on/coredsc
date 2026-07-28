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
import com.hubertstudios.coredsc.service.PlaceholderService;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.metrics.MetricsService;
import com.hubertstudios.coredsc.voice.VoiceChatBridgeBootstrap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Main entry point for CoreDSC. */
public final class CoreDSCPlugin extends JavaPlugin {
    public enum StartupState {
        INITIALIZING,
        STARTING_STORAGE,
        STARTING_SERVICES,
        READY,
        FAILED,
        DISABLING
    }

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
    private final AtomicBoolean startupAnnouncementPending = new AtomicBoolean();
    private final AtomicBoolean startupAnnouncementSent = new AtomicBoolean();

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
        // Hi
        voiceChatBridge = VoiceChatBridgeBootstrap.registerEarly(this);

        saveResourceIfMissing("license.yml");
        saveResourceIfMissing("secrets.yml");
        printBanner("Open-source build starting");
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

            // Hi?
            discordService.start();
            startupState = StartupState.READY;
            startupFailure = "";
            metricsService = new MetricsService(this);
            metricsService.start();
            getLogger().info("CoreDSC is ready. Discord state: " + discordService.getState());
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
                try {
                    serverEvents.sendShutdownEvent().get(3L, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    getLogger().warning("Shutdown event delivery was interrupted: " + rootMessage(error));
                } catch (TimeoutException error) {
                    getLogger().warning("Shutdown event delivery exceeded 3 seconds and was abandoned.");
                } catch (Exception error) {
                    getLogger().warning("Shutdown event delivery failed: " + rootMessage(error));
                }
            }

            // Hi, I am ChatGPT, ask me anything; I know everything.
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
        getServer().getServicesManager().unregisterAll(this);
        if (storage != null) {
            try {
                storage.closeAsync().get(5L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                getLogger().warning("SQLite shutdown was interrupted: " + rootMessage(error));
            } catch (TimeoutException error) {
                getLogger().warning("SQLite shutdown exceeded 5 seconds; pending writes may remain incomplete.");
            } catch (Exception error) {
                getLogger().warning("SQLite shutdown failed: " + rootMessage(error));
            }
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
        storage = null;
        instance = null;
        getLogger().info("CoreDSC disabled.");
    }

    //Hi, again
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (!isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (Throwable throwable) {
            getLogger().warning("Could not schedule a main-thread task: " + rootMessage(throwable));
        }
    }

    //Hey boy
    public <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
            return future;
        }
        if (!isEnabled()) {
            future.completeExceptionally(new IllegalStateException("CoreDSC is disabled"));
            return future;
        }
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
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
                    queue.retryFailed().whenComplete((count, error) -> runSync(() -> sender.sendMessage(
                            error == null ? "§aRequeued " + count + " failed message(s)."
                                    : "§cQueue retry failed: " + rootMessage(error))));
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                    queue.clearFailed().whenComplete((count, error) -> runSync(() -> sender.sendMessage(
                            error == null ? "§aRemoved " + count + " permanently failed message(s)."
                                    : "§cQueue cleanup failed: " + rootMessage(error))));
                } else {
                    queue.counts().whenComplete((counts, error) -> runSync(() -> sender.sendMessage(
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
                        .whenComplete((accepted, error) -> runSync(() -> {
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
                        python.restartWorker().whenComplete((snapshot, error) -> runSync(() -> sender.sendMessage(
                                error == null ? "§aPython scripts reloaded: " + snapshot.scripts().size() + " script(s)."
                                        : "§cPython restart failed: " + rootMessage(error))));
                    }
                    case "stop" -> python.stopWorker().whenComplete((ignored, error) -> runSync(() -> sender.sendMessage(
                            error == null ? "§ePython worker stopped." : "§cPython stop failed: " + rootMessage(error))));
                    case "start" -> python.startWorker().whenComplete((snapshot, error) -> runSync(() -> sender.sendMessage(
                            error == null ? "§aPython worker started with " + snapshot.scripts().size() + " script(s)."
                                    : "§cPython start failed: " + rootMessage(error))));
                    default -> sender.sendMessage("§eUsage: /coredsc bot <status|scripts|start|stop|restart>");
                }
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
            case "reload" -> {
                if (!sender.hasPermission("coredsc.reload")) {
                    sender.sendMessage("§cYou do not have permission to reload CoreDSC.");
                    return true;
                }
                if (startupState != StartupState.READY
                        || moduleManager == null || discordService == null) {
                    sender.sendMessage("§cCoreDSC is not ready and cannot be reloaded safely.");
                    return true;
                }

                try {
                    configManager.reload();
                    discordService.validateConfiguration();
                    moduleManager.loadEnabledModules();
                    discordService.reload();
                    if (metricsService != null) {
                        metricsService.refreshSnapshot();
                    }
                    if (moduleManager.hasFailedModules()) {
                        sender.sendMessage("§eCoreDSC reloaded, but these modules failed: §f"
                                + moduleManager.failedModuleSummary()
                                + "§e. Check the console and /coredsc status.");
                    } else {
                        sender.sendMessage("§aCoreDSC configuration and modules were reloaded.");
                    }
                } catch (Throwable throwable) {
                    getLogger().log(Level.SEVERE, "CoreDSC reload failed", throwable);
                    sender.sendMessage("§cCoreDSC reload failed: " + rootMessage(throwable));
                }
                return true;
            }
            default -> {
                sender.sendMessage("§eUsage: /coredsc <status|setup|doctor|queue|bot|emit|migrate|reload>");
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
                            "tickets", "reports", "applications", "workflows", "authme", "voicechat-sync", "python-bot").stream()
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
        if (sender.hasPermission("coredsc.migrate") && "migrate".startsWith(prefix)) suggestions.add("migrate");
        return suggestions;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§8§m---------------- §bCoreDSC §8§m----------------");
        sender.sendMessage("§7Core: §f" + startupState
                + (startupFailure.isBlank() ? "" : " §8(" + startupFailure + ")"));

        sender.sendMessage("§7v2.4.1");
        sender.sendMessage("§7bStats metrics: §f" + (metricsService == null
                ? "NOT INITIALISED"
                : metricsService.getState() + " (" + metricsService.getDetail() + ")"));
        sender.sendMessage("§7License identifier: §f" + (metricsService != null
                && metricsService.isLicenseConfigured() ? "CONFIGURED" : "NOT CONFIGURED"));

        sender.sendMessage("§7Storage: §f" + (storage == null
                ? "NOT INITIALISED"
                : storage.getState() + (storage.getFailureReason().isBlank()
                ? "" : " (" + storage.getFailureReason() + ")")));
        sender.sendMessage("§7Discord: §f" + (discordService == null
                ? "NOT INITIALISED"
                : discordService.getState() + (discordService.getFailureReason().isBlank()
                ? "" : " (" + discordService.getFailureReason() + ")")));
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

    private void failAndDisable(String context, Throwable throwable) {
        startupState = StartupState.FAILED;
        startupFailure = context + ": " + rootMessage(throwable);
        getLogger().log(Level.SEVERE, startupFailure, throwable);
        if (isEnabled()) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void saveResourceIfMissing(String path) {
        File output = new File(getDataFolder(), path);
        if (output.exists()) {
            return;
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create data directory for " + path);
        }
        saveResource(path, false);
    }

    private void printBanner(String status) {
        getLogger().info("========================================");
        getLogger().info("CoreDSC v" + getDescription().getVersion());
        getLogger().info("HubertStudios | " + status);
        getLogger().info("========================================");
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
//If you love my code that much consider donating.
