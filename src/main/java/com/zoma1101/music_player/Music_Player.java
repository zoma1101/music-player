package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.SoundPackManager;
import net.minecraft.SharedConstants;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.network.chat.Component;
import com.zoma1101.music_player.sound.ModSoundResourcePack;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Music_Player.MOD_ID)
public class Music_Player {
    public static final String MOD_ID = "music_player";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final SoundPackManager soundPackManager = new SoundPackManager();

    private static ModSoundResourcePack modSoundResourcePackInstance;

    public Music_Player(FMLJavaModLoadingContext ctx) { // FMLJavaModLoadingContext ctx 引数を削除 (一般的なパターン)
        IEventBus modEventBus = ctx.getModEventBus();

        // MODイベントバスへのリスナー登録 (MODライフサイクルイベント用)
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterClientReloadListeners);
        modEventBus.addListener(this::onAddPackFinders);

        // Forgeイベントバスへのリスナー登録 (ゲームイベント用)
        MinecraftForge.EVENT_BUS.register(this); // GameShuttingDownEvent用
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // soundPackManager.discoverAndLoadPacks(); // ★この行を削除またはコメントアウト
        LOGGER.info("Music_Player commonSetup: SoundPackManager initialization will occur during the first resource reload via ModSoundResourcePack.");
    }

    // MODイベントバス用のリスナー
    public void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        if (modSoundResourcePackInstance == null) {
            modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
        }
        event.registerReloadListener(modSoundResourcePackInstance);
    }

    // MODイベントバス用のリスナー
    public void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            if (modSoundResourcePackInstance == null) {
                modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
            }

            Component packInfoDescription = Component.literal("Provides dynamic sound resources for the Music Player mod.");
            int clientPackFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
            FeatureFlagSet requestedFeatures = FeatureFlagSet.of();

            final Pack.Info packInfo = new Pack.Info(
                    packInfoDescription,
                    clientPackFormat,
                    requestedFeatures
            );

            event.addRepositorySource((consumer) -> {
                Pack pack = Pack.create(
                        modSoundResourcePackInstance.packId(),
                        Component.literal("Music Player Dynamic Sounds"),
                        true,
                        (packId) -> modSoundResourcePackInstance,
                        packInfo,
                        PackType.CLIENT_RESOURCES,
                        Pack.Position.TOP,
                        true,
                        PackSource.BUILT_IN
                );
                consumer.accept(pack);
            });
        }
    }

    // Forgeイベントバス用のリスナー
    @SubscribeEvent
    public void onGameShuttingDown(final GameShuttingDownEvent event) {
        LOGGER.info("Music Player is shutting down (GameShuttingDownEvent). Performing cleanup...");
        soundPackManager.onShutdown();
    }
}