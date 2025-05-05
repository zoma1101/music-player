package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.regi.SoundEventRegi;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import static com.zoma1101.music_player.Music_Player.MOD_ID;

@Mod(MOD_ID)
public class Music_Player {
    public static final String MOD_ID = "music_player";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Music_Player(FMLJavaModLoadingContext ctx) {
        // Modのイベントバスを取得
        IEventBus modEventBus = ctx.getModEventBus();
        // --- Mod Event Bus Listeners ---
        SoundEventRegi.register(modEventBus);
        LOGGER.info("Registered ModSounds for {}", MOD_ID);

        LOGGER.info("{} mod initialized.", MOD_ID);
    }
}
