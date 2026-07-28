package com.hubertstudios.coredsc.voice;

import java.util.Set;
import java.util.UUID;

/**
 * Loader-neutral boundary between CoreDSC and the optional Simple Voice Chat API.
 * None of the types in this contract belong to the external voice-chat plugin,
 * which lets CoreDSC stay loadable when that plugin is not installed.
 */
public interface VoiceBridgeTransport {

    interface Endpoint {
        /** Whether microphone capture is currently useful to the active module. */
        boolean isVoiceRelayActive();

        /** Whether this player's next microphone frame should be decoded. */
        boolean shouldRelayMinecraft(UUID minecraftPlayerId);

        void onMinecraftPcm(UUID minecraftPlayerId, short[] monoPcm);
    }

    boolean isRegistered();

    boolean isServerReady();

    String statusDetail();

    void activate(Endpoint endpoint);

    void deactivate(Endpoint endpoint);

    /** Called from the Bukkit server thread. */
    void synchronizeOnlinePlayers(Set<UUID> playerIds);

    /**
     * Called from a Discord audio thread with one 20 ms, 48 kHz mono frame.
     * Each Discord speaker uses a stable stream ID and therefore an independent
     * Opus encoder/channel. The excluded listener snapshot prevents players who
     * already hear Discord directly from receiving the same audio again through
     * Simple Voice Chat.
     */
    void sendDiscordPcm(
            UUID streamId,
            UUID anchorPlayerId,
            Set<UUID> excludedListenerIds,
            short[] monoPcm
    );

    void shutdown();
}
