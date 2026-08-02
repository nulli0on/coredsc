package com.hubertstudios.coredsc.module;

import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.List;


public interface DiscordCommandContributor {
    List<CommandData> slashCommands();
}
