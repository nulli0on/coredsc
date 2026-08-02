package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.scheduler.CoreTask;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;


public final class CustomCommandsModule implements CoreModule, DiscordCommandContributor {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<String> RESERVED = Set.of("coredsc","link","unlink","account","ticket","report","case","appeal","apply","application","resetpassword","lore","balance","inventory","market","elo","leaderboard");
    private final CoreDSCPlugin plugin;
    private volatile Map<String,CustomCommand> commands=Map.of();
    private final Map<String,Long> cooldowns=new ConcurrentHashMap<>();
    private LinkedAccountRepository links;
    private ListenerAdapter discordListener;
    private final List<BukkitCommand> registeredMinecraftCommands = new ArrayList<>();
    private boolean allowConsoleActions;
    private List<String> consoleAllowPrefixes=List.of();
    private String serverAddress;
    private volatile Map<String,String> cachedServerValues=Map.of();
    private CoreTask serverValuesTask;

    public CustomCommandsModule(CoreDSCPlugin plugin){this.plugin=plugin;}
    @Override public String id(){return "custom-commands";}

    @Override public void enable(){
        SQLiteStorage storage=plugin.getStorage();if(storage==null||storage.getState()!=SQLiteStorage.State.READY)throw new IllegalStateException("SQLite storage is not ready");
        links=new LinkedAccountRepository(storage);DiscordBotService discord=plugin.getDiscordService();if(discord==null)throw new IllegalStateException("Discord service unavailable");
        allowConsoleActions=plugin.getAppConfig().getBoolean("custom-commands.allow-console-actions",false);
        consoleAllowPrefixes=plugin.getAppConfig().getStringList("custom-commands.console-command-allow-prefixes").stream().map(v->v.trim().toLowerCase(Locale.ROOT)).filter(v->!v.isBlank()).toList();
        serverAddress=value(plugin.getAppConfig().getString("custom-commands.server-address"));
        refreshServerValues();
        serverValuesTask=plugin.getCoreScheduler().runGlobalTimer(this::refreshServerValues,20L,20L);
        load(plugin.getAppConfig().getMapList("custom-commands.commands"));
        discordListener=new ListenerAdapter(){@Override public void onSlashCommandInteraction(SlashCommandInteractionEvent e){CustomCommand c=commands.get(e.getName());if(c!=null&&c.platforms().contains("DISCORD"))handleDiscord(e,c);}};
        discord.addEventListener(discordListener);
        registerMinecraftCommands();
    }

    @Override public void disable(){
        if(discordListener!=null&&plugin.getDiscordService()!=null)plugin.getDiscordService().removeEventListener(discordListener);
        discordListener=null;
        unregisterMinecraftCommands();
        commands=Map.of();
        cooldowns.clear();
        if(serverValuesTask!=null)serverValuesTask.cancel();
        serverValuesTask=null;
        cachedServerValues=Map.of();
    }
    @Override public String statusDetail(){return commands.size()+" shared command(s)";}

    @Override public List<CommandData> slashCommands(){List<CommandData>out=new ArrayList<>();for(CustomCommand c:commands.values()){if(!c.platforms().contains("DISCORD"))continue;SlashCommandData slash=Commands.slash(c.name(),c.description());for(CustomOption o:c.options()){OptionData data=new OptionData(o.type(),o.name(),o.description(),o.required());if(o.type()==OptionType.STRING)data.setMaxLength(o.maxLength());slash.addOptions(data);}out.add(slash);}return out;}

    private void registerMinecraftCommands(){
        CommandMap commandMap=plugin.getServer().getCommandMap();
        for(CustomCommand configured:commands.values()){
            if(!configured.platforms().contains("MINECRAFT"))continue;
            BukkitCommand command=new BukkitCommand(configured.name(),configured.description(),
                    "/"+configured.name(),List.of()){
                @Override public boolean execute(CommandSender sender,String commandLabel,String[]args){
                    if(!testPermission(sender))return true;
                    handleMinecraft(sender,configured,args);
                    return true;
                }
                @Override public List<String> tabComplete(CommandSender sender,String alias,String[]args){
                    return super.tabComplete(sender,alias,args);
                }
            };
            if(!configured.minecraftPermission().isBlank())command.setPermission(configured.minecraftPermission());
            boolean primary=commandMap.register("coredsc",command);
            registeredMinecraftCommands.add(command);
            if(!primary)plugin.getLogger().warning("[CustomCommands] /"+configured.name()
                    +" conflicts with an existing command; use /coredsc:"+configured.name()+" instead.");
        }
        plugin.getServer().getOnlinePlayers().forEach(player ->
                plugin.runForEntity(player, player::updateCommands));
    }

    private void unregisterMinecraftCommands(){
        if(registeredMinecraftCommands.isEmpty())return;
        CommandMap commandMap=plugin.getServer().getCommandMap();
        if(commandMap instanceof SimpleCommandMap simpleCommandMap){
            simpleCommandMap.getKnownCommands().entrySet().removeIf(entry ->
                    registeredMinecraftCommands.stream().anyMatch(command -> entry.getValue() == command));
        }
        for(BukkitCommand command:registeredMinecraftCommands){
            try{command.unregister(commandMap);}catch(RuntimeException error){
                plugin.getLogger().warning("[CustomCommands] Could not unregister /"+command.getName()+": "+rootMessage(error));
            }
        }
        registeredMinecraftCommands.clear();
        plugin.getServer().getOnlinePlayers().forEach(player ->
                plugin.runForEntity(player, player::updateCommands));
    }

    private void handleMinecraft(CommandSender sender,CustomCommand command,String[]args){
        if(!command.minecraftPermission().isBlank()&&!sender.hasPermission(command.minecraftPermission())){
            sender.sendMessage("§cYou do not have permission.");
            return;
        }
        Player player=sender instanceof Player online?online:null;
        UUID playerId=player==null?null:player.getUniqueId();
        Map<String,String> values=eventValues(sender,args);
        if(command.linkedOnly()){
            if(player==null){sender.sendMessage("§cThis command requires a linked player account.");return;}
            links.findByMinecraftUuid(playerId.toString()).whenComplete((link,error)->{
                if(error!=null||link.isEmpty()){
                    replyToMinecraft(sender,playerId,"§cYou must link your Discord account first.");
                    return;
                }
                execute(new Context("MINECRAFT",playerId,link,values),command)
                        .whenComplete((message,ex)->replyToMinecraft(sender,playerId,
                                ex==null?TextUtil.colorize(message):"§cCommand failed: "+rootMessage(ex)));
            });
            return;
        }
        execute(new Context("MINECRAFT",playerId,Optional.empty(),values),command)
                .whenComplete((message,error)->replyToMinecraft(sender,playerId,
                        error==null?TextUtil.colorize(message):"§cCommand failed: "+rootMessage(error)));
    }

    private void handleDiscord(SlashCommandInteractionEvent event,CustomCommand command){
        if(command.guildOnly()&&!event.isFromGuild()){event.reply("This command is guild-only.").setEphemeral(true).queue();return;}
        if(!hasRole(event.getMember(),command.allowedRoleIds())){event.reply("You do not have a required role.").setEphemeral(true).queue();return;}
        event.deferReply(command.ephemeral()).queue(hook->{
            CompletableFuture<Optional<LinkedAccount>> linkFuture=command.linkedOnly()
                    ?links.findByDiscordUserId(event.getUser().getId())
                    :CompletableFuture.completedFuture(Optional.empty());
            linkFuture.thenCompose(link->{
                if(command.linkedOnly()&&link.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("Link your Minecraft account first."));
                UUID playerId=link.map(LinkedAccount::minecraftUuid)
                        .map(CustomCommandsModule::parseUuid).orElse(null);
                return execute(new Context("DISCORD",playerId,link,
                        discordValues(event,command)),command);
            }).whenComplete((message,error)->edit(hook,error==null?message:rootMessage(error)));
        });
    }

    private CompletableFuture<String> execute(Context context,CustomCommand command){
        String identity=context.platform().equals("MINECRAFT")
                ?context.values().getOrDefault("minecraft_uuid","unknown")
                :context.values().getOrDefault("discord_user_id","unknown");
        long remaining=claimCooldown(command,context.platform()+":"+identity);
        if(remaining>0)return CompletableFuture.failedFuture(new IllegalStateException("Wait "+Math.max(1,(remaining+999)/1000)+" second(s)."));
        CompletableFuture<Void>chain=CompletableFuture.completedFuture(null);
        StringBuilder response=new StringBuilder();
        for(Action action:command.actions())chain=chain.thenCompose(v->executeAction(context,action,response));
        return chain.thenCompose(v->renderAsync(command.response(),context)).thenApply(configured->{
            if(!configured.isBlank()){if(response.length()>0)response.append('\n');response.append(configured);}
            plugin.recordFeatureUse("custom_command");
            return TextUtil.truncate(response.length()==0?"Command completed.":response.toString(),2000);
        });
    }

    private CompletableFuture<Void> executeAction(Context context,Action action,StringBuilder response){
        String type=action.type();
        return switch(type){
            case "SEND_MESSAGE"->renderAsync(action.value("message"),context).thenAccept(msg->{if(!msg.isBlank()){if(response.length()>0)response.append('\n');response.append(msg);}});
            case "SEND_DISCORD_MESSAGE"->callForContext(context,placeholder->new String[]{
                    render(action.value("channel-id"),context,placeholder),
                    render(action.value("message"),context,placeholder)})
                    .thenCompose(values->sendDiscord(values[0],values[1]));
            case "RUN_CONSOLE_COMMAND"->callForContext(context,
                    placeholder->renderConsoleCommand(action.value("command"),context))
                    .thenCompose(rendered -> plugin.callSync(() -> {
                        runConsole(rendered);
                        return null;
                    }));
            case "ADD_DISCORD_ROLE"->discordRole(context,action.value("role-id"),true);
            case "REMOVE_DISCORD_ROLE"->discordRole(context,action.value("role-id"),false);
            case "ADD_LUCKPERMS_GROUP"->luckPerms(context,action.value("group"),true);
            case "REMOVE_LUCKPERMS_GROUP"->luckPerms(context,action.value("group"),false);
            case "CREATE_TICKET"->callForContext(context,placeholder->new String[]{
                    render(action.value("reason"),context,placeholder),
                    render(action.value("message"),context,placeholder)})
                    .thenCompose(values->createTicket(context,values[0],values[1]));
            case "TRIGGER_WORKFLOW"->{WorkflowModule workflows=module(WorkflowModule.class);if(workflows!=null)plugin.runSync(()->workflows.trigger(action.value("trigger"),context.values()));yield CompletableFuture.completedFuture(null);}
            default->CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported action: "+type));
        };
    }

    private CompletableFuture<String> renderAsync(String template,Context context){
        return callForContext(context,placeholder->render(template,context,placeholder));
    }

    private <T> CompletableFuture<T> callForContext(
            Context context,
            Function<OfflinePlayer,T> function
    ) {
        UUID playerId=context.playerId();
        if(playerId==null)return plugin.callSync(()->function.apply(null));
        return plugin.callForPlayer(playerId,online->function.apply(online))
                .thenCompose(result->result.map(CompletableFuture::completedFuture)
                        .orElseGet(()->plugin.callSync(()->
                                function.apply(Bukkit.getOfflinePlayer(playerId)))));
    }

    private CompletableFuture<Void> sendDiscord(String channelId,String message){DeliveryQueueModule queue=module(DeliveryQueueModule.class);if(queue!=null)return queue.enqueue(channelId,message,5,"").thenApply(v->null);JDA j=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();TextChannel channel=j==null?null:j.getTextChannelById(channelId);if(channel==null)return CompletableFuture.failedFuture(new IllegalStateException("Discord channel unavailable"));return channel.sendMessage(TextUtil.truncate(TextUtil.sanitizeMassMentions(message),2000)).setAllowedMentions(java.util.Collections.emptyList()).submit().thenApply(v->null);}
    private CompletableFuture<Void> discordRole(Context context,String roleId,boolean add){String discordId=context.link().map(LinkedAccount::discordUserId).orElse(context.values().getOrDefault("discord_user_id",""));if(discordId.isBlank())return CompletableFuture.failedFuture(new IllegalStateException("No linked Discord account"));JDA j=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();long guildId=TextUtil.parsePositiveLong(plugin.getAppConfig().get("discord.guild-id"));var guild=j==null?null:j.getGuildById(guildId);var role=guild==null?null:guild.getRoleById(roleId);if(guild==null||role==null)return CompletableFuture.failedFuture(new IllegalStateException("Guild/role unavailable"));return guild.retrieveMemberById(discordId).submit().thenCompose(member->(add?guild.addRoleToMember(member,role):guild.removeRoleFromMember(member,role)).submit()).thenApply(v->null);}
    private CompletableFuture<Void> luckPerms(Context context,String group,boolean add){UUID uuid=parseUuid(context.values().get("minecraft_uuid"));if(uuid==null)uuid=context.link().map(LinkedAccount::minecraftUuid).map(CustomCommandsModule::parseUuid).orElse(null);if(uuid==null)return CompletableFuture.failedFuture(new IllegalStateException("No Minecraft account"));try{var lp=LuckPermsProvider.get();return lp.getUserManager().loadUser(uuid).thenCompose(user->{var node=InheritanceNode.builder(group).build();if(add)user.data().add(node);else user.data().remove(node);return lp.getUserManager().saveUser(user);});}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private CompletableFuture<Void> createTicket(Context context,String reason,String message){UUID uuid=parseUuid(context.values().get("minecraft_uuid"));if(uuid==null)uuid=context.link().map(LinkedAccount::minecraftUuid).map(CustomCommandsModule::parseUuid).orElse(null);TicketModule tickets=module(TicketModule.class);if(uuid==null||tickets==null)return CompletableFuture.failedFuture(new IllegalStateException("Ticket module/account unavailable"));return tickets.createTicketForPlayer(uuid,reason,message).thenCompose(result->result.success()?CompletableFuture.completedFuture(null):CompletableFuture.failedFuture(new IllegalStateException(result.message())));}
    private String renderConsoleCommand(String template, Context context) {
        Map<String,String> safe = new LinkedHashMap<>();
        String minecraftName = context.link().map(LinkedAccount::minecraftName)
                .orElse(context.values().getOrDefault("minecraft_name", ""));
        String minecraftUuid = context.link().map(LinkedAccount::minecraftUuid)
                .orElse(context.values().getOrDefault("minecraft_uuid", ""));
        String discordUserId = context.link().map(LinkedAccount::discordUserId)
                .orElse(context.values().getOrDefault("discord_user_id", ""));
        if (TextUtil.isSafeMinecraftName(minecraftName)) safe.put("minecraft_name", minecraftName);
        if (TextUtil.isUuid(minecraftUuid)) safe.put("minecraft_uuid", minecraftUuid);
        if (TextUtil.isPositiveSnowflake(discordUserId)) safe.put("discord_user_id", discordUserId);
        return TextUtil.renderRestrictedCommand(template, safe);
    }

    private void runConsole(String command){String lower=command.toLowerCase(Locale.ROOT).trim();if(!allowConsoleActions||consoleAllowPrefixes.stream().noneMatch(prefix->commandMatchesPrefix(lower,prefix)))throw new IllegalStateException("Console action is not allowlisted");if(command.contains("\n")||command.contains("\r"))throw new IllegalStateException("Unsafe console action blocked");Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command);}

    private void load(List<Map<?,?>>rawCommands){Map<String,CustomCommand>loaded=new LinkedHashMap<>();for(Map<?,?>raw:rawCommands){if(!bool(raw.get("enabled"),true))continue;String name=text(raw.get("name")).toLowerCase(Locale.ROOT);if(!NAME.matcher(name).matches()||RESERVED.contains(name)||loaded.containsKey(name))throw new IllegalArgumentException("Invalid/reserved custom command: "+name);String description=text(raw.get("description"));if(description.isBlank()||description.length()>100)throw new IllegalArgumentException("Description required for "+name);Set<String>platforms=parsePlatforms(raw.get("platforms"));List<Long>roles=parseIds(raw.get("allowed-role-ids"));List<CustomOption>options=parseOptions(raw.get("options"),name);List<Action>actions=parseActions(raw.get("actions"));String legacyConsole=text(raw.get("console-command"));if(!legacyConsole.isBlank()){List<Action> expanded=new ArrayList<>(actions);expanded.add(new Action("RUN_CONSOLE_COMMAND",Map.of("command",legacyConsole)));actions=expanded;}loaded.put(name,new CustomCommand(name,description,platforms,text(raw.get("minecraft-permission")),bool(raw.get("ephemeral"),true),bool(raw.get("guild-only"),false),bool(raw.get("linked-only"),false),Math.max(0,number(raw.get("cooldown-seconds"),5))*1000,roles,text(raw.get("response")),List.copyOf(actions),options));}commands=Map.copyOf(loaded);}
    private List<Action> parseActions(Object raw){List<Action>out=new ArrayList<>();if(raw instanceof List<?>list)for(Object item:list)if(item instanceof Map<?,?>map){String type=text(map.get("type")).toUpperCase(Locale.ROOT);Map<String,String>values=new LinkedHashMap<>();map.forEach((k,v)->values.put(text(k).toLowerCase(Locale.ROOT),text(v)));out.add(new Action(type,Map.copyOf(values)));}return out;}
    private List<CustomOption> parseOptions(Object raw,String command){List<CustomOption>out=new ArrayList<>();if(!(raw instanceof List<?>list))return List.of();Set<String>names=new HashSet<>();for(Object item:list){if(!(item instanceof Map<?,?>map))continue;String name=text(map.get("name")).toLowerCase(Locale.ROOT);if(!NAME.matcher(name).matches()||!names.add(name))throw new IllegalArgumentException("Invalid option in "+command);OptionType type=switch(text(map.get("type")).toLowerCase(Locale.ROOT)){case "integer"->OptionType.INTEGER;case "boolean"->OptionType.BOOLEAN;case "user"->OptionType.USER;default->OptionType.STRING;};String description=text(map.get("description"));if(description.isBlank()||description.length()>100)throw new IllegalArgumentException("Option "+name+" in "+command+" requires a 1-100 character description");int max=(int)Math.max(1,Math.min(6000,number(map.get("max-length"),200)));out.add(new CustomOption(name,description,type,bool(map.get("required"),false),max));}out.sort(Comparator.comparing(CustomOption::required).reversed());return List.copyOf(out);}

    private Map<String,String> eventValues(CommandSender sender,String[]args){Map<String,String>v=baseValues();v.put("sender_name",sender.getName());if(sender instanceof Player player){v.put("minecraft_name",player.getName());v.put("minecraft_uuid",player.getUniqueId().toString());}v.put("args",String.join(" ",args));for(int i=0;i<args.length;i++)v.put("arg_"+(i+1),args[i]);return v;}
    private Map<String,String> discordValues(SlashCommandInteractionEvent e,CustomCommand c){Map<String,String>v=baseValues();v.put("discord_user",e.getUser().getName());v.put("discord_user_id",e.getUser().getId());v.put("discord_display_name",e.getMember()==null?e.getUser().getEffectiveName():e.getMember().getEffectiveName());for(CustomOption o:c.options()){OptionMapping m=e.getOption(o.name());String key="option_"+o.name();if(m==null)v.put(key,"");else if(o.type()==OptionType.USER){v.put(key,m.getAsUser().getName());v.put(key+"_id",m.getAsUser().getId());}else if(o.type()==OptionType.INTEGER)v.put(key,Long.toString(m.getAsLong()));else if(o.type()==OptionType.BOOLEAN)v.put(key,Boolean.toString(m.getAsBoolean()));else v.put(key,m.getAsString());}return v;}
    private void refreshServerValues(){Map<String,String>v=new LinkedHashMap<>();v.put("server_name",plugin.getServer().getName());v.put("server_version",plugin.getServer().getVersion());v.put("server_address",serverAddress);v.put("online_players",Integer.toString(plugin.getServer().getOnlinePlayers().size()));v.put("max_players",Integer.toString(plugin.getServer().getMaxPlayers()));v.put("online_player_names",plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().collect(java.util.stream.Collectors.joining(", ")));cachedServerValues=Map.copyOf(v);}
    private Map<String,String> baseValues(){Map<String,String>v=new LinkedHashMap<>(cachedServerValues);v.put("uptime",formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()/1000));return v;}
    private String render(String template,Context context,OfflinePlayer placeholder){Map<String,Object>objects=new LinkedHashMap<>(context.values());context.link().ifPresent(link->{objects.put("minecraft_name",link.minecraftName());objects.put("minecraft_uuid",link.minecraftUuid());objects.put("discord_user_id",link.discordUserId());});String rendered=TextUtil.replace(template==null?"":template,objects);return plugin.getPlaceholderService().apply(placeholder,rendered);}

    private void replyToMinecraft(CommandSender sender,UUID playerId,String message){
        if(playerId!=null){plugin.runForPlayer(playerId,online->online.sendMessage(message));return;}
        plugin.runSync(()->sender.sendMessage(message));
    }

    private long claimCooldown(CustomCommand c,String id){if(c.cooldownMillis()<=0)return 0;long now=System.currentTimeMillis();String key=c.name()+":"+id;long[]remaining={0};cooldowns.compute(key,(k,previous)->{if(previous!=null&&now-previous<c.cooldownMillis()){remaining[0]=c.cooldownMillis()-(now-previous);return previous;}return now;});return remaining[0];}
    private static boolean hasRole(Member member,List<Long>ids){if(ids.isEmpty())return true;if(member==null)return false;for(Role r:member.getRoles())if(ids.contains(r.getIdLong()))return true;return false;}
    private <T extends CoreModule>T module(Class<T>type){return plugin.getModuleManager()==null?null:plugin.getModuleManager().getModule(type);}
    private void edit(InteractionHook h,String m){h.editOriginal(TextUtil.truncate(TextUtil.sanitizeMassMentions(m),2000)).setAllowedMentions(java.util.Collections.emptyList()).queue();}
    private static Set<String> parsePlatforms(Object raw){Set<String>out=new HashSet<>();if(raw instanceof List<?>list)for(Object v:list){String p=text(v).toUpperCase(Locale.ROOT);if(p.equals("MINECRAFT")||p.equals("DISCORD"))out.add(p);}if(out.isEmpty())out.add("DISCORD");return Set.copyOf(out);}
    private static List<Long> parseIds(Object raw){List<Long>out=new ArrayList<>();if(raw instanceof List<?>list)for(Object v:list)try{long id=Long.parseLong(v.toString());if(id>0)out.add(id);}catch(Exception ignored){}return List.copyOf(out);}
    private static UUID parseUuid(String s){try{return s==null||s.isBlank()?null:UUID.fromString(s);}catch(Exception e){return null;}}
    private static String formatUptime(long s){long d=s/86400,h=s%86400/3600,m=s%3600/60;return d>0?d+"d "+h+"h "+m+"m":h+"h "+m+"m";}
    private static boolean commandMatchesPrefix(String command,String prefix){return command.equals(prefix)||command.startsWith(prefix+" ");}
    private static String value(String v){return v==null?"":v.trim();}
    private static String text(Object v){return v==null?"":v.toString().trim();}
    private static boolean bool(Object v,boolean f){return v==null?f:Boolean.parseBoolean(v.toString());}
    private static long number(Object v,long f){if(v instanceof Number n)return n.longValue();try{return Long.parseLong(text(v));}catch(Exception e){return f;}}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record CustomCommand(String name,String description,Set<String>platforms,String minecraftPermission,boolean ephemeral,boolean guildOnly,boolean linkedOnly,long cooldownMillis,List<Long>allowedRoleIds,String response,List<Action>actions,List<CustomOption>options){}
    private record Action(String type,Map<String,String>values){String value(String key){return values.getOrDefault(key,"");}}
    private record CustomOption(String name,String description,OptionType type,boolean required,int maxLength){}
    private record Context(String platform,UUID playerId,Optional<LinkedAccount>link,Map<String,String>values){}
}
