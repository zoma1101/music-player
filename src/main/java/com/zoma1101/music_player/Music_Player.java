package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.regi.SoundEventRegi;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
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

        modEventBus.addListener(this::commonSetup);
        // ★Client Setup リスナーを追加
        modEventBus.addListener(this::clientSetup);

        LOGGER.info("{} mod initialized.", MOD_ID);
    }

    // Common setup メソッド (今は空でもOK)
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Common setup for {}.", MOD_ID);
        // 将来的にサーバー/クライアント共通の初期化処理が必要な場合に使う
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Client setup for {}.", MOD_ID);
        // ClientEventHandlerの初期化メソッドを呼び出す
        // event.enqueueWork() を使うと、メインスレッドで実行されることが保証される
        event.enqueueWork(ClientEventHandler::initializeBiomeMusicMap);
        LOGGER.info("Enqueued Biome Music Map initialization.");
    }

}
