package com.zoma1101.music_player;

import com.zoma1101.music_player.config.ClientConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import static com.zoma1101.music_player.Music_Player.MOD_ID;

@Mod(MOD_ID)
public class Music_Player {
    public static final String MOD_ID = "music_player";
    public Music_Player(FMLJavaModLoadingContext ctx) {
        ctx.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

    }
}
