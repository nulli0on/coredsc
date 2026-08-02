package com.hubertstudios.coredsc.config;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;









public final class ConfigManager {
    public static final int CURRENT_CONFIG_VERSION = 5;

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

    
    public record EditorDocument(String path, String content, String revision, long sizeBytes) { }

    
    public record EditorValidation(List<ConfigIssue> warnings) { }

    
    public record EditorSaveResult(String revision, String backupPath, List<ConfigIssue> warnings) { }

    
    public record EditorPatch(
            String file,
            String path,
            Object value,
            String expectedRevision
    ) { }

    
    public record EditorBatchSaveResult(
            Map<String, String> revisions,
            String backupPath,
            List<ConfigIssue> warnings,
            List<String> changedFiles
    ) { }

    
    public static final class EditorConflictException extends IOException {
        private static final long serialVersionUID = 1L;

        public EditorConflictException(String message) {
            super(message);
        }
    }

    private static final List<String> MODULE_IDS = List.of(
            "placeholderapi", "delivery-queue", "network", "link", "link-rewards",
            "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync",
            "chat-sync", "console", "server-events", "custom-commands", "status-channels", "cases",
            "moderation-bridge", "tickets", "reports", "applications", "workflows",
            "authme", "voicechat-sync", "economy-market", "lore-sync", "competitive", "web-editor"
    );
    private static final List<String> EDITABLE_FILES = buildEditableFiles();
    private static final Set<String> GLOBAL_ROOTS = Set.of(
            "config-version", "generated-by-version", "language", "debug", "startup-banner", "discord", "storage", "performance"
    );
    private static final Set<String> FILE_METADATA = Set.of("config-version", "generated-by-version");
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern LORE_PROFILE_ID = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

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

    



    public List<String> editableFilePaths() {
        return EDITABLE_FILES;
    }

    
    public synchronized EditorDocument readEditorDocument(String relativePath, int maximumBytes)
            throws IOException {
        String path = requireEditablePath(relativePath);
        Path file = resolveEditableFile(path);
        long size = Files.size(file);
        if (size > maximumBytes) {
            throw new IOException(path + " exceeds the WebEditor size limit of " + maximumBytes + " bytes");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > maximumBytes) {
            throw new IOException(path + " changed while being read and now exceeds the WebEditor size limit");
        }
        return new EditorDocument(path, new String(bytes, StandardCharsets.UTF_8),
                revision(bytes), bytes.length);
    }

    
    public synchronized EditorValidation validateEditorDocument(
            String relativePath,
            String content,
            int maximumBytes
    ) throws IOException, InvalidConfigurationException {
        String path = requireEditablePath(relativePath);
        byte[] bytes = editorBytes(path, content, maximumBytes);
        YamlConfiguration candidate = parseEditorYaml(path, bytes);
        requireCurrentSchema(candidate, path);
        validateTelemetryEditorFile(path, candidate);

        YamlConfiguration merged = buildRuntimeSnapshot(Map.of(path, candidate));
        validate(merged);
        return new EditorValidation(inspectEditorKeys(path, candidate));
    }

    



    public synchronized EditorSaveResult saveEditorDocument(
            String relativePath,
            String content,
            String expectedRevision,
            int maximumBytes
    ) throws IOException, InvalidConfigurationException {
        String path = requireEditablePath(relativePath);
        if (expectedRevision == null || !expectedRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A valid WebEditor revision is required");
        }
        byte[] replacement = editorBytes(path, content, maximumBytes);
        EditorValidation validation = validateEditorDocument(path, content, maximumBytes);
        Path file = resolveEditableFile(path);
        byte[] current = Files.readAllBytes(file);
        if (!revision(current).equals(expectedRevision)) {
            throw new EditorConflictException(path
                    + " changed after it was opened. Reload it before saving your draft.");
        }
        if (Arrays.equals(current, replacement)) {
            return new EditorSaveResult(expectedRevision, "", validation.warnings());
        }

        Path backup = createEditorBackup(file, path);
        writeAtomic(replacement, file.toFile());
        Path root = dataFolder.toPath().toAbsolutePath().normalize();
        String backupPath = root.relativize(backup.toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        return new EditorSaveResult(revision(replacement), backupPath, validation.warnings());
    }

    





    public synchronized EditorBatchSaveResult saveEditorPatch(
            List<EditorPatch> requestedPatches,
            int maximumBytes
    ) throws IOException, InvalidConfigurationException {
        if (requestedPatches == null || requestedPatches.isEmpty()) {
            throw new IllegalArgumentException("At least one structured configuration change is required");
        }
        if (requestedPatches.size() > 100) {
            throw new IllegalArgumentException("A structured edit may contain at most 100 changes");
        }

        Map<String, List<EditorPatch>> patchesByFile = new LinkedHashMap<>();
        for (EditorPatch patch : requestedPatches) {
            if (patch == null) {
                throw new IllegalArgumentException("Structured edit contains an empty change");
            }
            String file = requireEditablePath(patch.file());
            String path = requireEditorPatchPath(patch.path());
            String expectedRevision = normalizeEditorRevision(patch.expectedRevision());
            Object value = requireJsonSafeEditorValue(patch.value(), path, 0);
            patchesByFile.computeIfAbsent(file, ignored -> new ArrayList<>())
                    .add(new EditorPatch(file, path, value, expectedRevision));
        }

        Map<String, byte[]> originals = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        Map<String, YamlConfiguration> candidates = new LinkedHashMap<>();
        List<ConfigIssue> warnings = new ArrayList<>();

        for (Map.Entry<String, List<EditorPatch>> entry : patchesByFile.entrySet()) {
            String file = entry.getKey();
            Path resolved = resolveEditableFile(file);
            byte[] original = Files.readAllBytes(resolved);
            if (original.length > maximumBytes) {
                throw new IOException(file + " exceeds the WebEditor size limit of " + maximumBytes + " bytes");
            }
            String currentRevision = revision(original);
            Set<String> expected = new LinkedHashSet<>();
            for (EditorPatch patch : entry.getValue()) {
                expected.add(patch.expectedRevision());
            }
            if (expected.size() != 1 || !expected.contains(currentRevision)) {
                throw new EditorConflictException(file
                        + " changed after the dashboard was loaded. Refresh before saving.");
            }

            YamlConfiguration candidate = parseEditorYaml(file, original);
            requireCurrentSchema(candidate, file);
            YamlConfiguration defaults = loadResourceYaml(file);
            Set<String> touchedPaths = new LinkedHashSet<>();
            for (EditorPatch patch : entry.getValue()) {
                if (!touchedPaths.add(patch.path())) {
                    throw new IllegalArgumentException("Structured edit changes '" + patch.path()
                            + "' more than once in " + file);
                }
                if (!defaults.contains(patch.path()) && !candidate.contains(patch.path())) {
                    throw new IllegalArgumentException("The structured editor cannot create unknown key '"
                            + patch.path() + "' in " + file);
                }
                candidate.set(patch.path(), patch.value());
            }
            validateTelemetryEditorFile(file, candidate);
            candidates.put(file, candidate);
            originals.put(file, original);
            files.put(file, resolved);
            warnings.addAll(inspectEditorKeys(file, candidate));
        }

        
        
        YamlConfiguration merged = buildRuntimeSnapshot(candidates);
        validate(merged);

        Map<String, byte[]> replacements = new LinkedHashMap<>();
        List<String> changedFiles = new ArrayList<>();
        for (Map.Entry<String, YamlConfiguration> entry : candidates.entrySet()) {
            byte[] replacement = editorBytes(
                    entry.getKey(), entry.getValue().saveToString(), maximumBytes);
            replacements.put(entry.getKey(), replacement);
            if (!Arrays.equals(originals.get(entry.getKey()), replacement)) {
                changedFiles.add(entry.getKey());
            }
        }

        if (changedFiles.isEmpty()) {
            Map<String, String> revisions = new LinkedHashMap<>();
            originals.forEach((file, bytes) -> revisions.put(file, revision(bytes)));
            return new EditorBatchSaveResult(
                    Map.copyOf(revisions), "", List.copyOf(warnings), List.of());
        }

        Path backupRoot = createEditorBatchBackup(files, changedFiles);
        List<String> written = new ArrayList<>();
        try {
            for (String file : changedFiles) {
                writeAtomic(replacements.get(file), files.get(file).toFile());
                written.add(file);
            }
        } catch (Throwable writeFailure) {
            for (int index = written.size() - 1; index >= 0; index--) {
                String file = written.get(index);
                try {
                    writeAtomic(originals.get(file), files.get(file).toFile());
                } catch (Throwable rollbackFailure) {
                    writeFailure.addSuppressed(rollbackFailure);
                }
            }
            if (writeFailure instanceof IOException io) {
                throw io;
            }
            if (writeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Structured configuration write failed", writeFailure);
        }

        Map<String, String> revisions = new LinkedHashMap<>();
        replacements.forEach((file, bytes) -> revisions.put(file, revision(bytes)));
        Path root = dataFolder.toPath().toAbsolutePath().normalize();
        String backupPath = root.relativize(backupRoot.toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        return new EditorBatchSaveResult(
                Map.copyOf(revisions),
                backupPath,
                List.copyOf(warnings),
                List.copyOf(changedFiles));
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
        return buildRuntimeSnapshot(Map.of());
    }

    private YamlConfiguration buildRuntimeSnapshot(Map<String, YamlConfiguration> overrides)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration merged = new YamlConfiguration();
        YamlConfiguration root = loadEditorOverride(
                "config.yml", new File(dataFolder, "config.yml"), overrides);
        requireCurrentSchema(root, "config.yml");
        copyAll(root, merged, "", false);

        YamlConfiguration messages = loadEditorOverride(
                "messages.yml", new File(dataFolder, "messages.yml"), overrides);
        requireCurrentSchema(messages, "messages.yml");
        copyAll(messages, merged, "messages", true);

        for (Map.Entry<String, File> entry : moduleFiles.entrySet()) {
            String id = entry.getKey();
            String relativePath = "modules/" + id + ".yml";
            YamlConfiguration module = loadEditorOverride(relativePath, entry.getValue(), overrides);
            requireCurrentSchema(module, relativePath);
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

        YamlConfiguration reportGui = loadEditorOverride(
                "guis/report-gui.yml", new File(dataFolder, "guis/report-gui.yml"), overrides);
        requireCurrentSchema(reportGui, "guis/report-gui.yml");
        copyAll(reportGui, merged, "reports.gui", true);

        YamlConfiguration bot = loadEditorOverride(
                "bot/config.yml", new File(dataFolder, "bot/config.yml"), overrides);
        requireCurrentSchema(bot, "bot/config.yml");
        Object botEnabled = bot.get("enabled");
        if (!(botEnabled instanceof Boolean)) {
            throw new InvalidConfigurationException("bot/config.yml: enabled must be true or false");
        }
        merged.set("modules.python-bot", botEnabled);
        copyAll(bot, merged, "bot", true);
        return merged;
    }

    private YamlConfiguration loadEditorOverride(
            String relativePath,
            File file,
            Map<String, YamlConfiguration> overrides
    ) throws IOException, InvalidConfigurationException {
        YamlConfiguration override = overrides.get(relativePath);
        return override == null ? loadStrict(file) : override;
    }

    private List<ConfigIssue> inspectEditorKeys(String path, YamlConfiguration candidate)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = loadResourceYaml(path);
        List<ConfigIssue> warnings = new ArrayList<>();
        for (ConfigKeyInspector.UnknownKey unknown : ConfigKeyInspector.findUnknownKeys(
                candidate.getKeys(true), defaults.getKeys(true), FILE_METADATA)) {
            String suggestion = unknown.suggestion();
            String message = "Unknown key '" + unknown.path() + "'"
                    + (suggestion.isBlank() ? "." : ". Did you mean '" + suggestion + "'?");
            warnings.add(new ConfigIssue(ConfigIssueKind.UNKNOWN_KEY, path,
                    unknown.path(), suggestion, message));
        }
        return List.copyOf(warnings);
    }

    private void validateTelemetryEditorFile(String path, YamlConfiguration candidate)
            throws InvalidConfigurationException {
        if (!path.equals("telemetry.yml")) {
            return;
        }
        for (String key : List.of("bstats.enabled", "bstats.feature-activity")) {
            Object value = candidate.get(key);
            if (!(value instanceof Boolean)) {
                throw new InvalidConfigurationException(path + ": " + key + " must be true or false");
            }
        }
    }

    private Path resolveEditableFile(String relativePath) throws IOException {
        Path configuredRoot = dataFolder.toPath().toAbsolutePath().normalize();
        Path candidate = configuredRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(configuredRoot)) {
            throw new IOException("WebEditor path escaped the CoreDSC data directory");
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IOException(relativePath + " is a symbolic link and cannot be edited");
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(relativePath + " is not a regular configuration file");
        }

        Path realRoot = configuredRoot.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new IOException(relativePath + " resolves outside the CoreDSC data directory");
        }
        return candidate;
    }

    private Path createEditorBackup(Path source, String relativePath) throws IOException {
        Path backupRoot = dataFolder.toPath().toAbsolutePath().normalize()
                .resolve("backups/web-editor");
        Files.createDirectories(backupRoot);
        Path realDataRoot = dataFolder.toPath().toAbsolutePath().normalize().toRealPath();
        if (!backupRoot.toRealPath().startsWith(realDataRoot)) {
            throw new IOException("WebEditor backup directory resolves outside the CoreDSC data directory");
        }
        long timestamp = System.currentTimeMillis();
        Path session = backupRoot.resolve("edit-" + timestamp);
        int suffix = 0;
        while (Files.exists(session)) {
            session = backupRoot.resolve("edit-" + timestamp + "-" + (++suffix));
        }
        Path backup = session.resolve(relativePath).normalize();
        if (!backup.startsWith(session)) {
            throw new IOException("Could not create a safe WebEditor backup path");
        }
        Files.createDirectories(backup.getParent());
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    private Path createEditorBatchBackup(
            Map<String, Path> files,
            List<String> changedFiles
    ) throws IOException {
        Path configuredRoot = dataFolder.toPath().toAbsolutePath().normalize();
        Path backupRoot = configuredRoot.resolve("backups/web-editor");
        Files.createDirectories(backupRoot);
        Path realDataRoot = configuredRoot.toRealPath();
        if (!backupRoot.toRealPath().startsWith(realDataRoot)) {
            throw new IOException("WebEditor backup directory resolves outside the CoreDSC data directory");
        }

        long timestamp = System.currentTimeMillis();
        Path session = backupRoot.resolve("structured-edit-" + timestamp);
        int suffix = 0;
        while (Files.exists(session)) {
            session = backupRoot.resolve("structured-edit-" + timestamp + "-" + (++suffix));
        }
        Files.createDirectories(session);

        for (String relativePath : changedFiles) {
            Path source = files.get(relativePath);
            if (source == null || !source.startsWith(configuredRoot)) {
                throw new IOException("Could not resolve a safe backup source for " + relativePath);
            }
            Path backup = session.resolve(relativePath).normalize();
            if (!backup.startsWith(session)) {
                throw new IOException("Could not create a safe WebEditor backup path for " + relativePath);
            }
            Files.createDirectories(backup.getParent());
            Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return session;
    }

    private static byte[] editorBytes(String path, String content, int maximumBytes)
            throws IOException {
        if (maximumBytes < 1024) {
            throw new IllegalArgumentException("WebEditor maximum size is too small");
        }
        if (content == null) {
            throw new IllegalArgumentException("WebEditor content is missing");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw new IOException(path + " exceeds the WebEditor size limit of " + maximumBytes + " bytes");
        }
        return bytes;
    }

    private static YamlConfiguration parseEditorYaml(String path, byte[] bytes)
            throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            return yaml;
        } catch (InvalidConfigurationException error) {
            InvalidConfigurationException wrapped = new InvalidConfigurationException(
                    path + ": " + rootMessage(error));
            wrapped.initCause(error);
            throw wrapped;
        }
    }

    private static String requireEditablePath(String relativePath) {
        String value = relativePath == null ? "" : relativePath.trim();
        if (!EDITABLE_FILES.contains(value)) {
            throw new IllegalArgumentException("File is not available in WebEditor");
        }
        return value;
    }

    private static String requireEditorPatchPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}")) {
            throw new IllegalArgumentException("Structured configuration path is invalid");
        }
        if (FILE_METADATA.contains(path) || path.startsWith("config-version.")
                || path.startsWith("generated-by-version.")) {
            throw new IllegalArgumentException("Structured edits cannot change configuration metadata");
        }
        return path;
    }

    private static String normalizeEditorRevision(String rawRevision) {
        String value = rawRevision == null ? "" : rawRevision.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Every changed file requires a valid dashboard revision");
        }
        return value;
    }

    private static Object requireJsonSafeEditorValue(Object value, String path, int depth) {
        if (depth > 4) {
            throw new IllegalArgumentException("Structured value for '" + path + "' is nested too deeply");
        }
        if (value == null) {
            throw new IllegalArgumentException("Structured value for '" + path + "' cannot be null");
        }
        if (value instanceof String text) {
            if (text.length() > 16_384) {
                throw new IllegalArgumentException("Structured value for '" + path + "' is too long");
            }
            return text;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("Structured value for '" + path + "' is not finite");
            }
            return number;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Structured value for '" + path + "' is not finite");
            }
            return number;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 250) {
                throw new IllegalArgumentException("Structured list for '" + path + "' is too large");
            }
            List<Object> safe = new ArrayList<>(list.size());
            for (Object item : list) {
                safe.add(requireJsonSafeEditorValue(item, path, depth + 1));
            }
            return List.copyOf(safe);
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 100) {
                throw new IllegalArgumentException("Structured object for '" + path + "' is too large");
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).trim();
                if (!key.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) {
                    throw new IllegalArgumentException("Structured object for '" + path
                            + "' contains an invalid key");
                }
                safe.put(key, requireJsonSafeEditorValue(entry.getValue(), path, depth + 1));
            }
            return Map.copyOf(safe);
        }
        throw new IllegalArgumentException("Structured value for '" + path
                + "' has unsupported type " + value.getClass().getSimpleName());
    }

    private static String revision(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<String> buildEditableFiles() {
        List<String> paths = new ArrayList<>();
        paths.add("config.yml");
        paths.add("messages.yml");
        paths.add("telemetry.yml");
        paths.add("guis/report-gui.yml");
        paths.add("bot/config.yml");
        for (String moduleId : MODULE_IDS) {
            paths.add("modules/" + moduleId + ".yml");
        }
        return List.copyOf(paths);
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
        requireIntRange(merged, "storage.sqlite.queue-capacity", 256, 65_536);
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
        if (merged.getBoolean("modules.web-editor", false)) {
            String bindAddress = merged.getString("web-editor.bind-address", "127.0.0.1");
            bindAddress = bindAddress == null ? "" : bindAddress.trim();
            if (!Set.of("127.0.0.1", "::1", "localhost").contains(bindAddress)) {
                throw new InvalidConfigurationException(
                        "web-editor.bind-address must be 127.0.0.1, ::1 or localhost; public binding is blocked");
            }
            int port = merged.getInt("web-editor.port", 8765);
            if (port < 1024 || port > 65_535) {
                throw new InvalidConfigurationException("web-editor.port must be between 1024 and 65535");
            }
            int maximumMinutes = merged.getInt("web-editor.session.maximum-minutes", 30);
            int defaultMinutes = merged.getInt("web-editor.session.default-minutes", 15);
            if (maximumMinutes < 1 || maximumMinutes > 60
                    || defaultMinutes < 1 || defaultMinutes > maximumMinutes) {
                throw new InvalidConfigurationException(
                        "web-editor session minutes must be positive, default-minutes must not exceed maximum-minutes, "
                                + "and maximum-minutes must not exceed 60");
            }
            int failedAuthLimit = merged.getInt(
                    "web-editor.session.maximum-failed-auth-attempts-per-minute", 30);
            if (failedAuthLimit < 5 || failedAuthLimit > 120) {
                throw new InvalidConfigurationException(
                        "web-editor.session.maximum-failed-auth-attempts-per-minute must be between 5 and 120");
            }
            int maximumFileBytes = merged.getInt("web-editor.editor.maximum-file-bytes", 1_048_576);
            if (maximumFileBytes < 65_536 || maximumFileBytes > 2_097_152) {
                throw new InvalidConfigurationException(
                        "web-editor.editor.maximum-file-bytes must be between 65536 and 2097152");
            }
        }
        if (merged.getBoolean("modules.economy-market", false)) {
            validateEconomyMarket(merged);
        }
        if (merged.getBoolean("modules.lore-sync", false)) {
            validateLoreSync(merged);
        }
        if (merged.getBoolean("modules.competitive", false)) {
            validateCompetitive(merged);
        }
        if (merged.getBoolean("modules.console", false)) {
            validateSmartConsole(merged);
        }
        if (merged.getBoolean("modules.server-events", false)) {
            validateServerEventEmbeds(merged);
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

    private static void validateEconomyMarket(YamlConfiguration config)
            throws InvalidConfigurationException {
        requireIntRange(config, "economy-market.privacy.maximum-inventory-lines", 9, 54);
        requireLongRange(config, "economy-market.commands.cooldown-seconds", 0L, 300L);
        requireIntRange(config, "economy-market.market.page-size", 1, 10);
        Set<String> commands = new LinkedHashSet<>();
        for (String path : List.of("economy-market.commands.balance",
                "economy-market.commands.inventory", "economy-market.commands.market")) {
            String command = requireCommand(config, path);
            if (!commands.add(command)) {
                throw new InvalidConfigurationException(
                        "economy-market Discord command names must be unique; duplicate: " + command);
            }
        }
        String source = stringValue(config.get("economy-market.market.source")).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CONFIG", "SERVICE").contains(source)) {
            throw new InvalidConfigurationException("economy-market.market.source must be CONFIG or SERVICE");
        }
        if (source.equals("CONFIG")) {
            Set<String> ids = new LinkedHashSet<>();
            int index = 0;
            for (Map<?, ?> listing : config.getMapList("economy-market.market.listings")) {
                String prefix = "economy-market.market.listings[" + index++ + "]";
                String id = stringValue(listing.get("id"));
                String name = stringValue(listing.get("name"));
                if (!COMMAND_NAME.matcher(id).matches()) {
                    throw new InvalidConfigurationException(prefix
                            + ".id must match " + COMMAND_NAME.pattern());
                }
                if (!ids.add(id)) {
                    throw new InvalidConfigurationException("Duplicate economy market listing id: " + id);
                }
                if (name.isBlank() || name.length() > 100) {
                    throw new InvalidConfigurationException(prefix + ".name must contain 1-100 characters");
                }
                double price = finiteDouble(listing.get("price"), prefix + ".price");
                if (price < 0.0D) {
                    throw new InvalidConfigurationException(prefix + ".price cannot be negative");
                }
                validateOptionalHttps(stringValue(listing.get("icon-url")), prefix + ".icon-url");
            }
        }
    }

    private static void validateLoreSync(YamlConfiguration config)
            throws InvalidConfigurationException {
        requireIntRange(config, "lore-sync.security.maximum-message-length", 1, 3_500);
        requireLongRange(config, "lore-sync.security.cooldown-seconds", 0L, 300L);
        String permission = stringValue(config.get("lore-sync.security.permission"));
        if (permission.isBlank()) {
            throw new InvalidConfigurationException("lore-sync.security.permission cannot be blank");
        }
        String prefix = stringValue(config.get("lore-sync.webhooks.name-prefix"));
        if (prefix.isBlank() || prefix.length() > 60) {
            throw new InvalidConfigurationException("lore-sync.webhooks.name-prefix must contain 1-60 characters");
        }
        String defaultChannel = stringValue(config.get("lore-sync.default-channel-id"));
        if (!defaultChannel.isBlank() && parsePositiveLong(defaultChannel) <= 0L) {
            throw new InvalidConfigurationException("lore-sync.default-channel-id must be blank or a Discord ID");
        }
        Set<String> ids = new LinkedHashSet<>();
        int enabledProfiles = 0;
        int index = 0;
        for (Map<?, ?> profile : config.getMapList("lore-sync.profiles")) {
            String path = "lore-sync.profiles[" + index++ + "]";
            if (!booleanValue(profile.get("enabled"), true)) continue;
            enabledProfiles++;
            String id = stringValue(profile.get("id")).toLowerCase(java.util.Locale.ROOT);
            if (!LORE_PROFILE_ID.matcher(id).matches()) {
                throw new InvalidConfigurationException(path + ".id must match " + LORE_PROFILE_ID.pattern());
            }
            if (!ids.add(id)) throw new InvalidConfigurationException("Duplicate lore-sync profile id: " + id);
            String channel = stringValue(profile.get("channel-id"));
            if (channel.isBlank()) channel = defaultChannel;
            if (parsePositiveLong(channel) <= 0L) {
                throw new InvalidConfigurationException(path
                        + " needs a valid channel-id or lore-sync.default-channel-id; use WebEditor channel mapping");
            }
            String displayName = stringValue(profile.get("display-name"));
            if (displayName.isBlank() || displayName.length() > 80) {
                throw new InvalidConfigurationException(path + ".display-name must contain 1-80 characters");
            }
            String color = stringValue(profile.get("color"));
            if (!HEX_COLOR.matcher(color).matches()) {
                throw new InvalidConfigurationException(path + ".color must be a hex color such as #9B59B6");
            }
            validateOptionalHttps(stringValue(profile.get("avatar-url")), path + ".avatar-url");
            validateOptionalHttps(stringValue(profile.get("thumbnail-url")), path + ".thumbnail-url");
            validateOptionalHttps(stringValue(profile.get("image-url")), path + ".image-url");
        }
        if (enabledProfiles == 0) {
            throw new InvalidConfigurationException("lore-sync requires at least one enabled profile");
        }
    }

    private static void validateCompetitive(YamlConfiguration config)
            throws InvalidConfigurationException {
        String source = stringValue(config.get("competitive.rating.source")).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("BUILT_IN", "SERVICE").contains(source)) {
            throw new InvalidConfigurationException("competitive.rating.source must be BUILT_IN or SERVICE");
        }
        requireIntRange(config, "competitive.rating.initial", 100, 10_000);
        requireIntRange(config, "competitive.rating.minimum", 0, 10_000);
        if (config.getInt("competitive.rating.minimum") > config.getInt("competitive.rating.initial")) {
            throw new InvalidConfigurationException(
                    "competitive.rating.minimum cannot exceed competitive.rating.initial");
        }
        requireIntRange(config, "competitive.rating.k-factor", 1, 200);
        requireIntRange(config, "competitive.rating.provisional-k-factor", 1, 300);
        requireIntRange(config, "competitive.rating.provisional-matches", 0, 100);
        String elo = requireCommand(config, "competitive.discord.elo-command");
        String leaderboard = requireCommand(config, "competitive.discord.leaderboard-command");
        if (elo.equals(leaderboard)) {
            throw new InvalidConfigurationException("competitive Discord command names must be unique");
        }
        requireIntRange(config, "competitive.leaderboard.size", 3, 25);
        requireLongRange(config, "competitive.leaderboard.update-interval-seconds", 60L, 86_400L);
        requireIntRange(config, "competitive.leaderboard.minimum-matches", 0, 10_000);
        if (config.getBoolean("competitive.leaderboard.enabled", true)
                && parsePositiveLong(config.get("competitive.discord.channel-id")) <= 0L) {
            throw new InvalidConfigurationException(
                    "competitive.discord.channel-id is required for the live leaderboard; map it in WebEditor");
        }
    }

    private static void validateSmartConsole(YamlConfiguration config)
            throws InvalidConfigurationException {
        if (config.getBoolean("console.feed.enabled", true)
                && parsePositiveLong(config.get("console.channel-id")) <= 0L) {
            throw new InvalidConfigurationException(
                    "console.channel-id is required while the Smart Console feed is enabled");
        }
        requireLongRange(config, "console.feed.batch-interval-ticks", 20L, 1_200L);
        requireIntRange(config, "console.feed.maximum-lines-per-batch", 1, 200);
        requireIntRange(config, "console.feed.maximum-queued-lines", 50, 10_000);
        requireIntRange(config, "console.smart-console.include-stacktrace-lines", 0, 30);
        requireLongRange(config, "console.smart-console.deduplicate-window-seconds", 1L, 3_600L);
        requireLongRange(config, "console.smart-console.notification-cooldown-seconds", 0L, 86_400L);
        for (String path : List.of("console.feed.include-patterns", "console.feed.exclude-patterns",
                "console.feed.redact-patterns", "console.smart-console.alert-keywords",
                "console.smart-console.spam-patterns", "console.remote.deny-patterns")) {
            validateRegexList(config, path);
        }
        for (String level : config.getStringList("console.feed.levels")) {
            try {
                java.util.logging.Level.parse(level.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new InvalidConfigurationException("console.feed.levels contains invalid level: " + level);
            }
        }
        int roleIndex = 0;
        for (String role : config.getStringList("console.smart-console.developer-role-ids")) {
            if (parsePositiveLong(role) <= 0L) {
                throw new InvalidConfigurationException("console.smart-console.developer-role-ids["
                        + roleIndex + "] must be a Discord role ID");
            }
            roleIndex++;
        }
        String remoteMode = stringValue(config.get("console.remote.mode")).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("OFF", "ALLOWLIST", "FULL").contains(remoteMode)) {
            throw new InvalidConfigurationException("console.remote.mode must be OFF, ALLOWLIST or FULL");
        }
        String remotePrefix = stringValue(config.get("console.remote.prefix"));
        if (remotePrefix.isBlank() || remotePrefix.length() > 40
                || remotePrefix.indexOf('\0') >= 0 || remotePrefix.indexOf('\n') >= 0
                || remotePrefix.indexOf('\r') >= 0) {
            throw new InvalidConfigurationException(
                    "console.remote.prefix must contain 1-40 printable characters");
        }
        requireLongRange(config, "console.remote.cooldown-seconds", 0L, 3_600L);
        requireIntRange(config, "console.remote.maximum-command-length", 16, 1_000);
        requireIntRange(config, "console.remote.maximum-commands-per-minute", 1, 300);
        requireLongRange(config, "console.remote.audit-retention-days", 1L, 3_650L);
        if (!remoteMode.equals("OFF")) {
            Object configuredGuild = config.get("console.guild-id");
            if (parsePositiveLong(configuredGuild) <= 0L
                    && parsePositiveLong(config.get("discord.guild-id")) <= 0L) {
                throw new InvalidConfigurationException(
                        "console.guild-id or discord.guild-id is required for remote execution");
            }
            if (config.getStringList("console.remote.role-ids").isEmpty()) {
                throw new InvalidConfigurationException(
                        "console.remote.role-ids must not be empty when remote execution is enabled");
            }
            int remoteRoleIndex = 0;
            for (String role : config.getStringList("console.remote.role-ids")) {
                if (parsePositiveLong(role) <= 0L) {
                    throw new InvalidConfigurationException("console.remote.role-ids[" + remoteRoleIndex
                            + "] must be a Discord role ID");
                }
                remoteRoleIndex++;
            }
        }
        if (remoteMode.equals("ALLOWLIST")
                && config.getStringList("console.remote.allowlisted-commands").stream()
                .map(String::trim).noneMatch(value -> !value.isBlank())) {
            throw new InvalidConfigurationException(
                    "console.remote.allowlisted-commands cannot be empty in ALLOWLIST mode");
        }
        if (remoteMode.equals("FULL") && config.getStringList("console.remote.deny-patterns").isEmpty()) {
            throw new InvalidConfigurationException("console.remote.deny-patterns cannot be empty in FULL mode");
        }
        if (remoteMode.equals("FULL") && !config.getBoolean("console.remote.confirm-full-access", false)) {
            throw new InvalidConfigurationException(
                    "FULL remote mode requires console.remote.confirm-full-access: true");
        }
    }

    private static void validateServerEventEmbeds(YamlConfiguration config)
            throws InvalidConfigurationException {
        for (String event : List.of("startup", "shutdown", "join", "quit", "death")) {
            String root = "server-events.events." + event + ".embed.";
            String color = stringValue(config.get(root + "color"));
            if (!color.isBlank() && !HEX_COLOR.matcher(color).matches()) {
                throw new InvalidConfigurationException(root + "color must be a hex color such as #5865F2");
            }
            validateOptionalHttps(stringValue(config.get(root + "thumbnail-url")), root + "thumbnail-url");
            validateOptionalHttps(stringValue(config.get(root + "image-url")), root + "image-url");
        }
    }

    private static String requireCommand(YamlConfiguration config, String path)
            throws InvalidConfigurationException {
        String command = stringValue(config.get(path)).toLowerCase(java.util.Locale.ROOT);
        if (!COMMAND_NAME.matcher(command).matches()) {
            throw new InvalidConfigurationException(path + " must match " + COMMAND_NAME.pattern());
        }
        return command;
    }

    private static void validateRegexList(YamlConfiguration config, String path)
            throws InvalidConfigurationException {
        int index = 0;
        for (String expression : config.getStringList(path)) {
            try {
                Pattern.compile(expression);
            } catch (PatternSyntaxException error) {
                throw new InvalidConfigurationException(path + "[" + index + "] is invalid: "
                        + error.getDescription());
            }
            index++;
        }
    }

    private static void validateOptionalHttps(String value, String path)
            throws InvalidConfigurationException {
        if (value.isBlank()) return;
        if (value.length() > 2_048) {
            throw new InvalidConfigurationException(path + " must not exceed 2048 characters");
        }
        String parseable = value.replaceAll("%[A-Za-z0-9_.-]+%", "placeholder");
        try {
            URI uri = new URI(parseable);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new InvalidConfigurationException(path + " must be blank or an https:// URL");
            }
        } catch (URISyntaxException error) {
            throw new InvalidConfigurationException(path + " must be blank or a valid https:// URL");
        }
    }

    private static void requireIntRange(YamlConfiguration config, String path, int minimum, int maximum)
            throws InvalidConfigurationException {
        int value = config.getInt(path, minimum - 1);
        if (value < minimum || value > maximum) {
            throw new InvalidConfigurationException(path + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireLongRange(YamlConfiguration config, String path, long minimum, long maximum)
            throws InvalidConfigurationException {
        long value = config.getLong(path, minimum - 1L);
        if (value < minimum || value > maximum) {
            throw new InvalidConfigurationException(path + " must be between " + minimum + " and " + maximum);
        }
    }

    private static double finiteDouble(Object raw, String path) throws InvalidConfigurationException {
        try {
            double value = raw instanceof Number number
                    ? number.doubleValue() : Double.parseDouble(stringValue(raw));
            if (!Double.isFinite(value)) throw new NumberFormatException("not finite");
            return value;
        } catch (NumberFormatException error) {
            throw new InvalidConfigurationException(path + " must be a finite number");
        }
    }

    private static boolean booleanValue(Object raw, boolean fallback) {
        if (raw instanceof Boolean value) return value;
        return raw == null ? fallback : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
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
        Set<PosixFilePermission> permissions = readPosixPermissions(file.toPath());
        Path temporary;
        if (permissions == null) {
            temporary = Files.createTempFile(directory, ".coredsc-", ".tmp");
        } else {
            FileAttribute<Set<PosixFilePermission>> attribute =
                    PosixFilePermissions.asFileAttribute(permissions);
            temporary = Files.createTempFile(directory, ".coredsc-", ".tmp", attribute);
        }
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveReplace(temporary, file.toPath());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Set<PosixFilePermission> readPosixPermissions(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException ignored) {
            return null;
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
