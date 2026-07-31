package com.hubertstudios.coredsc.module;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.impl.AuthMeModule;
import com.hubertstudios.coredsc.module.impl.CustomCommandsModule;
import com.hubertstudios.coredsc.module.impl.ChatSyncModule;
import com.hubertstudios.coredsc.module.impl.LuckPermsSyncModule;
import com.hubertstudios.coredsc.module.impl.ModerationBridgeModule;
import com.hubertstudios.coredsc.module.impl.PlaceholderAPIModule;
import com.hubertstudios.coredsc.module.impl.ServerEventsModule;
import com.hubertstudios.coredsc.module.impl.LinkModule;
import com.hubertstudios.coredsc.module.impl.StatusChannelModule;
import com.hubertstudios.coredsc.module.impl.TicketModule;
import com.hubertstudios.coredsc.module.impl.VoiceChatSyncModule;
import com.hubertstudios.coredsc.module.impl.DeliveryQueueModule;
import com.hubertstudios.coredsc.module.impl.NetworkModule;
import com.hubertstudios.coredsc.module.impl.CaseModule;
import com.hubertstudios.coredsc.module.impl.ReportModule;
import com.hubertstudios.coredsc.module.impl.ApplicationModule;
import com.hubertstudios.coredsc.module.impl.WorkflowModule;
import com.hubertstudios.coredsc.module.impl.PythonBotModule;
import com.hubertstudios.coredsc.module.impl.BanSyncModule;
import com.hubertstudios.coredsc.module.impl.BoosterRewardsModule;
import com.hubertstudios.coredsc.module.impl.ConsoleModule;
import com.hubertstudios.coredsc.module.impl.LinkRewardsModule;
import com.hubertstudios.coredsc.module.impl.NicknameSyncModule;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.logging.Level;

                                                                            
public final class ModuleManager {

    public enum ModuleState {
        DISABLED,
        ENABLED,
        FAILED
    }

    public record ModuleStatus(ModuleState state, String detail) { }

    public record OperationalStatus(
            Instant enabledAt,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastFailure,
            long successfulActions
    ) { }

    private final CoreDSCPlugin plugin;
    private final Map<String, CoreModule> enabledModules = new LinkedHashMap<>();
    private final Map<String, ModuleStatus> statuses = new LinkedHashMap<>();
    private final Map<String, MutableOperationalStatus> operationalStatuses = new LinkedHashMap<>();

    public ModuleManager(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void loadEnabledModules() {
        disableModules();
        statuses.clear();

        FileConfiguration config = plugin.getAppConfig();
        List<ModuleSelection> selections = List.of(
                new ModuleSelection("placeholderapi", config.getBoolean("modules.placeholderapi", true),
                        () -> new PlaceholderAPIModule(plugin)),
                new ModuleSelection("delivery-queue", config.getBoolean("modules.delivery-queue", true),
                        () -> new DeliveryQueueModule(plugin)),
                new ModuleSelection("network", config.getBoolean("modules.network", false),
                        () -> new NetworkModule(plugin)),
                new ModuleSelection("link", config.getBoolean("modules.link", true),
                        () -> new LinkModule(plugin)),
                new ModuleSelection("link-rewards", config.getBoolean("modules.link-rewards", false),
                        () -> new LinkRewardsModule(plugin)),
                new ModuleSelection("nickname-sync", config.getBoolean("modules.nickname-sync", false),
                        () -> new NicknameSyncModule(plugin)),
                new ModuleSelection("booster-rewards", config.getBoolean("modules.booster-rewards", false),
                        () -> new BoosterRewardsModule(plugin)),
                new ModuleSelection("ban-sync", config.getBoolean("modules.ban-sync", false),
                        () -> new BanSyncModule(plugin)),
                new ModuleSelection("luckperms-sync", config.getBoolean("modules.luckperms-sync", false),
                        () -> new LuckPermsSyncModule(plugin)),
                new ModuleSelection("chat-sync", config.getBoolean("modules.chat-sync", true),
                        () -> new ChatSyncModule(plugin)),
                new ModuleSelection("console", config.getBoolean("modules.console", false),
                        () -> new ConsoleModule(plugin)),
                new ModuleSelection("server-events", config.getBoolean("modules.server-events", false),
                        () -> new ServerEventsModule(plugin)),
                new ModuleSelection("custom-commands", config.getBoolean("modules.custom-commands", false),
                        () -> new CustomCommandsModule(plugin)),
                new ModuleSelection("status-channels", config.getBoolean("modules.status-channels", true),
                        () -> new StatusChannelModule(plugin)),
                new ModuleSelection("cases", config.getBoolean("modules.cases", false),
                        () -> new CaseModule(plugin)),
                new ModuleSelection("moderation-bridge", config.getBoolean("modules.moderation-bridge", false),
                        () -> new ModerationBridgeModule(plugin)),
                new ModuleSelection("tickets", config.getBoolean("modules.tickets", false),
                        () -> new TicketModule(plugin)),
                new ModuleSelection("reports", config.getBoolean("modules.reports", false),
                        () -> new ReportModule(plugin)),
                new ModuleSelection("applications", config.getBoolean("modules.applications", false),
                        () -> new ApplicationModule(plugin)),
                new ModuleSelection("workflows", config.getBoolean("modules.workflows", false),
                        () -> new WorkflowModule(plugin)),
                new ModuleSelection("authme", config.getBoolean("modules.authme", false),
                        () -> new AuthMeModule(plugin)),
                new ModuleSelection("voicechat-sync", config.getBoolean("modules.voicechat-sync", false),
                        () -> new VoiceChatSyncModule(plugin)),
                new ModuleSelection("python-bot", config.getBoolean("modules.python-bot", false),
                        () -> new PythonBotModule(plugin))
        );

        for (ModuleSelection selection : selections) {
            if (!selection.enabled()) {
                statuses.put(selection.id(), new ModuleStatus(ModuleState.DISABLED, "disabled in modular config"));
                continue;
            }

            CoreModule module = null;
            try {
                module = selection.factory().create();
                module.enable();
                enabledModules.put(module.id(), module);
                statuses.put(module.id(), new ModuleStatus(ModuleState.ENABLED, module.statusDetail()));
                operationalStatuses.computeIfAbsent(module.id(), ignored -> new MutableOperationalStatus())
                        .markEnabled();
                plugin.getLogger().info("Enabled module: " + module.id());
            } catch (Throwable throwable) {
                if (module != null) {
                    try {
                        module.disable();
                    } catch (Throwable cleanupFailure) {
                        throwable.addSuppressed(cleanupFailure);
                    }
                }
                String moduleId = module == null ? selection.id() : module.id();
                String detail = rootMessage(throwable);
                statuses.put(moduleId, new ModuleStatus(ModuleState.FAILED, detail));
                operationalStatuses.computeIfAbsent(moduleId, ignored -> new MutableOperationalStatus())
                        .markFailure(throwable);
                plugin.addStartupWarning("Module " + moduleId + " failed: " + detail);
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to enable module " + moduleId + ": " + detail, throwable);
            }
        }
    }

    public synchronized void disableModules() {
        List<CoreModule> reverseOrder = new ArrayList<>(enabledModules.values());
        Collections.reverse(reverseOrder);
        for (CoreModule module : reverseOrder) {
            try {
                module.disable();
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING,
                        "Error while disabling module " + module.id() + ": " + rootMessage(throwable),
                        throwable);
            }
        }
        enabledModules.clear();
    }


    public synchronized void recordSuccessfulAction(String moduleId) {
        if (moduleId == null || moduleId.isBlank() || !enabledModules.containsKey(moduleId)) {
            return;
        }
        operationalStatuses.computeIfAbsent(moduleId, ignored -> new MutableOperationalStatus())
                .markSuccess();
    }

    public synchronized void recordFailedAction(String moduleId, Throwable error) {
        recordFailedAction(moduleId, error == null ? "unknown failure" : rootMessage(error));
    }

    public synchronized void recordFailedAction(String moduleId, String detail) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        operationalStatuses.computeIfAbsent(moduleId, ignored -> new MutableOperationalStatus())
                .markFailure(detail);
    }

    public synchronized Map<String, OperationalStatus> getOperationalStatuses() {
        Map<String, OperationalStatus> snapshot = new LinkedHashMap<>();
        operationalStatuses.forEach((id, value) -> snapshot.put(id, value.snapshot()));
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized OperationalStatus getOperationalStatus(String moduleId) {
        MutableOperationalStatus status = operationalStatuses.get(moduleId);
        return status == null ? null : status.snapshot();
    }

    public synchronized boolean isModuleEnabled(String id) {
        return enabledModules.containsKey(id);
    }

    public synchronized Map<String, ModuleStatus> getStatuses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    public synchronized String enabledModuleSummary() {
        return enabledModules.isEmpty() ? "none" : String.join(", ", enabledModules.keySet());
    }

    public synchronized boolean hasFailedModules() {
        return statuses.values().stream()
                .anyMatch(status -> status.state() == ModuleState.FAILED);
    }

     
                                                                               
                                                                               
                                                                      
       
    public synchronized void requireNoFailedModules(String operation) {
        if (!hasFailedModules()) {
            return;
        }
        String context = operation == null || operation.isBlank() ? "module load" : operation.trim();
        throw new IllegalStateException(context + " left failed modules: " + failedModuleSummary());
    }

    public synchronized String failedModuleSummary() {
        List<String> failed = new ArrayList<>();
        statuses.forEach((id, status) -> {
            if (status.state() == ModuleState.FAILED) {
                failed.add(id + " (" + status.detail() + ")");
            }
        });
        return String.join(", ", failed);
    }


    public synchronized List<String> enabledModuleIdsSnapshot() {
        return List.copyOf(enabledModules.keySet());
    }

     
                                                                         
                                                                         
                                                                       
       
    public synchronized void requirePreviouslyEnabledModules(
            List<String> previouslyEnabled,
            String operation
    ) {
        List<String> missing = new ArrayList<>();
        for (String id : previouslyEnabled == null ? List.<String>of() : previouslyEnabled) {
            if (!enabledModules.containsKey(id)) {
                ModuleStatus status = statuses.get(id);
                missing.add(status == null ? id : id + " (" + status.detail() + ")");
            }
        }
        if (!missing.isEmpty()) {
            String context = operation == null || operation.isBlank()
                    ? "module restoration" : operation.trim();
            throw new IllegalStateException(context + " did not restore previously working modules: "
                    + String.join(", ", missing));
        }
    }

    public synchronized List<CoreModule> getEnabledModulesSnapshot() {
        return List.copyOf(enabledModules.values());
    }

    public synchronized <T extends CoreModule> T getModule(Class<T> type) {
        for (CoreModule module : enabledModules.values()) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }


    private static final class MutableOperationalStatus {
        private Instant enabledAt;
        private Instant lastSuccessAt;
        private Instant lastFailureAt;
        private String lastFailure = "";
        private long successfulActions;

        private void markEnabled() {
            if (enabledAt == null) {
                enabledAt = Instant.now();
            }
        }

        private void markSuccess() {
            lastSuccessAt = Instant.now();
            successfulActions++;
        }

        private void markFailure(Throwable error) {
            markFailure(error == null ? "unknown failure" : rootMessage(error));
        }

        private void markFailure(String detail) {
            lastFailureAt = Instant.now();
            lastFailure = detail == null || detail.isBlank() ? "unknown failure" : detail.trim();
        }

        private OperationalStatus snapshot() {
            return new OperationalStatus(enabledAt, lastSuccessAt, lastFailureAt,
                    lastFailure, successfulActions);
        }
    }

    @FunctionalInterface
    private interface ModuleFactory {
        CoreModule create();
    }

    private record ModuleSelection(String id, boolean enabled, ModuleFactory factory) { }
}
