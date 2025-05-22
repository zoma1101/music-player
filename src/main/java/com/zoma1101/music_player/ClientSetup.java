package com.zoma1101.music_player; // 例: client パッケージ
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent; // FMLClientSetupEvent をインポート
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Client setup for {}.", Music_Player.MOD_ID);
        // ★★★ discoverAndLoadPacks() の呼び出しを削除 ★★★
        event.enqueueWork(() -> {
            LOGGER.info("Client setup work enqueued.");
        });
        // LOGGER.info("Client setup work enqueued."); // この行は重複しているので削除しても良い
    }
}