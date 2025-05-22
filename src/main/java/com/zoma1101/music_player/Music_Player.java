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
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
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
        modEventBus.addListener(this::clientSetup); // ClientSetupクラスの代わりにここで直接処理も可
        modEventBus.addListener(this::onRegisterClientReloadListeners); // ★ 既存のリスナー
        modEventBus.addListener(this::onAddPackFinders);               // ★ 新しいリスナー
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Music Player: FMLCommonSetupEvent received.");
        LOGGER.info("Music Player: Initiating synchronous sound pack discovery and loading during common setup...");
        soundPackManager.discoverAndLoadPacks();
        LOGGER.info("Music Player: Synchronous sound pack discovery and loading complete during common setup.");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // ClientSetupクラスで行っていた処理をここに移動するか、
        // ClientSetupクラスの onClientSetup メソッドを static のまま維持し、
        // Music_Player クラスのコンストラクタで modEventBus.register(ClientSetup.class); を行う。
        // ここでは ClientSetup クラスは別途登録されている前提とします。
        LOGGER.info("Music Player: FMLClientSetupEvent received (handled by Music_Player or ClientSetup class).");
    }

    // RegisterClientReloadListenersEvent のハンドラ
    // @SubscribeEvent アノテーションは、MODイベントバスにクラスごと登録する場合にメソッドに付与
    // addListenerで登録する場合は不要（ただし、付けても害はないことが多い）
    public void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        LOGGER.info("Music Player: Registering Client Reload Listeners");
        if (modSoundResourcePackInstance == null) {
            modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
        }
        event.registerReloadListener(modSoundResourcePackInstance);
        LOGGER.info("Registered ModSoundResourcePack as ReloadListener.");
    }

    // AddPackFindersEvent のハンドラ
    public void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            LOGGER.info("Music Player: AddPackFindersEvent for CLIENT_RESOURCES received.");

            if (modSoundResourcePackInstance == null) {
                modSoundResourcePackInstance = new ModSoundResourcePack(MOD_ID + "_soundpacks");
                LOGGER.warn("Music Player: ModSoundResourcePack was null in onAddPackFinders, creating new instance. This might indicate an issue with event order if onRegisterClientReloadListeners wasn't called first.");
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