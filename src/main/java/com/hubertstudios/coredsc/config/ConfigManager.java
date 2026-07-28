package com.hubertstudios.coredsc.config;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads CoreDSC's modular YAML files into one immutable-at-runtime snapshot.
 * Modules can keep using familiar dotted paths while server owners edit small,
 * purpose-specific files.
 */
public final class ConfigManager {
    private static final List<String> MODULE_IDS = List.of(
            "placeholderapi", "delivery-queue", "network", "link", "link-rewards",
            "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync",
            "chat-sync", "console", "server-events", "custom-commands", "status-channels", "cases",
            "moderation-bridge", "tickets", "reports", "applications", "workflows",
            "authme", "voicechat-sync"
    );
    private static final Set<String> GLOBAL_ROOTS = Set.of(
            "config-version", "language", "debug", "discord", "storage", "performance"
    );

    private final CoreDSCPlugin plugin;
    private final File dataFolder;
    private final Map<String, File> moduleFiles = new LinkedHashMap<>();
    private volatile YamlConfiguration active = new YamlConfiguration();

    public ConfigManager(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder();
        for (String id : MODULE_IDS) {
            moduleFiles.put(id, new File(dataFolder, "modules/" + id + ".yml"));
        }
    }

    public synchronized void initialize() throws IOException, InvalidConfigurationException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Could not create CoreDSC data directory: " + dataFolder);
        }
        File root = new File(dataFolder, "config.yml");
        if (!root.isFile()) {
            copyResource("config.yml", root, false);
        }

        YamlConfiguration existingRoot = loadStrict(root);
        if (isLegacy(existingRoot)) {
            migrateLegacy(existingRoot, root);
        }

        ensureResource("messages.yml");
        ensureResource("guis/report-gui.yml");
        ensureResource("bot/config.yml");
        ensureResource("bot/README.md");
        // Runtime files are CoreDSC-owned and must match the installed plugin version.
        copyResource("bot/runtime/worker.py", new File(dataFolder, "bot/runtime/worker.py"), true);
        copyResource("bot/runtime/coredsc_api.py", new File(dataFolder, "bot/runtime/coredsc_api.py"), true);
        ensureResource("bot/scripts/example_command.py");
        ensureResource("bot/scripts/example_event.py");
        ensureResource("bot/scripts/example_external_event.py");
        for (String id : MODULE_IDS) {
            ensureResource("modules/" + id + ".yml");
        }
        reload();
    }

    public synchronized void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration merged = new YamlConfiguration();
        YamlConfiguration root = loadStrict(new File(dataFolder, "config.yml"));
        copyAll(root, merged, "");

        YamlConfiguration messages = loadStrict(new File(dataFolder, "messages.yml"));
        copyAll(messages, merged, "messages");

        for (Map.Entry<String, File> entry : moduleFiles.entrySet()) {
            String id = entry.getKey();
            YamlConfiguration module = loadStrict(entry.getValue());
            Object enabledRaw = module.get("enabled");
            if (!(enabledRaw instanceof Boolean)) {
                throw new InvalidConfigurationException(entry.getValue().getPath()
                        + ": enabled must be true or false");
            }
            merged.set("modules." + id, enabledRaw);
            for (String key : module.getKeys(true)) {
                if (key.equals("enabled") || module.isConfigurationSection(key)) {
                    continue;
                }
                merged.set(id + "." + key, module.get(key));
            }
        }

        YamlConfiguration reportGui = loadStrict(new File(dataFolder, "guis/report-gui.yml"));
        copyAll(reportGui, merged, "reports.gui");

        YamlConfiguration bot = loadStrict(new File(dataFolder, "bot/config.yml"));
        Object botEnabled = bot.get("enabled");
        if (!(botEnabled instanceof Boolean)) {
            throw new InvalidConfigurationException("bot/config.yml: enabled must be true or false");
        }
        merged.set("modules.python-bot", botEnabled);
        copyAll(bot, merged, "bot");

        validate(merged);
        active = merged;
    }

    public FileConfiguration getConfig() {
        return active;
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
        yaml.save(file);
    }

    /** Persists a known dotted runtime path into its owning modular file. */
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

    private void setInFile(File file, String path, Object value)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = loadStrict(file);
        yaml.set(path, value);
        yaml.save(file);
    }

    private void migrateLegacy(YamlConfiguration legacy, File root)
            throws IOException, InvalidConfigurationException {
        File backup = new File(dataFolder, "config.yml.pre-modular-" + Instant.now().toEpochMilli() + ".bak");
        Files.copy(root.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        plugin.getLogger().warning("Legacy single-file configuration detected. Backup created at "
                + backup.getName());

        File modulesDirectory = new File(dataFolder, "modules");
        File guisDirectory = new File(dataFolder, "guis");
        if (!modulesDirectory.exists() && !modulesDirectory.mkdirs()) {
            throw new IOException("Could not create " + modulesDirectory);
        }
        if (!guisDirectory.exists() && !guisDirectory.mkdirs()) {
            throw new IOException("Could not create " + guisDirectory);
        }

        for (String id : MODULE_IDS) {
            File target = moduleFiles.get(id);
            if (target.isFile()) {
                continue;
            }
            YamlConfiguration module = loadResourceYaml("modules/" + id + ".yml");
            module.set("enabled", legacy.getBoolean("modules." + id, module.getBoolean("enabled", false)));
            ConfigurationSection section = legacy.getConfigurationSection(id);
            if (section != null) {
                copySection(section, module, "");
                if (id.equals("reports")) {
                    module.set("gui", null);
                }
            }
            module.save(target);
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
                copySection(botSection, bot, "");
            }
            bot.save(botFile);
        }

        File guiFile = new File(dataFolder, "guis/report-gui.yml");
        if (!guiFile.isFile()) {
            ConfigurationSection gui = legacy.getConfigurationSection("reports.gui");
            if (gui != null) {
                YamlConfiguration guiYaml = new YamlConfiguration();
                copySection(gui, guiYaml, "");
                guiYaml.save(guiFile);
            } else {
                copyResource("guis/report-gui.yml", guiFile, false);
            }
        }

        YamlConfiguration clean = new YamlConfiguration();
        YamlConfiguration defaults = loadResourceYaml("config.yml");
        copyAll(defaults, clean, "");
        for (String key : legacy.getKeys(false)) {
            if (key.equals("modules") || key.equals("bot") || key.equals("python-bot")
                    || MODULE_IDS.contains(key)) {
                continue;
            }
            if (GLOBAL_ROOTS.contains(key) || !defaults.contains(key)) {
                copyPath(legacy, clean, key, key);
            }
        }
        clean.set("config-version", 3);
        clean.save(root);
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

    private void ensureResource(String resourcePath) throws IOException {
        File destination = new File(dataFolder, resourcePath);
        if (!destination.isFile()) {
            copyResource(resourcePath, destination, false);
        }
    }

    private void copyResource(String resourcePath, File destination, boolean replace) throws IOException {
        if (destination.exists() && !replace) {
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            try (FileOutputStream output = new FileOutputStream(destination)) {
                input.transferTo(output);
            }
        }
    }

    private YamlConfiguration loadResourceYaml(String path)
            throws IOException, InvalidConfigurationException {
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IOException("Missing bundled YAML resource: " + path);
            }
            File temporary = File.createTempFile("coredsc-resource-", ".yml");
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    input.transferTo(output);
                }
                return loadStrict(temporary);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
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

    private static void copyAll(ConfigurationSection source, ConfigurationSection target, String prefix) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                continue;
            }
            String destination = prefix == null || prefix.isBlank() ? key : prefix + "." + key;
            target.set(destination, source.get(key));
        }
    }

    private static void copySection(ConfigurationSection source, ConfigurationSection target, String prefix) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
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
            copySection(section, target, targetPath);
        } else if (source.contains(sourcePath)) {
            target.set(targetPath, source.get(sourcePath));
        }
    }
}
