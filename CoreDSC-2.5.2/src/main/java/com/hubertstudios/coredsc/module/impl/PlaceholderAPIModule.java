package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.service.PlaceholderService;

                                                                              
public final class PlaceholderAPIModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private boolean available;

    public PlaceholderAPIModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "placeholderapi";
    }

    @Override
    public void enable() {
        PlaceholderService service = PlaceholderService.detect(plugin);
        available = service.isAvailable();
        plugin.setPlaceholderService(service);
        if (available) {
            plugin.getLogger().info("[PlaceholderAPI] Integration enabled.");
        } else {
            plugin.getLogger().info("[PlaceholderAPI] Plugin not installed; templates use CoreDSC placeholders only.");
        }
    }

    @Override
    public void disable() {
        plugin.setPlaceholderService(PlaceholderService.disabled(plugin));
        available = false;
    }

    @Override
    public String statusDetail() {
        return available ? "PlaceholderAPI active" : "pass-through (PlaceholderAPI not installed)";
    }
}
