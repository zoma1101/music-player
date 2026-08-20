package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.SoundPackManager;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.network.chat.Component;
import com.zoma1101.music_player.sound.ModSoundResourcePack;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;

import java.util.Optional;

@Mod(Music_Player.MOD_ID)
public class Music_Player {
    public static final String MOD_ID = "music_player";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final SoundPackManager soundPackManager = new SoundPackManager();

    private static ModSoundResourcePack modSoundResourcePackInstance;

    public Music_Player(IEventBus modEventBus, ModContainer modContainer) {
        // MODイベントバスへのリスナー登録 (MODライフサイクルイベント用)
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterClientReloadListeners);
        modEventBus.addListener(this::onAddPackFinders);

        // Forgeイベントバスへのリスナー登録 (ゲームイベント用)
        NeoForge.EVENT_BUS.register(this); // GameShuttingDownEvent用

        // NeoForge標準のConfigの登録 (.toml)
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, MusicPlayerConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info(
                "Music_Player commonSetup: SoundPackManager will be initialized during the first resource reload via ModSoundResourcePack.reload().");
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

            event.addRepositorySource((consumer) -> {
                // 1. パックの基本情報（ID、表示名、ソース元、既知のパックかどうか）
                PackLocationInfo locationInfo = new PackLocationInfo(
                        modSoundResourcePackInstance.packId(),
                        Component.literal("Music Player Dynamic Sounds"),
                        PackSource.BUILT_IN,
                        Optional.empty());

                // 2. パックの選択設定（必須かどうか、デフォルト位置、位置を固定するか）
                PackSelectionConfig selectionConfig = new PackSelectionConfig(
                        true, // isRequired
                        Pack.Position.TOP, // defaultPosition
                        true // fixedPosition
                );

                // 3. メタデータを読み込んでパックを作成
                Pack pack = Pack.readMetaAndCreate(
                        locationInfo,
                        // ResourcesSupplier（関数型インターフェース）
                        new Pack.ResourcesSupplier() {
                            @Override
                            public net.minecraft.server.packs.PackResources openPrimary(PackLocationInfo info) {
                                return modSoundResourcePackInstance;
                            }

                            @Override
                            public net.minecraft.server.packs.PackResources openFull(PackLocationInfo info,
                                    Pack.Metadata metadata) {
                                return modSoundResourcePackInstance;
                            }
                        },
                        PackType.CLIENT_RESOURCES,
                        selectionConfig);

                if (pack != null) {
                    consumer.accept(pack);
                }
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