package com.hubertstudios.coredsc.voice;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive Simple Voice Chat server-side extension used by CoreDSC. Audio
 * outputs are created lazily for active Discord speakers and closed when their
 * anchor player leaves the eligible set.
 */
public final class SimpleVoiceChatBridge implements VoicechatPlugin, VoiceBridgeTransport {
    private static final int FRAME_SAMPLES = 960;

    private final CoreDSCPlugin plugin;
    private final Map<UUID, OpusDecoder> microphoneDecoders = new ConcurrentHashMap<>();
    private final Map<OutputKey, PlayerOutput> discordOutputs = new ConcurrentHashMap<>();
    private final Map<OutputKey, Set<UUID>> discordOutputExclusions = new ConcurrentHashMap<>();
    private final Map<OutputKey, Long> discordOutputLastUsed = new ConcurrentHashMap<>();
    private final Set<UUID> desiredOnlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<OutputKey> pendingOutputs = ConcurrentHashMap.newKeySet();

    private volatile VoicechatServerApi serverApi;
    private volatile Endpoint endpoint;
    private volatile boolean shutdown;

    public SimpleVoiceChatBridge(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String getPluginId() {
        return "coredsc";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi server) {
            serverApi = server;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, ignored -> onServerStopped());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        if (!shutdown) {
            plugin.getLogger().info("Simple Voice Chat server API is ready for CoreDSC.");
        }
    }

    private void onServerStopped() {
        serverApi = null;
        closeAudioResources();
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        Endpoint currentEndpoint = endpoint;
        VoicechatServerApi currentApi = serverApi;
        if (shutdown || currentEndpoint == null || currentApi == null
                || !currentEndpoint.isVoiceRelayActive()
                || event.getSenderConnection() == null) {
            return;
        }

        UUID playerId = event.getSenderConnection().getPlayer().getUuid();
        if (!currentEndpoint.shouldRelayMinecraft(playerId)) {
            return;
        }
        byte[] encoded = event.getPacket().getOpusEncodedData();
        if (encoded == null || encoded.length == 0) {
            return;
        }

        try {
            OpusDecoder decoder = microphoneDecoders.computeIfAbsent(
                    playerId, ignored -> currentApi.createDecoder());
            short[] decoded;
            synchronized (decoder) {
                decoded = decoder.decode(encoded);
            }
            if (decoded != null && decoded.length > 0) {
                currentEndpoint.onMinecraftPcm(playerId, normalizeFrame(decoded));
            }
        } catch (Throwable error) {
            plugin.getLogger().fine("Could not decode a Simple Voice Chat frame from "
                    + playerId + ": " + rootMessage(error));
            closeDecoder(playerId);
        }
    }

    @Override
    public boolean isRegistered() {
        return true;
    }

    @Override
    public boolean isServerReady() {
        return serverApi != null && !shutdown;
    }

    @Override
    public String statusDetail() {
        return isServerReady()
                ? "Simple Voice Chat ready; " + discordOutputs.size() + " active spatial output(s)"
                : "registered; waiting for Simple Voice Chat server";
    }

    @Override
    public void activate(Endpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public void deactivate(Endpoint endpoint) {
        if (this.endpoint == endpoint) {
            this.endpoint = null;
        }
        desiredOnlinePlayers.clear();
        closeAudioResources();
    }

    @Override
    public void synchronizeOnlinePlayers(Set<UUID> playerIds) {
        if (shutdown) {
            return;
        }
        desiredOnlinePlayers.clear();
        if (playerIds != null) {
            desiredOnlinePlayers.addAll(playerIds);
        }
        long idleCutoff = System.currentTimeMillis() - 60_000L;
        for (OutputKey key : Set.copyOf(discordOutputs.keySet())) {
            if (!desiredOnlinePlayers.contains(key.anchorPlayerId())
                    || discordOutputLastUsed.getOrDefault(key, 0L) < idleCutoff) {
                closeOutput(key);
            }
        }
        for (UUID playerId : Set.copyOf(microphoneDecoders.keySet())) {
            if (!desiredOnlinePlayers.contains(playerId)) {
                closeDecoder(playerId);
            }
        }
    }

    @Override
    public void sendDiscordPcm(
            UUID streamId,
            UUID anchorPlayerId,
            Set<UUID> excludedListenerIds,
            short[] monoPcm
    ) {
        if (shutdown || streamId == null || anchorPlayerId == null || monoPcm == null
                || monoPcm.length == 0 || !desiredOnlinePlayers.contains(anchorPlayerId)) {
            return;
        }
        OutputKey key = new OutputKey(streamId, anchorPlayerId);
        discordOutputExclusions.put(key, excludedListenerIds == null
                ? Set.of() : Set.copyOf(excludedListenerIds));
        discordOutputLastUsed.put(key, System.currentTimeMillis());
        PlayerOutput output = discordOutputs.get(key);
        if (output == null) {
            requestOutput(key);
            return;
        }
        try {
            byte[] encoded;
            synchronized (output) {
                encoded = output.encoder().encode(normalizeFrame(monoPcm));
                output.channel().send(encoded);
            }
        } catch (Throwable error) {
            plugin.getLogger().fine("Could not relay Discord audio into Minecraft at "
                    + anchorPlayerId + ": " + rootMessage(error));
            closeOutput(key);
        }
    }

    private void requestOutput(OutputKey key) {
        if (!pendingOutputs.add(key)) {
            return;
        }
        plugin.callForPlayer(key.anchorPlayerId(), player -> {
            prepareOutput(key, player);
            return Boolean.TRUE;
        }).whenComplete((ignored, error) -> pendingOutputs.remove(key));
    }

    private void prepareOutput(OutputKey key, Player player) {
        if (shutdown || discordOutputs.containsKey(key)
                || !desiredOnlinePlayers.contains(key.anchorPlayerId())) {
            return;
        }
        VoicechatServerApi currentApi = serverApi;
        if (currentApi == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            Entity entity = currentApi.fromEntity(player);
            UUID channelId = UUID.nameUUIDFromBytes(
                    ("coredsc:discord:" + key.streamId() + ':' + key.anchorPlayerId())
                            .getBytes(StandardCharsets.UTF_8));
            EntityAudioChannel channel = currentApi.createEntityAudioChannel(channelId, entity);
            if (channel == null) {
                return;
            }
            channel.setFilter(listener -> !discordOutputExclusions
                    .getOrDefault(key, Set.of()).contains(listener.getUuid()));
            OpusEncoder encoder = currentApi.createEncoder();
            discordOutputs.put(key, new PlayerOutput(channel, encoder));
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not create spatial Discord audio output for "
                    + player.getName() + ": " + rootMessage(error));
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        endpoint = null;
        desiredOnlinePlayers.clear();
        pendingOutputs.clear();
        serverApi = null;
        closeAudioResources();
    }

    private void closeAudioResources() {
        for (OutputKey key : Set.copyOf(discordOutputs.keySet())) {
            closeOutput(key);
        }
        for (UUID playerId : Set.copyOf(microphoneDecoders.keySet())) {
            closeDecoder(playerId);
        }
    }

    private void closeOutput(OutputKey key) {
        PlayerOutput output = discordOutputs.remove(key);
        discordOutputExclusions.remove(key);
        discordOutputLastUsed.remove(key);
        if (output == null) {
            return;
        }
        synchronized (output) {
            try {
                output.channel().flush();
            } catch (Throwable ignored) {
            }
            try {
                output.encoder().close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void closeDecoder(UUID playerId) {
        OpusDecoder decoder = microphoneDecoders.remove(playerId);
        if (decoder == null) {
            return;
        }
        synchronized (decoder) {
            try {
                decoder.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static short[] normalizeFrame(short[] input) {
        if (input.length == FRAME_SAMPLES) {
            return Arrays.copyOf(input, input.length);
        }
        short[] normalized = new short[FRAME_SAMPLES];
        System.arraycopy(input, 0, normalized, 0, Math.min(input.length, normalized.length));
        return normalized;
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

    private record OutputKey(UUID streamId, UUID anchorPlayerId) { }

    private record PlayerOutput(EntityAudioChannel channel, OpusEncoder encoder) { }
}
