package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.CaseRepository;
import com.hubertstudios.coredsc.storage.CaseRepository.Appeal;
import com.hubertstudios.coredsc.storage.CaseRepository.ModerationCase;
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
import org.bukkit.Bukkit;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Moderation cases and appeal workflow shared between Minecraft and Discord. */
public final class CaseModule implements CoreModule, DiscordCommandContributor {
    private final CoreDSCPlugin plugin;
    private CaseRepository cases;
    private LinkedAccountRepository links;
    private SupportMessageRepository messages;
    private ListenerAdapter listener;
    private Listener bukkitListener;
    private long appealChannelId;
    private boolean privateThreads;
    private int appealMinLength;
    private int appealMaxLength;
    private List<Long> staffRoleIds = List.of();

    public CaseModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "cases"; }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) throw new IllegalStateException("SQLite storage is not ready");
        cases = new CaseRepository(storage);
        links = new LinkedAccountRepository(storage);
        messages = new SupportMessageRepository(storage);
        FileConfiguration c = plugin.getAppConfig();
        appealChannelId = readOptionalSnowflake(c, "cases.appeals.parent-channel-id");
        privateThreads = c.getBoolean("cases.appeals.private-thread", true);
        appealMinLength = (int) clamp(c.getLong("cases.appeals.message-min-length", 10L), 1L, 500L);
        appealMaxLength = (int) clamp(c.getLong("cases.appeals.message-max-length", 1500L), appealMinLength, 2000L);
        staffRoleIds = readSnowflakeList(c.getList("cases.staff-role-ids"));
        registerMinecraftCommands();
        bukkitListener = new Listener() {
            @EventHandler public void onJoin(PlayerJoinEvent event) { deliverOffline(event.getPlayer()); }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);
        listener = new ListenerAdapter() {
            @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                switch (event.getName()) {
                    case "case" -> handleDiscordCase(event);
                    case "appeal" -> handleDiscordAppeal(event);
                    default -> { }
                }
            }
            @Override public void onButtonInteraction(ButtonInteractionEvent event) { handleAppealButton(event); }
        };
        if (plugin.getDiscordService() != null) plugin.getDiscordService().addEventListener(listener);
    }

    @Override
    public void disable() {
        if (listener != null && plugin.getDiscordService() != null) plugin.getDiscordService().removeEventListener(listener);
        listener = null;
        if (bukkitListener != null) HandlerList.unregisterAll(bukkitListener);
        bukkitListener = null;
        PluginCommand caseCommand = plugin.getCommand("case");
        if (caseCommand != null) caseCommand.setExecutor((sender, command, label, args) -> {
            sender.sendMessage("§cModeration cases are disabled."); return true;
        });
        PluginCommand appealCommand = plugin.getCommand("appeal");
        if (appealCommand != null) appealCommand.setExecutor((sender, command, label, args) -> {
            sender.sendMessage("§cAppeals are disabled."); return true;
        });
    }

    @Override public String statusDetail() { return "moderation cases and appeals"; }

    @Override
    public List<CommandData> slashCommands() {
        CommandData caseCommand = Commands.slash("case", "Inspect moderation cases").addSubcommands(
                new SubcommandData("view", "View a case").addOption(OptionType.INTEGER, "id", "Case ID", true),
                new SubcommandData("player", "List cases for a player").addOption(OptionType.STRING, "player", "Minecraft name or UUID", true),
                new SubcommandData("close", "Close a case").addOption(OptionType.INTEGER, "id", "Case ID", true)
        );
        CommandData appealCommand = Commands.slash("appeal", "Create and manage case appeals").addSubcommands(
                new SubcommandData("create", "Appeal a moderation case").addOptions(
                        new OptionData(OptionType.INTEGER, "case", "Case ID", true),
                        new OptionData(OptionType.STRING, "message", "Your appeal", true).setMaxLength(appealMaxLength)),
                new SubcommandData("status", "Show an appeal").addOption(OptionType.INTEGER, "id", "Appeal ID", true),
                new SubcommandData("accept", "Accept an appeal").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Appeal ID", true),
                        new OptionData(OptionType.STRING, "note", "Decision note", false).setMaxLength(500)),
                new SubcommandData("reject", "Reject an appeal").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Appeal ID", true),
                        new OptionData(OptionType.STRING, "note", "Decision note", false).setMaxLength(500))
        );
        return List.of(caseCommand, appealCommand);
    }

    public CompletableFuture<Long> recordModerationAction(
            String action, String targetUuid, String targetName, String executor,
            String reason, String duration, String source, String externalId, boolean confirmed
    ) {
        String status = confirmed ? "ACTIVE" : "OBSERVED";
        return cases.createCase(action, targetUuid, targetName, executor, reason, duration, source, externalId,
                System.currentTimeMillis()).thenCompose(id -> status.equals("ACTIVE")
                ? CompletableFuture.completedFuture(id)
                : cases.updateCaseStatus(id, status, System.currentTimeMillis()).thenApply(ignored -> id));
    }

    private void registerMinecraftCommands() {
        PluginCommand caseCommand = plugin.getCommand("case");
        PluginCommand appealCommand = plugin.getCommand("appeal");
        if (caseCommand == null || appealCommand == null) throw new IllegalStateException("case/appeal commands missing from plugin.yml");
        caseCommand.setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("coredsc.case.view")) { sender.sendMessage("§cNo permission."); return true; }
            if (args.length < 2) { sender.sendMessage("§e/case view <id> | /case player <name> | /case close <id>"); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "view" -> { Long id=parseId(args[1]); if(id==null){sender.sendMessage("§cInvalid ID.");break;} cases.findCase(id).whenComplete((found,error)->plugin.runSync(()->sender.sendMessage(error!=null||found.isEmpty()?"§cCase not found.":formatCase(found.get())))); }
                case "player" -> cases.findCasesForTarget(args[1], 10).whenComplete((list,error)->plugin.runSync(()->{if(error!=null){sender.sendMessage("§cLookup failed.");return;}if(list.isEmpty()){sender.sendMessage("§7No cases found.");return;}sender.sendMessage("§bCases for "+args[1]+":");for(ModerationCase c:list)sender.sendMessage("§7- §f#"+c.id()+" §c"+c.action()+" §8["+c.status()+"] §7"+c.reason());}));
                case "close" -> { if(!sender.hasPermission("coredsc.case.manage")){sender.sendMessage("§cNo permission.");break;}Long id=parseId(args[1]);if(id==null){sender.sendMessage("§cInvalid ID.");break;}cases.updateCaseStatus(id,"CLOSED",System.currentTimeMillis()).whenComplete((ok,error)->plugin.runSync(()->sender.sendMessage(error==null&&ok?"§aCase closed.":"§cCase not found."))); }
                default -> sender.sendMessage("§e/case view <id> | /case player <name> | /case close <id>");
            }
            return true;
        });
        appealCommand.setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cOnly players can appeal."); return true; }
            if (args.length == 0) { player.sendMessage("§e/appeal create <case-id> <message> | /appeal status [id]"); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if(args.length<3){player.sendMessage("§e/appeal create <case-id> <message>");break;}Long caseId=parseId(args[1]);if(caseId==null){player.sendMessage("§cInvalid case ID.");break;}
                    createAppeal(player.getUniqueId(),caseId,join(args,2)).whenComplete((result,error)->plugin.runSync(()->player.sendMessage(error==null?"§aAppeal #"+result+" created.":"§c"+rootMessage(error))));
                }
                case "status" -> {
                    if(args.length>=2){Long id=parseId(args[1]);if(id==null){player.sendMessage("§cInvalid appeal ID.");break;}cases.findAppeal(id).whenComplete((found,error)->plugin.runSync(()->{
                        if(error!=null||found.isEmpty()||!found.get().minecraftUuid().equals(player.getUniqueId().toString())) player.sendMessage("§cAppeal not found.");
                        else player.sendMessage(formatAppeal(found.get()));
                    }));}
                    else cases.findAppealsForUser(player.getUniqueId().toString()).whenComplete((list,error)->plugin.runSync(()->{if(error!=null){player.sendMessage("§cLookup failed.");return;}if(list.isEmpty()){player.sendMessage("§7No appeals.");return;}for(Appeal a:list)player.sendMessage("§7Appeal §f#"+a.id()+" §7for case #"+a.caseId()+" §8["+a.status()+"]");}));
                }
                default -> player.sendMessage("§e/appeal create <case-id> <message> | /appeal status [id]");
            }
            return true;
        });
    }

    private CompletableFuture<Long> createAppeal(UUID uuid, long caseId, String message) {
        String clean = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(message).trim(), appealMaxLength);
        if (clean.length() < appealMinLength) {
            return CompletableFuture.failedFuture(new IllegalStateException("Appeal message is too short."));
        }
        return plugin.callSync(() -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return name == null ? "" : name;
        }).thenCompose(playerName -> cases.findCase(caseId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Case not found."));
            }
            ModerationCase moderationCase = found.get();
            if (!moderationCase.targetUuid().isBlank()) {
                if (!moderationCase.targetUuid().equalsIgnoreCase(uuid.toString())) {
                    return CompletableFuture.failedFuture(new IllegalStateException("This case does not belong to you."));
                }
            } else if (moderationCase.targetName().isBlank() || playerName.isBlank()
                    || !moderationCase.targetName().equalsIgnoreCase(playerName)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "This case has no verifiable Minecraft identity for your account."));
            }
            return links.findByMinecraftUuid(uuid.toString()).thenCompose(link -> {
                String discordId = link.map(LinkedAccountRepository.LinkedAccount::discordUserId).orElse("");
                return cases.createAppeal(caseId, uuid.toString(), discordId, clean, System.currentTimeMillis())
                        .thenCompose(id -> createAppealThread(id, moderationCase, uuid, discordId, clean)
                                .thenCompose(channel -> cases.setAppealChannel(id, channel)
                                        .thenApply(ignored -> id)
                                        .exceptionallyCompose(error -> deleteThread(channel)
                                                .handle((ignored, deleteError) -> null)
                                                .thenCompose(ignored -> CompletableFuture.failedFuture(error))))
                                .exceptionallyCompose(error -> cases.deletePendingAppeal(id)
                                        .thenCompose(ignored -> CompletableFuture.failedFuture(error))));
            });
        }));
    }

    private CompletableFuture<String> createAppealThread(
            long appealId,
            ModerationCase moderationCase,
            UUID uuid,
            String discordId,
            String message
    ) {
        if (appealChannelId <= 0) {
            return CompletableFuture.completedFuture("");
        }
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        TextChannel parent = jda == null ? null : jda.getTextChannelById(appealChannelId);
        if (parent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Appeal channel unavailable"));
        }
        String text = "**Appeal #" + appealId + "** for case **#" + moderationCase.id() + "**\n"
                + "Player: `" + uuid + "`" + (discordId.isBlank() ? "" : " (<@" + discordId + ">)") + "\n"
                + "Action: `" + moderationCase.action() + "`\n"
                + "Reason: " + TextUtil.sanitizeMassMentions(moderationCase.reason()) + "\n\n"
                + TextUtil.sanitizeMassMentions(message);
        return parent.createThreadChannel(
                        "appeal-" + appealId + "-case-" + moderationCase.id(), privateThreads)
                .submit()
                .thenCompose(thread -> {
                    CompletableFuture<?> membership = discordId.isBlank()
                            ? CompletableFuture.completedFuture(null)
                            : parent.getGuild().retrieveMemberById(discordId).submit()
                                    .thenCompose(member -> thread.addThreadMember(member).submit());
                    if (!privateThreads) {
                        membership = membership.handle((ignored, error) -> null);
                    }
                    return membership.thenCompose(ignored -> thread.sendMessage(TextUtil.truncate(text, 2000))
                                    .setAllowedMentions(java.util.Collections.emptyList())
                                    .setComponents(ActionRow.of(
                                            Button.success("coredsc:appeal:accept:" + appealId, "Accept"),
                                            Button.danger("coredsc:appeal:reject:" + appealId, "Reject")))
                                    .submit())
                            .thenApply(ignored -> thread.getId())
                            .exceptionallyCompose(error -> deleteThread(thread.getId())
                                    .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
                });
    }


    private void handleAppealButton(ButtonInteractionEvent event) {
        String component = event.getComponentId();
        if (!component.startsWith("coredsc:appeal:")) return;
        if (!hasStaff(event.getMember())) { event.reply("Staff role required.").setEphemeral(true).queue(); return; }
        String[] parts = component.split(":");
        if (parts.length != 4) { event.reply("Invalid appeal action.").setEphemeral(true).queue(); return; }
        Long id = parseId(parts[3]);
        if (id == null) { event.reply("Invalid appeal ID.").setEphemeral(true).queue(); return; }
        String status = parts[2].equals("accept") ? "ACCEPTED" : "REJECTED";
        event.deferReply(true).queue(hook -> decideAppeal(id,status,event.getUser().getEffectiveName(),
                "Decided with Discord button").whenComplete((ok,error) -> {
            edit(hook,error==null&&ok?"Appeal "+status.toLowerCase(Locale.ROOT)+".":"Appeal not found or already decided.");
        }));
    }

    private void handleDiscordCase(SlashCommandInteractionEvent event) {
        if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}
        switch(event.getSubcommandName()==null?"":event.getSubcommandName()){
            case "view"->{long id=event.getOption("id").getAsLong();event.deferReply(true).queue(h->cases.findCase(id).whenComplete((found,error)->edit(h,error!=null||found.isEmpty()?"Case not found.":stripColors(formatCase(found.get())))));}
            case "player"->{String target=event.getOption("player").getAsString();event.deferReply(true).queue(h->cases.findCasesForTarget(target,10).whenComplete((list,error)->{if(error!=null){edit(h,"Lookup failed.");return;}StringBuilder b=new StringBuilder();for(ModerationCase c:list)b.append("#").append(c.id()).append(" ").append(c.action()).append(" [").append(c.status()).append("] ").append(c.reason()).append('\n');edit(h,b.length()==0?"No cases found.":b.toString());}));}
            case "close"->{long id=event.getOption("id").getAsLong();event.deferReply(true).queue(h->cases.updateCaseStatus(id,"CLOSED",System.currentTimeMillis()).whenComplete((ok,error)->edit(h,error==null&&ok?"Case closed.":"Case not found.")));}
            default->event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleDiscordAppeal(SlashCommandInteractionEvent event) {
        String sub=event.getSubcommandName()==null?"":event.getSubcommandName();
        if(sub.equals("create")){long caseId=event.getOption("case").getAsLong();String message=event.getOption("message").getAsString();event.deferReply(true).queue(h->links.findByDiscordUserId(event.getUser().getId()).thenCompose(link->link.isEmpty()?CompletableFuture.failedFuture(new IllegalStateException("Link your Minecraft account first.")):createAppeal(UUID.fromString(link.get().minecraftUuid()),caseId,message)).whenComplete((id,error)->edit(h,error==null?"Appeal #"+id+" created.":rootMessage(error))));return;}
        if(sub.equals("status")){
            long id=event.getOption("id").getAsLong();
            event.deferReply(true).queue(h->cases.findAppeal(id).thenCompose(found->{
                if(found.isEmpty())return CompletableFuture.completedFuture("Appeal not found.");
                if(hasStaff(event.getMember()))return CompletableFuture.completedFuture(stripColors(formatAppeal(found.get())));
                return links.findByDiscordUserId(event.getUser().getId()).thenApply(link->
                        link.isPresent()&&found.get().minecraftUuid().equals(link.get().minecraftUuid())
                                ?stripColors(formatAppeal(found.get())):"Appeal not found.");
            }).whenComplete((message,error)->edit(h,error==null?message:"Appeal lookup failed.")));
            return;
        }
        if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}
        long id=event.getOption("id").getAsLong();String note=event.getOption("note")==null?"":event.getOption("note").getAsString();String status=sub.equals("accept")?"ACCEPTED":"REJECTED";
        event.deferReply(true).queue(h->decideAppeal(id,status,event.getUser().getEffectiveName(),note).whenComplete((ok,error)->edit(h,error==null&&ok?"Appeal "+status.toLowerCase(Locale.ROOT)+".":"Appeal not found or already decided.")));
    }

    private CompletableFuture<Boolean> decideAppeal(long id,String status,String by,String note){
        return cases.findAppeal(id).thenCompose(found->{
            if(found.isEmpty())return CompletableFuture.completedFuture(false);
            Appeal appeal=found.get();
            return cases.decideAppeal(id,status,by,note,System.currentTimeMillis()).thenCompose(ok->{
                if(!ok)return CompletableFuture.completedFuture(false);
                return notifyAppealDecision(appeal,status,by,note).handle((ignored,error)->{
                    if(error!=null)plugin.getLogger().warning("[Cases] Could not notify appeal #"+id+": "+rootMessage(error));
                    return null;
                }).thenCompose(ignored->announceAppealDecision(appeal,status,by,note)
                        .exceptionally(error->{
                            plugin.getLogger().warning("[Cases] Could not announce appeal #"+id+": "+rootMessage(error));
                            archiveThread(appeal.channelId());
                            return null;
                        })).thenApply(ignored->true);
            });
        });
    }

    private CompletableFuture<Void> notifyAppealDecision(Appeal appeal,String status,String by,String note){
        String text="Appeal #"+appeal.id()+" was "+status.toLowerCase(Locale.ROOT)+" by "+
                TextUtil.sanitizeMinecraftUserText(by)+(note==null||note.isBlank()?"":". Note: "+TextUtil.sanitizeMinecraftUserText(note));
        return plugin.callSync(()->Bukkit.getPlayer(UUID.fromString(appeal.minecraftUuid()))).thenCompose(online->
                messages.add("APPEAL",appeal.id(),"SYSTEM","","CoreDSC",text,System.currentTimeMillis(),online!=null,true)
                        .thenAccept(messageId->{if(online!=null)plugin.runSync(()->online.sendMessage("§b[Appeal #"+appeal.id()+"] §f"+text));}));
    }


    private CompletableFuture<Void> announceAppealDecision(Appeal appeal,String status,String by,String note){
        if(appeal.channelId()==null||appeal.channelId().isBlank())return CompletableFuture.completedFuture(null);
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(appeal.channelId());
        if(thread==null)return CompletableFuture.failedFuture(new IllegalStateException("Appeal thread unavailable"));
        String message="**Appeal "+status.toLowerCase(Locale.ROOT)+" by "+
                TextUtil.sanitizeMassMentions(TextUtil.sanitizeMinecraftUserText(by))+".**"+
                (note==null||note.isBlank()?"":"\n"+TextUtil.sanitizeMassMentions(TextUtil.sanitizeMinecraftUserText(note)));
        return thread.sendMessage(TextUtil.truncate(message,2000))
                .setAllowedMentions(java.util.Collections.emptyList()).submit()
                .thenCompose(ignored->thread.getManager().setLocked(true).setArchived(true).submit())
                .thenApply(ignored->null);
    }

    private void archiveThread(String channelId){
        if(channelId==null||channelId.isBlank())return;
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(channelId);
        if(thread!=null)thread.getManager().setLocked(true).setArchived(true).queue(ignored->{},error->{});
    }

    private CompletableFuture<Void> deleteThread(String channelId){
        if(channelId==null||channelId.isBlank())return CompletableFuture.completedFuture(null);
        JDA jda=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();
        ThreadChannel thread=jda==null?null:jda.getThreadChannelById(channelId);
        if(thread==null)return CompletableFuture.completedFuture(null);
        return thread.delete().submit().thenApply(ignored->null);
    }

    private void deliverOffline(Player player){
        messages.pendingForMinecraft(player.getUniqueId().toString(),"APPEAL",50).whenComplete((pending,error)->{
            if(error!=null||pending.isEmpty())return;
            List<SupportMessage> own=pending.stream().filter(message->message.itemType().equals("APPEAL")).toList();
            if(own.isEmpty())return;
            plugin.runSync(()->{List<Long>ids=new ArrayList<>();for(SupportMessage message:own){player.sendMessage("§b[Appeal #"+message.itemId()+"] §f"+message.message());ids.add(message.id());}messages.markMinecraftDelivered(ids);});
        });
    }

    private boolean hasStaff(Member member){if(member==null)return false;if(member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER))return true;for(Role role:member.getRoles())if(staffRoleIds.contains(role.getIdLong()))return true;return false;}
    private void edit(InteractionHook hook,String message){hook.editOriginal(TextUtil.truncate(TextUtil.sanitizeMassMentions(message),2000)).setAllowedMentions(java.util.Collections.emptyList()).queue();}
    private static String formatCase(ModerationCase c){return "§bCase #"+c.id()+" §8["+c.status()+"]\n§7Action: §f"+c.action()+"\n§7Target: §f"+c.targetName()+"\n§7Executor: §f"+c.executor()+"\n§7Reason: §f"+c.reason()+"\n§7Duration: §f"+(c.duration().isBlank()?"permanent/unspecified":c.duration())+"\n§7Source: §f"+c.source();}
    private static String formatAppeal(Appeal a){return "§bAppeal #"+a.id()+" §8["+a.status()+"]\n§7Case: §f#"+a.caseId()+"\n§7Message: §f"+a.message()+"\n§7Decision: §f"+a.decisionNote();}
    private static String stripColors(String s){return s.replaceAll("§[0-9A-FK-ORa-fk-or]","");}
    private static String join(String[] args,int start){return String.join(" ",Arrays.copyOfRange(args,start,args.length));}
    private static Long parseId(String v){try{long id=Long.parseLong(v);return id>0?id:null;}catch(Exception e){return null;}}
    private static long readOptionalSnowflake(FileConfiguration c,String path){Object raw=c.get(path);if(raw==null||raw.toString().isBlank())return 0L;try{long id=Long.parseLong(raw.toString());return Math.max(0,id);}catch(Exception e){throw new IllegalArgumentException(path+" must be a Discord ID",e);}}
    private static List<Long> readSnowflakeList(List<?> raw){if(raw==null)return List.of();List<Long> out=new ArrayList<>();for(Object x:raw){try{long id=Long.parseLong(x.toString());if(id>0)out.add(id);}catch(Exception ignored){}}return List.copyOf(out);}
    private static long clamp(long v,long min,long max){return Math.max(min,Math.min(max,v));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
