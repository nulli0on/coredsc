package com.hubertstudios.coredsc.voice;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;






public final class DiscordVoiceRelayPool {
    private static final int FRAME_SAMPLES = 960;

    @FunctionalInterface
    public interface DiscordAudioListener {
        void onDiscordPcm(UUID roomId, String discordUserId, short[] monoPcm);
    }

    private final CoreDSCPlugin plugin;
    private final long guildId;
    private final DiscordAudioListener listener;
    private final List<RelayBot> bots = new ArrayList<>();
    private final Map<UUID, RelayBot> assignments = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final int maxBufferedFrames;
    private final int maxMixedSourcesPerFrame;
    private final double minecraftGain;
    private final double discordGain;
    private final boolean minecraftToDiscord;
    private final boolean discordToMinecraft;

    public DiscordVoiceRelayPool(
            CoreDSCPlugin plugin,
            long guildId,
            Collection<String> tokens,
            boolean minecraftToDiscord,
            boolean discordToMinecraft,
            double minecraftGain,
            double discordGain,
            int maxBufferedFrames,
            int maxMixedSourcesPerFrame,
            DiscordAudioListener listener
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.guildId = guildId;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.minecraftToDiscord = minecraftToDiscord;
        this.discordToMinecraft = discordToMinecraft;
        this.minecraftGain = minecraftGain;
        this.discordGain = discordGain;
        this.maxBufferedFrames = maxBufferedFrames;
        this.maxMixedSourcesPerFrame = maxMixedSourcesPerFrame;
        int index = 1;
        Set<String> uniqueTokens = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                uniqueTokens.add(token.trim());
            }
        }
        for (String token : uniqueTokens) {
            bots.add(new RelayBot(index++, token));
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (RelayBot bot : bots) {
            bot.start();
        }
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        assignments.clear();
        for (RelayBot bot : bots) {
            bot.shutdown();
        }
    }

    public void reconnectAll() {
        if (!running.get()) {
            return;
        }
        for (RelayBot bot : bots) {
            bot.restart();
        }
    }

    




    public boolean assign(UUID roomId, long channelId) {
        if (!running.get() || roomId == null || channelId <= 0L) {
            return false;
        }
        RelayBot existing = assignments.get(roomId);
        if (existing != null) {
            existing.connect(roomId, channelId);
            return true;
        }
        synchronized (bots) {
            for (RelayBot bot : bots) {
                if (!bot.reserve(roomId, channelId)) {
                    continue;
                }
                assignments.put(roomId, bot);
                return true;
            }
        }
        return false;
    }

    public void release(UUID roomId) {
        RelayBot bot = assignments.remove(roomId);
        if (bot != null) {
            bot.release(roomId);
        }
    }

    public void enqueueMinecraftPcm(UUID roomId, UUID speakerId, short[] monoPcm) {
        RelayBot bot = assignments.get(roomId);
        if (bot != null) {
            bot.enqueue(speakerId, monoPcm);
        }
    }

    public boolean isAssigned(UUID roomId) {
        return roomId != null && assignments.containsKey(roomId);
    }

    public boolean isConnected(UUID roomId) {
        RelayBot bot = assignments.get(roomId);
        return bot != null && bot.isConnected();
    }

    public int configuredCount() {
        return bots.size();
    }

    
    public Set<String> relayUserIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (RelayBot bot : bots) {
            String id = bot.userId();
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    public int readyCount() {
        int count = 0;
        for (RelayBot bot : bots) {
            if (bot.isReady()) {
                count++;
            }
        }
        return count;
    }

    public int assignedCount() {
        return assignments.size();
    }

    public long queuedFrames() {
        long count = 0L;
        for (RelayBot bot : bots) {
            count += bot.queuedFrames.get();
        }
        return count;
    }

    public long droppedFrames() {
        long count = 0L;
        for (RelayBot bot : bots) {
            count += bot.droppedFrames.sum();
        }
        return count;
    }

    public String statusDetail() {
        if (bots.isEmpty()) {
            return "no relay bot tokens configured";
        }
        return "relay bots ready=" + readyCount() + '/' + configuredCount()
                + ", assigned=" + assignedCount()
                + ", queued=" + queuedFrames()
                + ", dropped=" + droppedFrames();
    }

    private final class RelayBot {
        private final int index;
        private final String token;
        private final AtomicBoolean starting = new AtomicBoolean();
        private final Map<UUID, ConcurrentLinkedQueue<short[]>> minecraftFrames = new ConcurrentHashMap<>();
        private final AtomicInteger queuedFrames = new AtomicInteger();
        private final AtomicInteger mixCursor = new AtomicInteger();
        private final AtomicInteger dropCursor = new AtomicInteger();
        private final LongAdder droppedFrames = new LongAdder();
        private final Object assignmentLock = new Object();

        private volatile JDA jda;
        private volatile AudioManager audioManager;
        private volatile UUID roomId;
        private volatile long channelId;
        private volatile String lastError = "";
        private volatile long nextStartAttemptAt;

        private final AudioSendHandler sendHandler = new AudioSendHandler() {
            @Override
            public boolean canProvide() {
                return running.get() && minecraftToDiscord && roomId != null
                        && queuedFrames.get() > 0;
            }

            @Override
            public ByteBuffer provide20MsAudio() {
                short[] mixed = mixNextFrame();
                return mixed == null ? null : ByteBuffer.wrap(monoToDiscordStereo(mixed));
            }
        };

        private final AudioReceiveHandler receiveHandler = new AudioReceiveHandler() {
            @Override
            public boolean canReceiveUser() {
                return running.get() && discordToMinecraft && roomId != null;
            }

            @Override
            public void handleUserAudio(UserAudio userAudio) {
                UUID currentRoom = roomId;
                if (currentRoom == null || userAudio.getUser().isBot()) {
                    return;
                }
                short[] mono = discordStereoToMono(userAudio.getAudioData(1.0));
                applyGain(mono, discordGain);
                listener.onDiscordPcm(currentRoom, userAudio.getUser().getId(), mono);
            }
        };

        private RelayBot(int index, String token) {
            this.index = index;
            this.token = token;
        }

        private void start() {
            if (!running.get() || System.currentTimeMillis() < nextStartAttemptAt
                    || !starting.compareAndSet(false, true)) {
                return;
            }
            startDaemon("CoreDSC-Voice-Relay-" + index, () -> {
                try {
                    JDABuilder builder = JDABuilder.createLight(
                            token, Set.of(GatewayIntent.GUILD_VOICE_STATES));
                    builder.enableCache(CacheFlag.VOICE_STATE);
                    builder.setMemberCachePolicy(MemberCachePolicy.VOICE);
                    JDA built = builder.build().awaitReady();
                    if (!running.get()) {
                        built.shutdownNow();
                        return;
                    }
                    jda = built;
                    lastError = "";
                    nextStartAttemptAt = 0L;
                    plugin.getLogger().info("Voice relay bot " + index + " connected as "
                            + built.getSelfUser().getName() + '.');
                    UUID reservedRoom = roomId;
                    long reservedChannel = channelId;
                    if (reservedRoom != null && reservedChannel > 0L) {
                        connect(reservedRoom, reservedChannel);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    lastError = "startup interrupted";
                    nextStartAttemptAt = System.currentTimeMillis() + 30_000L;
                } catch (Throwable error) {
                    lastError = rootMessage(error);
                    nextStartAttemptAt = System.currentTimeMillis() + 30_000L;
                    plugin.getLogger().warning("Voice relay bot " + index
                            + " failed to start: " + lastError);
                } finally {
                    starting.set(false);
                }
            });
        }

        private void restart() {
            clearAssignmentAudio(true);
            shutdownJda();
            nextStartAttemptAt = 0L;
            start();
        }

        private boolean reserve(UUID requestedRoom, long requestedChannel) {
            synchronized (assignmentLock) {
                if (roomId != null && !roomId.equals(requestedRoom)) {
                    return false;
                }
                roomId = requestedRoom;
                channelId = requestedChannel;
            }
            connect(requestedRoom, requestedChannel);
            return true;
        }

        private void connect(UUID requestedRoom, long requestedChannel) {
            if (!running.get() || !requestedRoom.equals(roomId)
                    || requestedChannel != channelId) {
                return;
            }
            JDA current = jda;
            if (current == null) {
                start();
                return;
            }
            Guild guild = current.getGuildById(guildId);
            VoiceChannel channel = guild == null ? null : guild.getVoiceChannelById(requestedChannel);
            if (guild == null || channel == null) {
                lastError = guild == null ? "guild not visible" : "room channel not visible";
                return;
            }
            Member self = guild.getSelfMember();
            if (!self.hasPermission(channel, Permission.VIEW_CHANNEL)
                    || !self.hasPermission(channel, Permission.VOICE_CONNECT)) {
                lastError = "missing View Channel or Connect permission in " + channel.getName();
                return;
            }
            if (minecraftToDiscord && !self.hasPermission(channel, Permission.VOICE_SPEAK)) {
                lastError = "missing Speak permission in " + channel.getName();
                return;
            }
            try {
                AudioManager manager = guild.getAudioManager();
                manager.setAutoReconnect(true);
                manager.setSelfDeafened(!discordToMinecraft);
                manager.setSelfMuted(!minecraftToDiscord);
                manager.setSendingHandler(minecraftToDiscord ? sendHandler : null);
                manager.setReceivingHandler(discordToMinecraft ? receiveHandler : null);
                manager.openAudioConnection(channel);
                audioManager = manager;
                lastError = "";
            } catch (Throwable error) {
                lastError = rootMessage(error);
                plugin.getLogger().warning("Voice relay bot " + index
                        + " could not join room " + requestedRoom + ": " + lastError);
            }
        }

        private void release(UUID releasedRoom) {
            synchronized (assignmentLock) {
                if (!Objects.equals(roomId, releasedRoom)) {
                    return;
                }
                roomId = null;
                channelId = 0L;
            }
            clearAssignmentAudio(true);
        }

        private void enqueue(UUID speakerId, short[] monoPcm) {
            if (speakerId == null || monoPcm == null || monoPcm.length == 0 || roomId == null) {
                return;
            }
            short[] copy = normalizeFrame(monoPcm);
            applyGain(copy, minecraftGain);
            ConcurrentLinkedQueue<short[]> speakerQueue = minecraftFrames.computeIfAbsent(
                    speakerId, ignored -> new ConcurrentLinkedQueue<>());
            int perSourceLimit = Math.max(3, Math.min(10, maxBufferedFrames));
            while (speakerQueue.size() >= perSourceLimit && speakerQueue.poll() != null) {
                decrementQueuedFrames();
                droppedFrames.increment();
            }
            speakerQueue.offer(copy);
            queuedFrames.incrementAndGet();
            while (queuedFrames.get() > maxBufferedFrames) {
                if (!dropOneBufferedFrame()) {
                    break;
                }
            }
        }

        private short[] mixNextFrame() {
            List<Map.Entry<UUID, ConcurrentLinkedQueue<short[]>>> available = minecraftFrames
                    .entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .toList();
            if (available.isEmpty()) {
                return null;
            }
            int start = Math.floorMod(mixCursor.getAndIncrement(), available.size());
            int[] accumulator = new int[FRAME_SAMPLES];
            int mixedSources = 0;
            for (int offset = 0; offset < available.size()
                    && mixedSources < maxMixedSourcesPerFrame; offset++) {
                Map.Entry<UUID, ConcurrentLinkedQueue<short[]>> entry = available.get(
                        (start + offset) % available.size());
                short[] frame = entry.getValue().poll();
                if (frame == null) {
                    continue;
                }
                decrementQueuedFrames();
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    accumulator[i] += frame[i];
                }
                mixedSources++;
                if (entry.getValue().isEmpty()) {
                    minecraftFrames.remove(entry.getKey(), entry.getValue());
                }
            }
            if (mixedSources == 0) {
                return null;
            }
            double attenuation = 1.0 / Math.sqrt(mixedSources);
            short[] mixed = new short[FRAME_SAMPLES];
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                mixed[i] = clamp((int) Math.round(accumulator[i] * attenuation));
            }
            return mixed;
        }

        private boolean dropOneBufferedFrame() {
            List<ConcurrentLinkedQueue<short[]>> queues = minecraftFrames.values().stream()
                    .filter(queue -> !queue.isEmpty())
                    .toList();
            if (queues.isEmpty()) {
                return false;
            }
            int start = Math.floorMod(dropCursor.getAndIncrement(), queues.size());
            for (int offset = 0; offset < queues.size(); offset++) {
                ConcurrentLinkedQueue<short[]> queue = queues.get((start + offset) % queues.size());
                if (queue.poll() == null) {
                    continue;
                }
                decrementQueuedFrames();
                droppedFrames.increment();
                return true;
            }
            return false;
        }

        private void decrementQueuedFrames() {
            queuedFrames.updateAndGet(value -> Math.max(0, value - 1));
        }

        private String userId() {
            JDA current = jda;
            return current == null ? null : current.getSelfUser().getId();
        }

        private boolean isReady() {
            return jda != null;
        }

        private boolean isConnected() {
            AudioManager manager = audioManager;
            return manager != null && manager.isConnected();
        }

        private void clearAssignmentAudio(boolean closeConnection) {
            minecraftFrames.clear();
            queuedFrames.set(0);
            mixCursor.set(0);
            dropCursor.set(0);
            AudioManager manager = audioManager;
            audioManager = null;
            if (manager == null) {
                return;
            }
            try {
                if (manager.getSendingHandler() == sendHandler) {
                    manager.setSendingHandler(null);
                }
                if (manager.getReceivingHandler() == receiveHandler) {
                    manager.setReceivingHandler(null);
                }
                if (closeConnection) {
                    manager.closeAudioConnection();
                }
            } catch (Throwable error) {
                plugin.getLogger().fine("Could not clear relay bot " + index
                        + " audio handlers: " + rootMessage(error));
            }
        }

        private void shutdown() {
            synchronized (assignmentLock) {
                roomId = null;
                channelId = 0L;
            }
            clearAssignmentAudio(true);
            shutdownJda();
        }

        private void shutdownJda() {
            JDA current = jda;
            jda = null;
            if (current != null) {
                try {
                    current.shutdownNow();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static short[] discordStereoToMono(byte[] stereo) {
        short[] mono = new short[FRAME_SAMPLES];
        int frames = Math.min(FRAME_SAMPLES, stereo == null ? 0 : stereo.length / 4);
        for (int i = 0; i < frames; i++) {
            int offset = i * 4;
            short left = (short) (((stereo[offset] & 0xFF) << 8)
                    | (stereo[offset + 1] & 0xFF));
            short right = (short) (((stereo[offset + 2] & 0xFF) << 8)
                    | (stereo[offset + 3] & 0xFF));
            mono[i] = (short) (((int) left + right) / 2);
        }
        return mono;
    }

    private static byte[] monoToDiscordStereo(short[] mono) {
        byte[] stereo = new byte[FRAME_SAMPLES * 4];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            short sample = i < mono.length ? mono[i] : 0;
            int offset = i * 4;
            byte high = (byte) ((sample >>> 8) & 0xFF);
            byte low = (byte) (sample & 0xFF);
            stereo[offset] = high;
            stereo[offset + 1] = low;
            stereo[offset + 2] = high;
            stereo[offset + 3] = low;
        }
        return stereo;
    }

    private static short[] normalizeFrame(short[] input) {
        short[] normalized = new short[FRAME_SAMPLES];
        System.arraycopy(input, 0, normalized, 0, Math.min(input.length, normalized.length));
        return normalized;
    }

    private static void applyGain(short[] samples, double gain) {
        if (gain == 1.0) {
            return;
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] = clamp((int) Math.round(samples[i] * gain));
        }
    }

    private static short clamp(int sample) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
