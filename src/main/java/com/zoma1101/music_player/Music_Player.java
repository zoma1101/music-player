package com.zoma1101.music_player;


import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.SoundPackManager;
import net.minecraft.SharedConstants;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent; // 既存のimportを確認
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.network.chat.Component;
import com.zoma1101.music_player.sound.ModSoundResourcePack; // ModSoundResourcePackをインポート
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// ... (他のクラス定義やフィールド) ...

@Mod(Music_Player.MOD_ID)
public class Music_Player {
    public static final String MOD_ID = "music_player";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final SoundPackManager soundPackManager = new SoundPackManager();

    // ModSoundResourcePackのインスタンスを保持するためのフィールド
    private static ModSoundResourcePack modSoundResourcePackInstance;

    public Music_Player(FMLJavaModLoadingContext ctx) {
        // ... (既存のコンストラクタ内の処理) ...
        IEventBus modEventBus = ctx.getModEventBus();
        modEventBus.register(this); // MODメインクラスをMODイベントバスに登録
        // イベントリスナーの登録
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterClientReloadListeners); // ★ 既存のリスナー
        modEventBus.addListener(this::onAddPackFinders);               // ★ 新しいリスナー
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        soundPackManager.discoverAndLoadPacks();
    }

    public void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        if (modSoundResourcePackInstance == null) {
            modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
        }
        event.registerReloadListener(modSoundResourcePackInstance);
    }

    // AddPackFindersEvent のハンドラ
    public void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            if (modSoundResourcePackInstance == null) {
                modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
            }

            // Pack.Info を作成
            // 1. リソースパックの説明 (Component)
            Component packInfoDescription = Component.literal("Provides dynamic sound resources for the Music Player mod.");

            // 2. パックフォーマット (int) - クライアントリソース用
            // SharedConstants を使って現在のMinecraftバージョンに適したパックフォーマットを取得
            int clientPackFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);

            // 3. 要求される機能フラグ (FeatureFlagSet) - 通常は空で問題なし
            FeatureFlagSet requestedFeatures = FeatureFlagSet.of(); // または FeatureFlagSet.of()

            // Pack.Info のインスタンスをセカンダリコンストラクタで生成
            final Pack.Info packInfo = new Pack.Info(
                    packInfoDescription,    // Component description
                    clientPackFormat,       // int format (これが dataFormat と resourceFormat の両方に使われる)
                    requestedFeatures       // FeatureFlagSet requestedFeatures
            );

            event.addRepositorySource((consumer) -> {
                Pack pack = Pack.create(
                        modSoundResourcePackInstance.packId(),              // 1. id (String)
                        Component.literal("Music Player Dynamic Sounds"),   // 2. title (Component) - リソースパック選択画面での表示名
                        true,                                               // 3. required (boolean) - このパックが常にロードされるべきか
                        (packId) -> modSoundResourcePackInstance,           // 4. supplier (Pack.ResourcesSupplier)
                        packInfo,                                           // 5. info (Pack.Info) ★修正済み
                        PackType.CLIENT_RESOURCES,                          // 6. type (PackType)
                        Pack.Position.TOP,                                  // 7. defaultPosition (Pack.Position) - 読み込み優先順位
                        true,                                               // 8. isFixedPosition (boolean) - ユーザーが有効/無効を切り替えられないか
                        PackSource.BUILT_IN                                 // 9. source (PackSource)
                );
                consumer.accept(pack);
            });
        }
    }
}