package com.hubertstudios.coredsc.scheduler;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A primitive hand-off for command feedback.
 *
 * <p>Player and entity command senders are never retained after command dispatch. Player
 * feedback is resolved from its UUID on the owning entity scheduler. Region-bound command
 * blocks and non-player entities are deliberately redirected to the local console because
 * Bukkit does not expose a stable primitive lookup for their transient command sender.</p>
 */
public final class CommandReplyTarget {
    private final CoreDSCPlugin plugin;
    private final UUID playerId;
    private final CommandSender nonRegionSender;
    private final String redirectedSenderName;

    private CommandReplyTarget(
            CoreDSCPlugin plugin,
            UUID playerId,
            CommandSender nonRegionSender,
            String redirectedSenderName
    ) {
        this.plugin = plugin;
        this.playerId = playerId;
        this.nonRegionSender = nonRegionSender;
        this.redirectedSenderName = redirectedSenderName;
    }

    public static CommandReplyTarget capture(CoreDSCPlugin plugin, CommandSender sender) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(sender, "sender");
        if (sender instanceof Player player) {
            return new CommandReplyTarget(plugin, player.getUniqueId(), null, "");
        }
        if (sender instanceof Entity || sender instanceof BlockCommandSender) {
            return new CommandReplyTarget(plugin, null, null, sender.getName());
        }
        return new CommandReplyTarget(plugin, null, sender, "");
    }

    public void execute(Consumer<CommandSender> action) {
        Objects.requireNonNull(action, "action");
        if (playerId != null) {
            plugin.runForPlayer(playerId, action::accept).exceptionally(error -> {
                plugin.getLogger().warning("Could not deliver command feedback to player "
                        + playerId + ": " + rootMessage(error));
                return false;
            });
            return;
        }
        plugin.runSync(() -> {
            CommandSender target = nonRegionSender == null
                    ? plugin.getServer().getConsoleSender()
                    : nonRegionSender;
            if (!redirectedSenderName.isBlank()) {
                target.sendMessage("[CoreDSC] Feedback for region-bound sender '"
                        + redirectedSenderName + "':");
            }
            action.accept(target);
        });
    }

    public void send(String message) {
        execute(sender -> sender.sendMessage(message));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
