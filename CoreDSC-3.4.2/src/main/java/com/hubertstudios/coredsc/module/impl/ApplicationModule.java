package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.scheduler.CommandReplyTarget;
import com.hubertstudios.coredsc.storage.ApplicationRepository;
import com.hubertstudios.coredsc.storage.ApplicationRepository.Application;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.storage.SupportMessageRepository;
import com.hubertstudios.coredsc.storage.SupportMessageRepository.SupportMessage;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Configurable linked-account whitelist/application questionnaire. */
public final class ApplicationModule implements CoreModule, DiscordCommandContributor {
    private final CoreDSCPlugin plugin;
    private ApplicationRepository applications;
    private LinkedAccountRepository links;
    private SupportMessageRepository messages;
    private ListenerAdapter listener;
    private Listener bukkitListener;
    private List<Question> questions = List.of();
    private List<Long> staffRoleIds = List.of();
    private long parentChannelId;
    private boolean privateThreads;
    private boolean requireLinked;
    private boolean whitelistOnAccept;
    private String luckPermsGroup;
    private long discordRoleId;
    private long guildId;
    private List<String> acceptCommands = List.of();
    private List<String> rejectCommands = List.of();
    private boolean allowConsoleActions;
    private List<String> consoleAllowPrefixes = List.of();

    public ApplicationModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "applications"; }

    @Override
    public void enable() {
        SQLiteStorage storage=plugin.getStorage();if(storage==null||storage.getState()!=SQLiteStorage.State.READY)throw new IllegalStateException("SQLite storage is not ready");
        applications=new ApplicationRepository(storage);links=new LinkedAccountRepository(storage);messages=new SupportMessageRepository(storage);
        FileConfiguration c=plugin.getAppConfig();
        questions=loadQuestions(c.getMapList("applications.questions"));
        if(questions.isEmpty())throw new IllegalArgumentException("applications.questions must contain at least one question");
        parentChannelId=readRequiredSnowflake(c,"applications.parent-channel-id");privateThreads=c.getBoolean("applications.private-thread",true);
        requireLinked=c.getBoolean("applications.require-linked-account",true);staffRoleIds=readSnowflakeList(c.getList("applications.staff-role-ids"));
        whitelistOnAccept=c.getBoolean("applications.accept-actions.whitelist",true);
        luckPermsGroup=value(c,"applications.accept-actions.luckperms-group","");discordRoleId=readOptionalSnowflake(c,"applications.accept-actions.discord-role-id");
        guildId=readOptionalSnowflake(c,"discord.guild-id");
        acceptCommands=List.copyOf(c.getStringList("applications.accept-actions.console-commands"));rejectCommands=List.copyOf(c.getStringList("applications.reject-actions.console-commands"));
        allowConsoleActions=c.getBoolean("applications.allow-console-actions",false);
        consoleAllowPrefixes=c.getStringList("applications.console-command-allow-prefixes").stream()
                .map(v->v.trim().toLowerCase(Locale.ROOT)).filter(v->!v.isBlank()).toList();
        registerMinecraftCommands();
        bukkitListener=new Listener(){@EventHandler public void onJoin(PlayerJoinEvent event){deliverOffline(event.getPlayer().getUniqueId());}};
        plugin.getServer().getPluginManager().registerEvents(bukkitListener,plugin);
        listener=new ListenerAdapter(){
            @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent e){if(e.getName().equals("apply"))handleApply(e);else if(e.getName().equals("application"))handleStaff(e);}
            @Override public void onButtonInteraction(ButtonInteractionEvent e){handleButton(e);}
        };
        if(plugin.getDiscordService()!=null)plugin.getDiscordService().addEventListener(listener);
    }

    @Override public void disable(){
        if(listener!=null&&plugin.getDiscordService()!=null)plugin.getDiscordService().removeEventListener(listener);
        listener=null;
        if(bukkitListener!=null)HandlerList.unregisterAll(bukkitListener);
        bukkitListener=null;
        PluginCommand apply=plugin.getCommand("apply");
        if(apply!=null){apply.setExecutor((sender,cmd,label,args)->{sender.sendMessage("§cApplications are disabled.");return true;});apply.setTabCompleter(null);}
        PluginCommand staff=plugin.getCommand("application");
        if(staff!=null)staff.setExecutor((sender,cmd,label,args)->{sender.sendMessage("§cApplications are disabled.");return true;});
    }
    @Override public String statusDetail(){return questions.size()+" application question(s)";}

    @Override public List<CommandData> slashCommands(){return List.of(
            Commands.slash("apply","Manage your Minecraft application").addSubcommands(
                    new SubcommandData("start","Start or resume an application"),
                    new SubcommandData("status","Show your current application")),
            Commands.slash("application","Staff application management").addSubcommands(
                    new SubcommandData("view","View an application").addOption(OptionType.INTEGER,"id","Application ID",true),
                    new SubcommandData("accept","Accept an application").addOptions(new OptionData(OptionType.INTEGER,"id","Application ID",true),new OptionData(OptionType.STRING,"note","Decision note",false).setMaxLength(500)),
                    new SubcommandData("reject","Reject an application").addOptions(new OptionData(OptionType.INTEGER,"id","Application ID",true),new OptionData(OptionType.STRING,"note","Decision note",false).setMaxLength(500)))
    );}

    private void registerMinecraftCommands(){
        PluginCommand apply=plugin.getCommand("apply"),staff=plugin.getCommand("application");if(apply==null||staff==null)throw new IllegalStateException("apply/application commands missing from plugin.yml");
        apply.setExecutor((sender,cmd,label,args)->{if(!(sender instanceof Player p)){sender.sendMessage("§cOnly players can apply.");return true;}if(args.length==0){help(p);return true;}switch(args[0].toLowerCase(Locale.ROOT)){
            case "start"->start(p);
            case "questions"->showQuestions(p);
            case "answer"->{if(args.length<3){p.sendMessage("§e/apply answer <question-id> <answer>");break;}answer(p,args[1],join(args,2));}
            case "submit"->submit(p);
            case "status"->status(p);
            default->help(p);
        }return true;});
        apply.setTabCompleter((s,c,a,args)->args.length==1?List.of("start","questions","answer","submit","status").stream().filter(v->v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList():args.length==2&&args[0].equalsIgnoreCase("answer")?questions.stream().map(Question::id).filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList():List.of());
        staff.setExecutor((sender,cmd,label,args)->{if(!sender.hasPermission("coredsc.application.manage")){sender.sendMessage("§cNo permission.");return true;}if(args.length<2){sender.sendMessage("§e/application <view|accept|reject> <id> [note]");return true;}Long id=parseId(args[1]);if(id==null){sender.sendMessage("§cInvalid ID.");return true;}CommandReplyTarget replyTarget=CommandReplyTarget.capture(plugin,sender);switch(args[0].toLowerCase(Locale.ROOT)){
            case "view"->applications.find(id).thenCombine(applications.answers(id),(app,answers)->app.map(a->format(a,answers)).orElse("Application not found.")).whenComplete((text,error)->replyTarget.send(error==null?text:"§cLookup failed."));
            case "accept","reject"->{boolean accept=args[0].equalsIgnoreCase("accept");String note=args.length>2?join(args,2):"";String actor=sender.getName();decide(id,accept,actor,note).whenComplete((ok,error)->replyTarget.send(error==null&&ok?"§aApplication decided.":"§cApplication not found or already decided."));}
            default->sender.sendMessage("§e/application <view|accept|reject> <id> [note]");
        }return true;});
    }

    private void start(Player p){UUID playerId=p.getUniqueId();String playerUuid=playerId.toString();String playerName=p.getName();links.findByMinecraftUuid(playerUuid).thenCompose(link->{if(requireLinked&&link.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("Link your Discord account first."));return applications.start(playerUuid,playerName,link.map(LinkedAccountRepository.LinkedAccount::discordUserId).orElse(""),System.currentTimeMillis());}).whenComplete((id,error)->plugin.runForPlayer(playerId,currentPlayer->{if(error!=null){currentPlayer.sendMessage("§c"+rootMessage(error));return;}currentPlayer.sendMessage("§aApplication #"+id+" started. Use §e/apply questions§a and §e/apply answer <id> <answer>§a.");}));}
    private void showQuestions(Player p){p.sendMessage("§bApplication questions:");for(Question q:questions)p.sendMessage("§e"+q.id()+"§7: §f"+q.text());}
    private void answer(Player p,String questionId,String answer){UUID playerId=p.getUniqueId();Question q=questions.stream().filter(x->x.id().equalsIgnoreCase(questionId)).findFirst().orElse(null);if(q==null){p.sendMessage("§cUnknown question ID.");return;}String clean=TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(answer).trim(),q.maxLength());if(clean.length()<q.minLength()){p.sendMessage("§cAnswer is too short.");return;}applications.activeForUser(playerId.toString()).thenCompose(app->app.isEmpty()?CompletableFuture.failedFuture(new IllegalStateException("Start an application first.")):applications.answer(app.get().id(),q.id(),clean,System.currentTimeMillis())).whenComplete((v,e)->plugin.runForPlayer(playerId,currentPlayer->currentPlayer.sendMessage(e==null?"§aAnswer saved.":"§c"+rootMessage(e))));}
    private void submit(Player p){UUID playerId=p.getUniqueId();applications.activeForUser(playerId.toString()).thenCompose(app->{
        if(app.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("Start an application first."));
        Application a=app.get();
        return applications.answers(a.id()).thenCompose(answers->{
            List<String>missing=questions.stream().filter(Question::required).map(Question::id)
                    .filter(id->!answers.containsKey(id)||answers.get(id).isBlank()).toList();
            if(!missing.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("Missing answers: "+String.join(", ",missing)));
            return applications.submit(a.id(),System.currentTimeMillis()).thenCompose(ok->{
                if(!ok)return CompletableFuture.failedFuture(new IllegalStateException("Application is already submitted."));
                return createThread(a,answers).thenCompose(channel->applications.setChannel(a.id(),channel)
                        .thenApply(ignored->{plugin.recordFeatureUse("application_created");return channel;})
                        .exceptionallyCompose(error->deleteThread(channel)
                                .handle((ignored,deleteError)->null)
                                .thenCompose(ignored->CompletableFuture.failedFuture(error))))
                        .exceptionallyCompose(error->applications.revertSubmission(a.id())
                                .thenCompose(ignored->CompletableFuture.failedFuture(error)));
            });
        });
    }).whenComplete((v,e)->plugin.runForPlayer(playerId,currentPlayer->currentPlayer.sendMessage(e==null?"§aApplication submitted.":"§c"+rootMessage(e))));}
    private void status(Player p){UUID playerId=p.getUniqueId();applications.activeForUser(playerId.toString()).whenComplete((app,e)->plugin.runForPlayer(playerId,currentPlayer->currentPlayer.sendMessage(e!=null?"§cLookup failed.":app.isEmpty()?"§7No active application.":"§bApplication #"+app.get().id()+" §8["+app.get().status()+"]")));}

    private CompletableFuture<String> createThread(Application application, Map<String, String> answers) {
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        TextChannel parent = jda == null ? null : jda.getTextChannelById(parentChannelId);
        if (parent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Application channel unavailable"));
        }
        StringBuilder text = new StringBuilder("**Application #")
                .append(application.id()).append("**\nMinecraft: `")
                .append(application.minecraftName()).append("` (`")
                .append(application.minecraftUuid()).append("`)");
        if (!application.discordUserId().isBlank()) {
            text.append("\nDiscord: <@").append(application.discordUserId()).append('>');
        }
        for (Question question : questions) {
            text.append("\n\n**").append(question.text()).append("**\n")
                    .append(TextUtil.sanitizeMassMentions(
                            answers.getOrDefault(question.id(), "Not answered")));
        }
        String threadName = TextUtil.truncate(
                "application-" + application.id() + "-"
                        + TextUtil.safeChannelToken(application.minecraftName()), 100);
        return parent.createThreadChannel(threadName, privateThreads).submit().thenCompose(thread -> {
            CompletableFuture<?> membership = application.discordUserId().isBlank()
                    ? CompletableFuture.completedFuture(null)
                    : parent.getGuild().retrieveMemberById(application.discordUserId()).submit()
                            .thenCompose(member -> thread.addThreadMember(member).submit());
            if (!privateThreads) {
                membership = membership.handle((ignored, error) -> null);
            }
            return membership.thenCompose(ignored -> thread.sendMessage(
                                    TextUtil.truncate(text.toString(), 2000))
                            .setAllowedMentions(java.util.Collections.emptyList())
                            .setComponents(ActionRow.of(
                                    Button.success("coredsc:application:accept:" + application.id(), "Accept"),
                                    Button.danger("coredsc:application:reject:" + application.id(), "Reject")))
                            .submit())
                    .thenApply(ignored -> thread.getId())
                    .exceptionallyCompose(error -> deleteThread(thread.getId())
                            .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
        });
    }

    private CompletableFuture<Boolean> decide(long id,boolean accept,String by,String note){
        String status=accept?"ACCEPTED":"REJECTED";
        return applications.find(id).thenCompose(found->{
            if(found.isEmpty())return CompletableFuture.completedFuture(false);
            Application a=found.get();
            return applications.decide(id,status,by,note,System.currentTimeMillis()).thenCompose(ok->{
                if(!ok)return CompletableFuture.completedFuture(false);
                CompletableFuture<Void> actions=plugin.callSync(()->{
                    OfflinePlayer player=Bukkit.getOfflinePlayer(UUID.fromString(a.minecraftUuid()));
                    if(accept&&whitelistOnAccept)player.setWhitelisted(true);
                    for(String template:accept?acceptCommands:rejectCommands){
                        String rendered=renderConsoleCommand(template,a);
                        runConsoleAction(rendered);
                    }
                    return null;
                });
                if(accept)actions=actions.thenCompose(v->applyExternalAcceptActions(a));
                return actions.handle((ignored,error)->{
                    if(error!=null)plugin.getLogger().warning("[Applications] Decision actions for #"+id+" failed: "+rootMessage(error));
                    return null;
                }).thenCompose(ignored->notifyDecision(a,status,by,note))
                        .thenCompose(ignored->announceDecision(a,status,by,note)
                                .exceptionally(error->{
                                    plugin.getLogger().warning("[Applications] Could not announce decision for #"+id+": "+rootMessage(error));
                                    archiveThread(a.channelId());
                                    return null;
                                }))
                        .thenApply(ignored->true);
            });
        });
    }


    private String renderConsoleCommand(String template,Application application){
        Map<String,String> safe=new java.util.LinkedHashMap<>();
        if(TextUtil.isSafeMinecraftName(application.minecraftName()))safe.put("player",application.minecraftName());
        if(TextUtil.isUuid(application.minecraftUuid()))safe.put("uuid",application.minecraftUuid());
        return TextUtil.renderRestrictedCommand(template,safe);
    }

    private void runConsoleAction(String command){
        String normalized=command.trim().toLowerCase(Locale.ROOT);
        if(!allowConsoleActions||consoleAllowPrefixes.stream().noneMatch(prefix->commandMatchesPrefix(normalized,prefix))){
            throw new IllegalStateException("Application console action is not allowlisted");
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command);
    }

    private static boolean commandMatchesPrefix(String command,String prefix){
        return command.equals(prefix)||command.startsWith(prefix+" ");
    }

    private CompletableFuture<Void> applyExternalAcceptActions(Application a){CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);if(!luckPermsGroup.isBlank())chain=chain.thenCompose(v->{try{var lp=LuckPermsProvider.get();return lp.getUserManager().loadUser(UUID.fromString(a.minecraftUuid())).thenCompose(user->{user.data().add(InheritanceNode.builder(luckPermsGroup).build());return lp.getUserManager().saveUser(user);});}catch(Exception e){return CompletableFuture.failedFuture(e);}});if(discordRoleId>0&&!a.discordUserId().isBlank())chain=chain.thenCompose(v->{JDA j=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();var guild=j==null?null:j.getGuildById(guildId);var role=guild==null?null:guild.getRoleById(discordRoleId);if(guild==null||role==null)return CompletableFuture.failedFuture(new IllegalStateException("Application role unavailable"));return guild.retrieveMemberById(a.discordUserId()).submit().thenCompose(member->guild.addRoleToMember(member,role).submit()).thenApply(x->null);});return chain;}


    private CompletableFuture<Void> notifyDecision(Application application,String status,String by,String note){
        String text="Application #"+application.id()+" was "+status.toLowerCase(Locale.ROOT)+" by "+
                TextUtil.sanitizeMinecraftUserText(by)+(note==null||note.isBlank()?"":". Note: "+TextUtil.sanitizeMinecraftUserText(note));
        UUID playerId=UUID.fromString(application.minecraftUuid());
        return messages.add("APPLICATION",application.id(),"SYSTEM","", "CoreDSC",text,
                        System.currentTimeMillis(),false,true)
                .thenCompose(messageId->plugin.runForPlayer(playerId,player->
                                player.sendMessage("§b[Application #"+application.id()+"] §f"+text))
                        .thenCompose(delivered->delivered
                                ?messages.markMinecraftDelivered(List.of(messageId))
                                :CompletableFuture.completedFuture(null)));
    }

    private void deliverOffline(UUID playerId){
        messages.pendingForMinecraft(playerId.toString(),"APPLICATION",50).whenComplete((pending,error)->{
            if(error!=null||pending.isEmpty())return;
            List<SupportMessage> own=pending.stream().filter(message->message.itemType().equals("APPLICATION")).toList();
            if(own.isEmpty())return;
            plugin.runForPlayer(playerId,currentPlayer->{List<Long>ids=new ArrayList<>();for(SupportMessage message:own){currentPlayer.sendMessage("§b[Application #"+message.itemId()+"] §f"+message.message());ids.add(message.id());}messages.markMinecraftDelivered(ids);});
        });
    }


    private CompletableFuture<Void> announceDecision(Application application,String status,String by,String note){
        if(application.channelId()==null||application.channelId().isBlank())return CompletableFuture.completedFuture(null);
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(application.channelId());
        if(thread==null)return CompletableFuture.failedFuture(new IllegalStateException("Application thread unavailable"));
        String message="**Application "+status.toLowerCase(Locale.ROOT)+" by "+
                TextUtil.sanitizeMassMentions(TextUtil.sanitizeMinecraftUserText(by))+".**"+
                (note==null||note.isBlank()?"":"\n"+TextUtil.sanitizeMassMentions(TextUtil.sanitizeMinecraftUserText(note)));
        return thread.sendMessage(TextUtil.truncate(message,2000))
                .setAllowedMentions(java.util.Collections.emptyList()).submit()
                .thenCompose(ignored->thread.getManager().setLocked(true).setArchived(true).submit())
                .thenApply(ignored->null);
    }

    private CompletableFuture<Void> deleteThread(String channelId){
        if(channelId==null||channelId.isBlank())return CompletableFuture.completedFuture(null);
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(channelId);
        if(thread==null)return CompletableFuture.completedFuture(null);
        return thread.delete().submit().thenApply(ignored->null);
    }

    private void archiveThread(String channelId){
        if(channelId==null||channelId.isBlank())return;
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(channelId);
        if(thread!=null)thread.getManager().setLocked(true).setArchived(true).queue(ignored->{},error->{});
    }


    private void handleButton(ButtonInteractionEvent event){
        String component=event.getComponentId();
        if(!component.startsWith("coredsc:application:"))return;
        if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}
        String[]parts=component.split(":");
        if(parts.length!=4){event.reply("Invalid application action.").setEphemeral(true).queue();return;}
        Long id=parseId(parts[3]);
        if(id==null){event.reply("Invalid application ID.").setEphemeral(true).queue();return;}
        boolean accept=parts[2].equals("accept");
        event.deferReply(true).queue(h->decide(id,accept,event.getUser().getEffectiveName(),"Decided with Discord button")
                .whenComplete((ok,error)->{
                    edit(h,error==null&&ok?"Application "+(accept?"accepted":"rejected")+".":"Application not found or already decided.");
                }));
    }

    private void handleApply(SlashCommandInteractionEvent e){String sub=e.getSubcommandName()==null?"":e.getSubcommandName();if(sub.equals("start")){e.deferReply(true).queue(h->links.findByDiscordUserId(e.getUser().getId()).thenCompose(link->link.isEmpty()?CompletableFuture.failedFuture(new IllegalStateException("Link your Minecraft account first.")):applications.start(link.get().minecraftUuid(),link.get().minecraftName(),e.getUser().getId(),System.currentTimeMillis())).whenComplete((id,error)->edit(h,error==null?"Application #"+id+" started. Answer it in Minecraft with /apply questions.":rootMessage(error))));}else if(sub.equals("status")){e.deferReply(true).queue(h->links.findByDiscordUserId(e.getUser().getId()).thenCompose(link->link.isEmpty()?CompletableFuture.completedFuture(java.util.Optional.<Application>empty()):applications.activeForUser(link.get().minecraftUuid())).whenComplete((app,error)->edit(h,error!=null?"Lookup failed.":app.isEmpty()?"No active application.":"Application #"+app.get().id()+" ["+app.get().status()+"]")));}}
    private void handleStaff(SlashCommandInteractionEvent e){if(!hasStaff(e.getMember())){e.reply("Staff role required.").setEphemeral(true).queue();return;}String sub=e.getSubcommandName()==null?"":e.getSubcommandName();long id=e.getOption("id").getAsLong();if(sub.equals("view")){e.deferReply(true).queue(h->applications.find(id).thenCombine(applications.answers(id),(app,answers)->app.map(a->strip(format(a,answers))).orElse("Application not found.")).whenComplete((text,error)->edit(h,error==null?text:"Lookup failed.")));return;}boolean accept=sub.equals("accept");String note=e.getOption("note")==null?"":e.getOption("note").getAsString();e.deferReply(true).queue(h->decide(id,accept,e.getUser().getEffectiveName(),note).whenComplete((ok,error)->edit(h,error==null&&ok?"Application "+(accept?"accepted":"rejected")+".":"Could not decide application.")));}

    private String format(Application a,Map<String,String>answers){StringBuilder b=new StringBuilder("§bApplication #").append(a.id()).append(" §8[").append(a.status()).append("]\n§7Player: §f").append(a.minecraftName());for(Question q:questions)b.append("\n§e").append(q.text()).append("§7: §f").append(answers.getOrDefault(q.id(),"Not answered"));return b.toString();}
    private boolean hasStaff(Member m){if(m==null)return false;if(m.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))return true;for(Role r:m.getRoles())if(staffRoleIds.contains(r.getIdLong()))return true;return false;}
    private List<Question> loadQuestions(List<Map<?,?>>raw){List<Question>out=new ArrayList<>();Set<String>ids=new HashSet<>();for(Map<?,?>m:raw){String id=text(m.get("id")).toLowerCase(Locale.ROOT),q=text(m.get("question"));if(id.isBlank()||q.isBlank()||!ids.add(id))throw new IllegalArgumentException("Application question requires a unique id and question");int min=(int)clamp(number(m.get("min-length"),1),0,500);int max=(int)clamp(number(m.get("max-length"),500),1,2000);if(min>max)throw new IllegalArgumentException("Application question "+id+" has min-length above max-length");out.add(new Question(id,q,bool(m.get("required"),true),min,max));}return List.copyOf(out);}
    private void edit(InteractionHook h,String m){h.editOriginal(TextUtil.truncate(TextUtil.sanitizeMassMentions(m),2000)).setAllowedMentions(java.util.Collections.emptyList()).queue();}
    private void help(Player p){p.sendMessage("§b/apply start");p.sendMessage("§b/apply questions");p.sendMessage("§b/apply answer <question-id> <answer>");p.sendMessage("§b/apply submit");p.sendMessage("§b/apply status");}
    private static String strip(String s){return s.replaceAll("§[0-9A-FK-ORa-fk-or]","");}
    private static String join(String[]a,int start){return String.join(" ",Arrays.copyOfRange(a,start,a.length));}
    private static Long parseId(String s){try{long id=Long.parseLong(s);return id>0?id:null;}catch(Exception e){return null;}}
    private static String value(FileConfiguration c,String path,String fallback){String v=c.getString(path,fallback);return v==null?fallback:v.trim();}
    private static long readRequiredSnowflake(FileConfiguration c,String path){long v=readOptionalSnowflake(c,path);if(v<=0)throw new IllegalArgumentException(path+" must be configured");return v;}
    private static long readOptionalSnowflake(FileConfiguration c,String path){Object r=c.get(path);if(r==null||r.toString().isBlank())return 0;try{long v=Long.parseLong(r.toString());return Math.max(0,v);}catch(Exception e){throw new IllegalArgumentException(path+" invalid",e);}}
    private static List<Long> readSnowflakeList(List<?>raw){if(raw==null)return List.of();List<Long>o=new ArrayList<>();for(Object x:raw)try{long v=Long.parseLong(x.toString());if(v>0)o.add(v);}catch(Exception ignored){}return List.copyOf(o);}
    private static String text(Object v){return v==null?"":v.toString().trim();}
    private static boolean bool(Object v,boolean f){return v==null?f:Boolean.parseBoolean(v.toString());}
    private static long number(Object v,long f){if(v instanceof Number n)return n.longValue();try{return Long.parseLong(text(v));}catch(Exception e){return f;}}
    private static long clamp(long v,long min,long max){return Math.max(min,Math.min(max,v));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record Question(String id,String text,boolean required,int minLength,int maxLength){}
}
