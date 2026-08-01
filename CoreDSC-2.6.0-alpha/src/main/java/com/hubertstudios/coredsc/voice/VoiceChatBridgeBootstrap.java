package com.hubertstudios.coredsc.voice;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

 
                                                                             
                                                                              
                                                                            
                                                                              
                                                                 
   
public final class VoiceChatBridgeBootstrap {
    private final VoiceBridgeTransport transport;
    private final String registrationDetail;

    private VoiceChatBridgeBootstrap(VoiceBridgeTransport transport, String registrationDetail) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.registrationDetail = registrationDetail == null ? "" : registrationDetail;
    }

    public static VoiceChatBridgeBootstrap registerEarly(CoreDSCPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> serviceType = Class.forName(
                    "de.maxhenkel.voicechat.api.BukkitVoicechatService", false, loader);
            Class<?> voicePluginType = Class.forName(
                    "de.maxhenkel.voicechat.api.VoicechatPlugin", false, loader);

            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager()
                    .getRegistration((Class) serviceType);
            if (registration == null || registration.getProvider() == null) {
                return unavailable("Simple Voice Chat service is not registered");
            }

            Class<?> implementationType = Class.forName(
                    "com.hubertstudios.coredsc.voice.SimpleVoiceChatBridge", true, loader);
            Constructor<?> constructor = implementationType.getConstructor(CoreDSCPlugin.class);
            Object bridge = constructor.newInstance(plugin);
            if (!(bridge instanceof VoiceBridgeTransport transport)) {
                return unavailable("voice bridge does not implement the CoreDSC transport contract");
            }

            Method registerPlugin = serviceType.getMethod("registerPlugin", voicePluginType);
            registerPlugin.invoke(registration.getProvider(), bridge);
            plugin.getLogger().info("Registered the passive Simple Voice Chat bridge.");
            return new VoiceChatBridgeBootstrap(transport, "registered");
        } catch (ClassNotFoundException error) {
            return unavailable("Simple Voice Chat is not installed");
        } catch (Throwable error) {
            String detail = rootMessage(error);
            plugin.getLogger().warning("Could not register Simple Voice Chat bridge: " + detail);
            return unavailable(detail);
        }
    }

    public VoiceBridgeTransport transport() {
        return transport;
    }

    public boolean isRegistered() {
        return transport.isRegistered();
    }

    public String statusDetail() {
        String transportDetail = transport.statusDetail();
        return registrationDetail.isBlank() ? transportDetail
                : registrationDetail + (transportDetail.isBlank() ? "" : "; " + transportDetail);
    }

    public void shutdown() {
        transport.shutdown();
    }

    private static VoiceChatBridgeBootstrap unavailable(String detail) {
        return new VoiceChatBridgeBootstrap(new UnavailableTransport(detail), detail);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class UnavailableTransport implements VoiceBridgeTransport {
        private final String detail;

        private UnavailableTransport(String detail) {
            this.detail = detail == null ? "unavailable" : detail;
        }

        @Override public boolean isRegistered() { return false; }
        @Override public boolean isServerReady() { return false; }
        @Override public String statusDetail() { return detail; }
        @Override public void activate(Endpoint endpoint) { }
        @Override public void deactivate(Endpoint endpoint) { }
        @Override public void synchronizeOnlinePlayers(Set<UUID> playerIds) { }
        @Override public void sendDiscordPcm(
                UUID streamId,
                UUID anchorPlayerId,
                Set<UUID> excludedListenerIds,
                short[] monoPcm
        ) { }
        @Override public void shutdown() { }
    }
}
