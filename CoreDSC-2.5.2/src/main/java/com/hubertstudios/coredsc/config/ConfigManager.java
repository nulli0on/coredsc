package com.hubertstudios.coredsc.config;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

 
                                                                               
                                                 
  
                                                                             
                                                                              
                                                                        
   
public final class ConfigManager {
    public static final int CURRENT_CONFIG_VERSION = 4;

    public enum ConfigIssueKind {
        UNKNOWN_KEY,
        DEPRECATED_SETTING,
        OBSOLETE_FILE
    }

    public record ConfigIssue(
            ConfigIssueKind kind,
            String file,
            String path,
            String suggestion,
            String message
    ) { }

    private static final List<String> MODULE_IDS = List.of(
            "placeholderapi", "delivery-queue", "network", "link", "link-rewards",
            "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync",
            "chat-sync", "console", "server-events", "custom-commands", "status-channels", "cases",
            "moderation-bridge", "tickets", "reports", "applications", "workflows",
            "authme", "voicechat-sync"
    );
    private static final Set<String> GLOBAL_ROOTS = Set.of(
            "config-version", "generated-by-version", "language", "debug", "startup-banner", "discord", "storage", "performance"
    );
    private static final Set<String> FILE_METADATA = Set.of("config-version", "generated-by-version");

    private final CoreDSCPlugin plugin;
    private final File dataFolder;
    private final Map<String, File> moduleFiles = new LinkedHashMap<>();
    private volatile YamlConfiguration active = new YamlConfiguration();
    private volatile MigrationSummary migrationSummary = MigrationSummary.none(CURRENT_CONFIG_VERSION);
    private volatile List<ConfigIssue> configIssues = List.of();

    private final Map<Path, Path> sessionBackups = new LinkedHashMap<>();
    private final Set<Path> sessionCreatedFiles = new LinkedHashSet<>();
    private final List<String> sessionChangedFiles = new ArrayList<>();
    private Path sessionBackupRoot;

    public ConfigManager(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder();
        for (String id : MODULE_IDS) {
            moduleFiles.put(id, new File(dataFolder, "modules/" + id + ".yml"));
        }
    }

    public synchronized void initialize() throws IOException, InvalidConfigurationException {
        beginMigrationSession();
        try {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                throw new IOException("Could not create CoreDSC data directory: " + dataFolder);
            }

            File root = new File(dataFolder, "config.yml");
            ensureResourceCreated("config.yml", root);
            YamlConfiguration existingRoot = loadStrict(root);
            if (isLegacy(existingRoot)) {
                migrateLegacy(existingRoot, root);
            }

            prepareManagedYaml("config.yml");
            prepareManagedYaml("messages.yml");
                                                                             
                                                                               
            prepareManagedYaml("telemetry.yml");
            migrateObsoleteLicenseMetrics();
            prepareManagedYaml("secrets.yml");
            prepareManagedYaml("guis/report-gui.yml");
            prepareManagedYaml("bot/config.yml");
            prepareManagedText("bot/README.md");

                                                                                         
                                                                                         
            prepareManagedResource("bot/runtime/worker.py", new File(dataFolder, "bot/runtime/worker.py"));
            prepareManagedResource("bot/runtime/coredsc_api.py", new File(dataFolder, "bot/runtime/coredsc_api.py"));
            ensureResourceCreated("bot/scripts/example_command.py", new File(dataFolder, "bot/scripts/example_command.py"));
            ensureResourceCreated("bot/scripts/example_event.py", new File(dataFolder, "bot/scripts/example_event.py"));
            ensureResourceCreated("bot/scripts/example_external_event.py", new File(dataFolder, "bot/scripts/example_external_event.py"));

            for (String id : MODULE_IDS) {
                prepareManagedYaml("modules/" + id + ".yml");
            }

            File obsoleteLicense = new File(dataFolder, "license.yml");
            if (obsoleteLicense.isFile()) {
                plugin.getLogger().warning("license.yml is obsolete and no longer active. Its former bStats preference "
                        + "was preserved in telemetry.yml when valid; the old file can be removed after verifying the upgrade.");
            }

            reload();
            finishMigrationSession();
        } catch (Throwable error) {
            rollbackMigrationSession(error);
            if (error instanceof InvalidConfigurationException invalidConfiguration) {
                throw invalidConfiguration;
            }
            if (error instanceof IOException io) {
                throw io;
            }
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new IOException("Unexpected configuration initialisation failure", error);
        } finally {
            clearMigrationSession();
        }
    }

    public synchronized void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration merged = buildRuntimeSnapshot();
        validate(merged);
        List<ConfigIssue> detectedIssues = inspectConfigurationKeys();
        active = merged;
        configIssues = List.copyOf(detectedIssues);
        logConfigurationIssues(detectedIssues);
    }

                                                                                  
    public synchronized YamlConfiguration snapshotActive() {
        return cloneYaml(active);
    }

                                                                               
    public synchronized void restoreActive(YamlConfiguration snapshot) {
        restoreActive(snapshot, configIssues);
    }

                                                                            
    public synchronized void restoreActive(YamlConfiguration snapshot, List<ConfigIssue> issues) {
        active = cloneYaml(Objects.requireNonNull(snapshot, "snapshot"));
        configIssues = issues == null ? List.of() : List.copyOf(issues);
    }

    public FileConfiguration getConfig() {
        return active;
    }

    public MigrationSummary getMigrationSummary() {
        return migrationSummary;
    }

    public List<ConfigIssue> getConfigIssues() {
        return configIssues;
    }

    public File getBotDirectory() {
        return new File(dataFolder, "bot");
    }

    public synchronized void setModuleEnabled(String moduleId, boolean enabled)
            throws IOException, InvalidConfigurationException {
        File file;
        if (moduleId.equals("python-bot")) {
            file = new File(dataFolder, "bot/config.yml");
        } else {
            file = moduleFiles.get(moduleId);
        }
        if (file == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }
        YamlConfiguration yaml = loadStrict(file);
        yaml.set("enabled", enabled);
        stamp(yaml);
        saveAtomic(yaml, file);
    }

                                                                           
    public synchronized void setValue(String runtimePath, Object value)
            throws IOException, InvalidConfigurationException {
        if (runtimePath == null || runtimePath.isBlank()) {
            throw new IllegalArgumentException("Configuration path is blank");
        }
        if (runtimePath.startsWith("modules.")) {
            setModuleEnabled(runtimePath.substring("modules.".length()), Boolean.parseBoolean(String.valueOf(value)));
            return;
        }
        if (runtimePath.startsWith("reports.gui.")) {
            setInFile(new File(dataFolder, "guis/report-gui.yml"),
                    runtimePath.substring("reports.gui.".length()), value);
            return;
        }
        if (runtimePath.startsWith("bot.")) {
            setInFile(new File(dataFolder, "bot/config.yml"), runtimePath.substring(4), value);
            return;
        }

        int dot = runtimePath.indexOf('.');
        String root = dot < 0 ? runtimePath : runtimePath.substring(0, dot);
        if (GLOBAL_ROOTS.contains(root)) {
            setInFile(new File(dataFolder, "config.yml"), runtimePath, value);
            return;
        }
        File moduleFile = moduleFiles.get(root);
        if (moduleFile == null) {
            throw new IllegalArgumentException("No modular config owns path: " + runtimePath);
        }
        String relative = dot < 0 ? runtimePath : runtimePath.substring(dot + 1);
        setInFile(moduleFile, relative, value);
    }

    private YamlConfiguration buildRuntimeSnapshot() throws IOException, InvalidConfigurationException {
        YamlConfiguration merged = new YamlConfiguration();
        YamlConfiguration root = loadStrict(new File(dataFolder, "config.yml"));
        requireCurrentSchema(root, "config.yml");
        copyAll(root, merged, "", false);

        YamlConfiguration messages = loadStrict(new File(dataFolder, "messages.yml"));
        requireCurrentSchema(messages, "messages.yml");
        copyAll(messages, merged, "messages", true);

        for (Map.Entry<String, File> entry : moduleFiles.entrySet()) {
            String id = entry.getKey();
            YamlConfiguration module = loadStrict(entry.getValue());
            requireCurrentSchema(module, "modules/" + id + ".yml");
            Object enabledRaw = module.get("enabled");
            if (!(enabledRaw instanceof Boolean)) {
                throw new InvalidConfigurationException(entry.getValue().getPath()
                        + ": enabled must be true or false");
            }
            merged.set("modules." + id, enabledRaw);
            for (String key : module.getKeys(true)) {
                if (FILE_METADATA.contains(key) || key.equals("enabled") || module.isConfigurationSection(key)) {
                    continue;
                }
                merged.set(id + "." + key, module.get(key));
            }
        }

        YamlConfiguration reportGui = loadStrict(new File(dataFolder, "guis/report-gui.yml"));
        requireCurrentSchema(reportGui, "guis/report-gui.yml");
        copyAll(reportGui, merged, "reports.gui", true);

        YamlConfiguration bot = loadStrict(new File(dataFolder, "bot/config.yml"));
        requireCurrentSchema(bot, "bot/config.yml");
        Object botEnabled = bot.get("enabled");
        if (!(botEnabled instanceof Boolean)) {
            throw new InvalidConfigurationException("bot/config.yml: enabled must be true or false");
        }
        merged.set("modules.python-bot", botEnabled);
        copyAll(bot, merged, "bot", true);
        return merged;
    }


    private List<ConfigIssue> inspectConfigurationKeys()
            throws IOException, InvalidConfigurationException {
        List<String> managedYaml = new ArrayList<>();
        managedYaml.add("config.yml");
        managedYaml.add("messages.yml");
        managedYaml.add("telemetry.yml");
        managedYaml.add("secrets.yml");
        managedYaml.add("guis/report-gui.yml");
        managedYaml.add("bot/config.yml");
        for (String id : MODULE_IDS) {
            managedYaml.add("modules/" + id + ".yml");
        }

        List<ConfigIssue> issues = new ArrayList<>();
        for (String resourcePath : managedYaml) {
            File currentFile = new File(dataFolder, resourcePath);
            if (!currentFile.isFile()) {
                continue;
            }
            YamlConfiguration current = loadStrict(currentFile);
            YamlConfiguration defaults = loadResourceYaml(resourcePath);
            Set<String> allowed = defaults.getKeys(true);
            for (ConfigKeyInspector.UnknownKey unknown : ConfigKeyInspector.findUnknownKeys(
                    current.getKeys(true), allowed, FILE_METADATA)) {
                String suggestion = unknown.suggestion();
                String message = "Unknown key '" + unknown.path() + "'"
                        + (suggestion.isBlank() ? "." : ". Did you mean '" + suggestion + "'?");
                issues.add(new ConfigIssue(ConfigIssueKind.UNKNOWN_KEY, resourcePath,
                        unknown.path(), suggestion, message));
            }
        }

        File obsoleteLicense = new File(dataFolder, "license.yml");
        if (obsoleteLicense.isFile()) {
            YamlConfiguration legacy = loadStrict(obsoleteLicense);
            if (legacy.contains("metrics.enabled")) {
                issues.add(new ConfigIssue(ConfigIssueKind.DEPRECATED_SETTING,
                        "license.yml", "metrics.enabled", "telemetry.yml -> bstats.enabled",
                        "Deprecated setting 'metrics.enabled'; use telemetry.yml -> bstats.enabled."));
            } else {
                issues.add(new ConfigIssue(ConfigIssueKind.OBSOLETE_FILE,
                        "license.yml", "", "", "license.yml is obsolete and is no longer read."));
            }
        }
        return issues;
    }

    private void logConfigurationIssues(List<ConfigIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        plugin.getLogger().warning("Detected " + issues.size()
                + " configuration warning(s); unknown keys are preserved but ignored:");
        int shown = 0;
        for (ConfigIssue issue : issues) {
            if (shown++ >= 12) {
                plugin.getLogger().warning("- " + (issues.size() - 12)
                        + " additional warning(s); run /coredsc doctor for details.");
                break;
            }
            plugin.getLogger().warning("- " + issue.file() + ": " + issue.message());
        }
    }

    private void setInFile(File file, String path, Object value)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = loadStrict(file);
        yaml.set(path, value);
        stamp(yaml);
        saveAtomic(yaml, file);
    }

    private void migrateLegacy(YamlConfiguration legacy, File root)
            throws IOException, InvalidConfigurationException {
        backupBeforeWrite(root);
        plugin.getLogger().warning("Legacy single-file configuration detected. A complete backup will be kept in "
                + sessionBackupRoot.getFileName() + ".");

        for (String id : MODULE_IDS) {
            File target = moduleFiles.get(id);
            if (target.isFile()) {
                continue;
            }
            YamlConfiguration module = loadResourceYaml("modules/" + id + ".yml");
            module.set("enabled", legacy.getBoolean("modules." + id, module.getBoolean("enabled", false)));
            ConfigurationSection section = legacy.getConfigurationSection(id);
            if (section != null) {
                copySection(section, module, "", false);
                if (id.equals("reports")) {
                    module.set("gui", null);
                }
            }
            stamp(module);
            markCreated(target);
            saveAtomic(module, target);
        }

        File botFile = new File(dataFolder, "bot/config.yml");
        if (!botFile.isFile()) {
            YamlConfiguration bot = loadResourceYaml("bot/config.yml");
            bot.set("enabled", legacy.getBoolean(
                    "modules.python-bot", bot.getBoolean("enabled", false)));
            ConfigurationSection botSection = legacy.getConfigurationSection("bot");
            if (botSection == null) {
                botSection = legacy.getConfigurationSection("python-bot");
            }
            if (botSection != null) {
                copySection(botSection, bot, "", false);
            }
            stamp(bot);
            markCreated(botFile);
            saveAtomic(bot, botFile);
        }

        File guiFile = new File(dataFolder, "guis/report-gui.yml");
        if (!guiFile.isFile()) {
            ConfigurationSection gui = legacy.getConfigurationSection("reports.gui");
            YamlConfiguration guiYaml = gui == null
                    ? loadResourceYaml("guis/report-gui.yml") : new YamlConfiguration();
            if (gui != null) {
                copySection(gui, guiYaml, "", false);
            }
            stamp(guiYaml);
            markCreated(guiFile);
            saveAtomic(guiYaml, guiFile);
        }

        YamlConfiguration clean = loadResourceYaml("config.yml");
        for (String key : legacy.getKeys(false)) {
            if (key.equals("modules") || key.equals("bot") || key.equals("python-bot")
                    || MODULE_IDS.contains(key) || FILE_METADATA.contains(key)) {
                continue;
            }
            if (GLOBAL_ROOTS.contains(key) || !clean.contains(key)) {
                copyPath(legacy, clean, key, key);
            }
        }
        stamp(clean);
        saveAtomic(clean, root);
        recordChanged(root);
        plugin.getLogger().info("Migrated CoreDSC configuration into modules/, guis/ and bot/.");
    }

    private boolean isLegacy(YamlConfiguration root) {
        if (root.contains("modules")) {
            return true;
        }
        for (String id : MODULE_IDS) {
            if (root.contains(id)) {
                return true;
            }
        }
        return root.contains("bot") || root.contains("python-bot");
    }

    private void validate(YamlConfiguration merged) throws InvalidConfigurationException {
        int version = readVersion(merged, "config.yml");
        if (version != CURRENT_CONFIG_VERSION) {
            throw new InvalidConfigurationException("config.yml schema version " + version
                    + " is not supported by this build (expected " + CURRENT_CONFIG_VERSION + ")");
        }
        String registration = merged.getString("discord.command-registration", "guild");
        if (registration == null || (!registration.equalsIgnoreCase("guild")
                && !registration.equalsIgnoreCase("global"))) {
            throw new InvalidConfigurationException(
                    "discord.command-registration must be 'guild' or 'global'");
        }
        int reportListSize = merged.getInt("reports.gui.list.size", 54);
        int reportDetailSize = merged.getInt("reports.gui.detail.size", 45);
        if (!validInventorySize(reportListSize) || !validInventorySize(reportDetailSize)) {
            throw new InvalidConfigurationException(
                    "Report GUI inventory sizes must be a multiple of 9 between 9 and 54");
        }
        String engine = merged.getString("bot.engine", "EXTERNAL_CPYTHON");
        if (engine == null || !engine.equalsIgnoreCase("EXTERNAL_CPYTHON")) {
            throw new InvalidConfigurationException("bot.engine currently supports only EXTERNAL_CPYTHON");
        }
        if (merged.getBoolean("modules.voicechat-sync", false)) {
            long categoryId = parsePositiveLong(merged.get("voicechat-sync.discord.category-id"));
            long lobbyId = parsePositiveLong(merged.get("voicechat-sync.discord.lobby-channel-id"));
            if (categoryId <= 0L || lobbyId <= 0L) {
                throw new InvalidConfigurationException(
                        "voicechat-sync requires positive category-id and lobby-channel-id values");
            }
            validateGain(merged, "voicechat-sync.audio.minecraft-to-discord.gain");
            validateGain(merged, "voicechat-sync.audio.discord-to-minecraft.gain");
            validatePositive(merged, "voicechat-sync.proximity.horizontal-distance");
            validatePositive(merged, "voicechat-sync.proximity.vertical-distance");
            if (merged.getInt("voicechat-sync.proximity.minimum-players", 2) < 2) {
                throw new InvalidConfigurationException(
                        "voicechat-sync.proximity.minimum-players must be at least 2");
            }
            if (merged.getLong("voicechat-sync.proximity.update-ticks", 10L) < 5L) {
                throw new InvalidConfigurationException(
                        "voicechat-sync.proximity.update-ticks must be at least 5");
            }
            int maximumRooms = merged.getInt(
                    "voicechat-sync.rooms.maximum-active-rooms", 12);
            if (maximumRooms < 1 || maximumRooms > 100) {
                throw new InvalidConfigurationException(
                        "voicechat-sync.rooms.maximum-active-rooms must be between 1 and 100");
            }
        }
    }

     
                                                                                 
                                                                              
                                                                             
       
    private void migrateObsoleteLicenseMetrics()
            throws IOException, InvalidConfigurationException {
        File obsoleteLicense = new File(dataFolder, "license.yml");
        if (!obsoleteLicense.isFile()) {
            return;
        }

        YamlConfiguration legacy = loadStrict(obsoleteLicense);
        Object rawEnabled = legacy.get("metrics.enabled");
        if (rawEnabled == null) {
            return;
        }
        if (!(rawEnabled instanceof Boolean legacyMetricsEnabled)) {
            plugin.getLogger().warning("license.yml metrics.enabled is not true/false and was not migrated; "
                    + "review telemetry.yml manually.");
            return;
        }

        File telemetryFile = new File(dataFolder, "telemetry.yml");
        if (!telemetryFile.isFile()) {
            YamlConfiguration telemetry = loadResourceYaml("telemetry.yml");
            telemetry.set("bstats.enabled", legacyMetricsEnabled);
            stamp(telemetry);
            markCreated(telemetryFile);
            saveAtomic(telemetry, telemetryFile);
            plugin.getLogger().info("Migrated the former license.yml metrics preference to telemetry.yml.");
            return;
        }

        YamlConfiguration telemetry = loadStrict(telemetryFile);
        boolean telemetryEnabled = telemetry.getBoolean("bstats.enabled", true);
        if (!legacyMetricsEnabled && telemetryEnabled) {
            backupBeforeWrite(telemetryFile);
            telemetry.set("bstats.enabled", false);
            stamp(telemetry);
            saveAtomic(telemetry, telemetryFile);
            recordChanged(telemetryFile);
            plugin.getLogger().info("Preserved the former license.yml bStats opt-out in telemetry.yml.");
        }
    }

    private void prepareManagedYaml(String resourcePath)
            throws IOException, InvalidConfigurationException {
        File destination = new File(dataFolder, resourcePath);
        if (!destination.isFile()) {
            ensureResourceCreated(resourcePath, destination);
            return;
        }

        YamlConfiguration current = loadStrict(destination);
        YamlConfiguration defaults = loadResourceYaml(resourcePath);
        int sourceVersion = readVersion(current, resourcePath);
        int targetVersion = readVersion(defaults, "bundled " + resourcePath);
        if (targetVersion != CURRENT_CONFIG_VERSION) {
            throw new InvalidConfigurationException("Bundled " + resourcePath + " uses schema "
                    + targetVersion + " but the code expects " + CURRENT_CONFIG_VERSION);
        }
        if (sourceVersion > CURRENT_CONFIG_VERSION) {
            throw new InvalidConfigurationException(resourcePath + " was created by a newer CoreDSC schema ("
                    + sourceVersion + "). Downgrade is blocked to prevent configuration loss.");
        }

        boolean changed = sourceVersion < CURRENT_CONFIG_VERSION;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || FILE_METADATA.contains(key)) {
                continue;
            }
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                changed = true;
            }
        }
                                                                                
                                                                            
                                                                               
        if (current.getString("generated-by-version", "").isBlank()) {
            changed = true;
        }

        if (changed) {
            stamp(current);
            backupBeforeWrite(destination);
            saveAtomic(current, destination);
            recordChanged(destination);
        }
    }

    private void prepareManagedText(String resourcePath) throws IOException {
        ensureResourceCreated(resourcePath, new File(dataFolder, resourcePath));
    }

                                                                                
    private void prepareManagedResource(String resourcePath, File destination) throws IOException {
        byte[] bundled;
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            bundled = input.readAllBytes();
        }

        Path path = destination.toPath().toAbsolutePath().normalize();
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new IOException("Managed resource path is not a regular file: " + destination);
        }
        if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), bundled)) {
            return;
        }

        if (Files.isRegularFile(path)) {
            backupBeforeWrite(destination);
        } else {
            markCreated(destination);
        }
        writeAtomic(bundled, destination);
        if (Files.isRegularFile(path) && !sessionCreatedFiles.contains(path)) {
            recordChanged(destination);
        }
    }

    private void stamp(YamlConfiguration yaml) {
        yaml.set("config-version", CURRENT_CONFIG_VERSION);
        yaml.set("generated-by-version", plugin.getDescription().getVersion());
    }

    private void requireCurrentSchema(YamlConfiguration yaml, String source)
            throws InvalidConfigurationException {
        int version = readVersion(yaml, source);
        if (version != CURRENT_CONFIG_VERSION) {
            throw new InvalidConfigurationException(source + " uses config-version " + version
                    + " but this runtime requires " + CURRENT_CONFIG_VERSION
                    + "; restart CoreDSC to run safe migrations before reloading");
        }
    }

    private int readVersion(YamlConfiguration yaml, String source)
            throws InvalidConfigurationException {
        Object raw = yaml.get("config-version");
        if (raw == null) {
            return 0;
        }
        try {
            int version = Integer.parseInt(String.valueOf(raw).trim());
            if (version < 0) {
                throw new NumberFormatException("negative");
            }
            return version;
        } catch (NumberFormatException error) {
            throw new InvalidConfigurationException(source + ": config-version must be a non-negative integer");
        }
    }

    private void beginMigrationSession() {
        sessionBackups.clear();
        sessionCreatedFiles.clear();
        sessionChangedFiles.clear();
        sessionBackupRoot = null;
    }

    private void finishMigrationSession() {
        if (sessionChangedFiles.isEmpty()) {
            migrationSummary = MigrationSummary.none(CURRENT_CONFIG_VERSION);
            return;
        }
        migrationSummary = new MigrationSummary(true, CURRENT_CONFIG_VERSION,
                List.copyOf(sessionChangedFiles), sessionBackupRoot == null ? "" : sessionBackupRoot.toString(),
                "updated " + sessionChangedFiles.size() + " managed file(s)");
        plugin.getLogger().info("Configuration schema upgrade completed: "
                + String.join(", ", sessionChangedFiles));
    }

    private void rollbackMigrationSession(Throwable cause) {
        List<Throwable> restoreFailures = new ArrayList<>();
        for (Path created : sessionCreatedFiles) {
            try {
                Files.deleteIfExists(created);
            } catch (IOException error) {
                restoreFailures.add(error);
            }
        }
        for (Map.Entry<Path, Path> entry : sessionBackups.entrySet()) {
            try {
                Files.createDirectories(entry.getKey().getParent());
                writeAtomic(Files.readAllBytes(entry.getValue()), entry.getKey().toFile());
            } catch (IOException error) {
                restoreFailures.add(error);
            }
        }
        for (Throwable restoreFailure : restoreFailures) {
            cause.addSuppressed(restoreFailure);
        }
        migrationSummary = new MigrationSummary(false, CURRENT_CONFIG_VERSION,
                List.copyOf(sessionChangedFiles), sessionBackupRoot == null ? "" : sessionBackupRoot.toString(),
                "migration failed and rollback was attempted: " + rootMessage(cause));
    }

    private void clearMigrationSession() {
        sessionBackups.clear();
        sessionCreatedFiles.clear();
        sessionChangedFiles.clear();
        sessionBackupRoot = null;
    }

    private void backupBeforeWrite(File file) throws IOException {
        Path original = file.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(original) || sessionBackups.containsKey(original)) {
            return;
        }
        if (sessionBackupRoot == null) {
            sessionBackupRoot = new File(dataFolder,
                    "backups/config-migration-" + Instant.now().toEpochMilli()).toPath();
            Files.createDirectories(sessionBackupRoot);
        }
        Path relative = dataFolder.toPath().toAbsolutePath().normalize().relativize(original);
        Path backup = sessionBackupRoot.resolve(relative);
        Files.createDirectories(backup.getParent());
        Files.copy(original, backup, StandardCopyOption.COPY_ATTRIBUTES);
        sessionBackups.put(original, backup);
    }

    private void markCreated(File file) {
        sessionCreatedFiles.add(file.toPath().toAbsolutePath().normalize());
        recordChanged(file);
    }

    private void recordChanged(File file) {
        Path relative = dataFolder.toPath().toAbsolutePath().normalize()
                .relativize(file.toPath().toAbsolutePath().normalize());
        String normalized = relative.toString().replace(File.separatorChar, '/');
        if (!sessionChangedFiles.contains(normalized)) {
            sessionChangedFiles.add(normalized);
        }
    }

    private void ensureResourceCreated(String resourcePath, File destination) throws IOException {
        if (destination.isFile()) {
            return;
        }
        markCreated(destination);
        copyResource(resourcePath, destination, false);
    }

    private void copyResource(String resourcePath, File destination, boolean replace) throws IOException {
        if (destination.exists() && !replace) {
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create directory " + parent);
        }
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            Path temporary = Files.createTempFile(parent == null ? dataFolder.toPath() : parent.toPath(),
                    ".coredsc-", ".tmp");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                moveReplace(temporary, destination.toPath());
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private YamlConfiguration loadResourceYaml(String path)
            throws IOException, InvalidConfigurationException {
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IOException("Missing bundled YAML resource: " + path);
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(content);
            return yaml;
        }
    }

    private static YamlConfiguration loadStrict(File file)
            throws IOException, InvalidConfigurationException {
        if (!file.isFile()) {
            throw new IOException("Configuration file does not exist: " + file.getPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    private static YamlConfiguration cloneYaml(YamlConfiguration source) {
        YamlConfiguration copy = new YamlConfiguration();
        try {
            copy.loadFromString(source.saveToString());
            return copy;
        } catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException("Could not clone an already-loaded YAML configuration", impossible);
        }
    }

    private static void saveAtomic(YamlConfiguration yaml, File file) throws IOException {
        writeAtomic(yaml.saveToString().getBytes(StandardCharsets.UTF_8), file);
    }

    private static void writeAtomic(byte[] content, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create directory " + parent);
        }
        Path directory = parent == null ? file.toPath().toAbsolutePath().getParent() : parent.toPath();
        if (directory == null) {
            throw new IOException("Could not resolve parent directory for " + file);
        }
        Path temporary = Files.createTempFile(directory, ".coredsc-", ".tmp");
        try {
            Files.write(temporary, content);
            moveReplace(temporary, file.toPath());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean validInventorySize(int size) {
        return size >= 9 && size <= 54 && size % 9 == 0;
    }

    private static void validateGain(YamlConfiguration config, String path)
            throws InvalidConfigurationException {
        double gain = config.getDouble(path, 1.0);
        if (!Double.isFinite(gain) || gain < 0.0 || gain > 4.0) {
            throw new InvalidConfigurationException(path + " must be between 0.0 and 4.0");
        }
    }

    private static void validatePositive(YamlConfiguration config, String path)
            throws InvalidConfigurationException {
        double value = config.getDouble(path, 0.0);
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new InvalidConfigurationException(path + " must be greater than 0");
        }
    }

    private static long parsePositiveLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw.toString().trim());
            return value > 0L ? value : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static void copyAll(
            ConfigurationSection source,
            ConfigurationSection target,
            String prefix,
            boolean skipMetadata
    ) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key) || (skipMetadata && FILE_METADATA.contains(key))) {
                continue;
            }
            String destination = prefix == null || prefix.isBlank() ? key : prefix + "." + key;
            target.set(destination, source.get(key));
        }
    }

    private static void copySection(
            ConfigurationSection source,
            ConfigurationSection target,
            String prefix,
            boolean skipMetadata
    ) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key) || (skipMetadata && FILE_METADATA.contains(key))) {
                continue;
            }
            String destination = prefix == null || prefix.isBlank() ? key : prefix + "." + key;
            target.set(destination, source.get(key));
        }
    }

    private static void copyPath(ConfigurationSection source, ConfigurationSection target,
                                 String sourcePath, String targetPath) {
        ConfigurationSection section = source.getConfigurationSection(sourcePath);
        if (section != null) {
            copySection(section, target, targetPath, false);
        } else if (source.contains(sourcePath)) {
            target.set(targetPath, source.get(sourcePath));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record MigrationSummary(
            boolean migrated,
            int targetVersion,
            List<String> changedFiles,
            String backupDirectory,
            String detail
    ) {
        private static MigrationSummary none(int targetVersion) {
            return new MigrationSummary(false, targetVersion, List.of(), "", "configuration is current");
        }
    }
}
