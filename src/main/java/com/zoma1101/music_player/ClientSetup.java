package com.zoma1101.music_player; // 例: client パッケージ


import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.config.SoundPackLoader;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent; // FMLClientSetupEvent をインポート
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * FMLClientSetupEvent のリスナー (クライアント初期化時に一度だけ呼ばれる)
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Client setup for {}.", Music_Player.MOD_ID);
        // イベントスレッド外で実行する必要がある初期化処理をエンキュー
        event.enqueueWork(() -> {
            // ★ ClientEventHandler のデフォルトサウンドをキャッシュするメソッドを呼び出す
            ClientMusicManager.initialize();
            LOGGER.info("Enqueuing ClientEventHandler default sound initialization.");
        });
    }

    /**
     * AddPackFindersEvent のリスナー (リソースパック検索時に呼ばれる)
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        // クライアントリソース用のファインダーのみを登録
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            try {
                SoundPackLoader.loadSoundPacks();
                // ★ SoundPackFinder を登録 (DirectoryValidatorなし版)
                event.addRepositorySource(new SoundPackFinder(PackType.CLIENT_RESOURCES));
            } catch (Exception e) {
                LOGGER.error("Failed during AddPackFindersEvent processing", e);
            }
        }
    }
}