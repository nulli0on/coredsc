package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.service.RewardExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;


public final class LinkRewardsModule implements CoreModule, Listener {
    private final CoreDSCPlugin plugin;
    private RewardExecutor rewards;
    private List<String> commands = List.of();

    public LinkRewardsModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "link-rewards";
    }

    @Override
    public void enable() {
        FileConfiguration config = plugin.getAppConfig();
        commands = config.getStringList("link-rewards.commands").stream()
                .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("link-rewards.commands must contain at least one command");
        }
        rewards = new RewardExecutor(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        rewards.resume("FIRST_LINK", commands);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (rewards != null) rewards.shutdown();
        rewards = null;
        commands = List.of();
    }

    @EventHandler
    public void onAccountLinked(AccountLinkedEvent event) {
        if (rewards == null) return;
        rewards.grant(
                "first-link:" + event.minecraftUuid(),
                "FIRST_LINK",
                event.minecraftUuid(),
                event.minecraftName(),
                event.discordUserId(),
                commands,
                () -> plugin.recordFeatureUse("link_reward")
        );
    }

    @Override
    public String statusDetail() {
        return commands.isEmpty() ? "enabled; no reward commands configured" : commands.size() + " reward command(s)";
    }
}
