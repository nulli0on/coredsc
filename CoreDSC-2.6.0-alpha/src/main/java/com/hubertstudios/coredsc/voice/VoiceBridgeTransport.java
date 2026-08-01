package com.hubertstudios.coredsc.voice;

import java.util.Set;
import java.util.UUID;

 
                                                                                  
                                                                               
                                                                      
   
public interface VoiceBridgeTransport {

    interface Endpoint {
                                                                                 
        boolean isVoiceRelayActive();

                                                                           
        boolean shouldRelayMinecraft(UUID minecraftPlayerId);

        void onMinecraftPcm(UUID minecraftPlayerId, short[] monoPcm);
    }

    boolean isRegistered();

    boolean isServerReady();

    String statusDetail();

    void activate(Endpoint endpoint);

    void deactivate(Endpoint endpoint);

                                              
    void synchronizeOnlinePlayers(Set<UUID> playerIds);

     
                                                                            
                                                                                
                                                                                
                                                                                
                         
       
    void sendDiscordPcm(
            UUID streamId,
            UUID anchorPlayerId,
            Set<UUID> excludedListenerIds,
            short[] monoPcm
    );

    void shutdown();
}
