package com.zoma1101.music_player;

import com.zoma1101.music_player.regi.SoundEventRegi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.logging.LogUtils; // Logger をインポート
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.util.*;


// このクラスのイベントハンドラをForgeのイベントバスに登録します (クライアントサイド限定)
@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEventHandler {


    private static final Logger LOGGER = LogUtils.getLogger();
    private static SoundInstance currentMusic = null;
    // private static ResourceLocation lastBiomeId = null; // biome ID ではなく、最後に再生した SoundEvent で管理
    private static final int CHECK_INTERVAL_TICKS = 20; // チェック間隔 (変更可)

    private static final Map<ResourceKey<Biome>, RegistryObject<SoundEvent>> biomeMusicMap = new HashMap<>();
    // ★村バイオーム用のマッピングを追加
    private static final Map<ResourceKey<Biome>, RegistryObject<SoundEvent>> villageBiomeMusicMap = new HashMap<>();
    private static SoundEvent defaultMusicSound = null;

    // ★最後に再生を開始した SoundEvent を追跡 (不要な再再生を防ぐため)
    private static SoundEvent lastPlayedSoundEvent = null;

    private static final Set<Integer> activeCombatEntityIds = new HashSet<>();
    // ★ 戦闘判定の半径を定数化
    private static final double COMBAT_CHECK_RADIUS = 24.0;


    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        // 再生されようとしているサウンドインスタンスを取得
        SoundInstance sound = event.getSound(); // 1.18以降は getSound() (旧バージョンは getSound() や name で取得)

        // 現在ModのカスタムBGMが再生中かどうかを確認
        boolean isModMusicPlaying = currentMusic != null && Minecraft.getInstance().getSoundManager().isActive(currentMusic);

        // ModのBGMが再生中で、かつ再生されようとしているサウンドが存在する場合
        if (isModMusicPlaying && sound != null) {
            boolean isVanillaMusic = sound.getSource() == SoundSource.MUSIC &&
                    sound.getLocation().getNamespace().equals("minecraft") &&
                    sound.getLocation().getPath().startsWith("music.");

            if (isVanillaMusic) {
                event.setSound(null);
            }
        }
    }


    // このメソッドは Client Setup フェーズで一度だけ呼ばれるようにする (後述)
    public static void initializeBiomeMusicMap() {
        LOGGER.info("Initializing Biome Music Map for {}", Music_Player.MOD_ID);
        // デフォルトBGMを設定
        defaultMusicSound = SoundEventRegi.BGM_DEFAULT.get();

        // 各バイオームに対応するBGMをマップに追加
        // net.minecraft.world.level.biome.Biomes クラスの定数を使用
        biomeMusicMap.put(Biomes.PLAINS, SoundEventRegi.BGM_PLAINS);
        biomeMusicMap.put(Biomes.FOREST, SoundEventRegi.BGM_FOREST);
        biomeMusicMap.put(Biomes.FLOWER_FOREST, SoundEventRegi.BGM_FOREST);
        biomeMusicMap.put(Biomes.BIRCH_FOREST, SoundEventRegi.BGM_FOREST);
        biomeMusicMap.put(Biomes.DARK_FOREST, SoundEventRegi.BGM_FOREST);
        biomeMusicMap.put(Biomes.DESERT, SoundEventRegi.BGM_DESERT);
        biomeMusicMap.put(Biomes.BADLANDS, SoundEventRegi.BGM_DESERT);
        biomeMusicMap.put(Biomes.ERODED_BADLANDS, SoundEventRegi.BGM_DESERT);
        biomeMusicMap.put(Biomes.WOODED_BADLANDS, SoundEventRegi.BGM_DESERT);
        biomeMusicMap.put(Biomes.RIVER, SoundEventRegi.BGM_OCEAN);
        biomeMusicMap.put(Biomes.OCEAN, SoundEventRegi.BGM_OCEAN);
        biomeMusicMap.put(Biomes.WARM_OCEAN, SoundEventRegi.BGM_OCEAN);
        biomeMusicMap.put(Biomes.COLD_OCEAN, SoundEventRegi.BGM_OCEAN);
        biomeMusicMap.put(Biomes.DEEP_OCEAN, SoundEventRegi.BGM_OCEAN);
        // ... 他のバイオームとBGMのマッピングを追加 ...

        // --- ★村バイオーム専用のマッピング ---
        // バニラで村が生成されるバイオームをマッピングする
        villageBiomeMusicMap.put(Biomes.PLAINS, SoundEventRegi.BGM_VILLAGE_PLAINS);
        villageBiomeMusicMap.put(Biomes.DESERT, SoundEventRegi.BGM_VILLAGE_DESERT); // 砂漠の村用BGM
        villageBiomeMusicMap.put(Biomes.SAVANNA, SoundEventRegi.BGM_VILLAGE_DESERT);
        villageBiomeMusicMap.put(Biomes.SNOWY_PLAINS, SoundEventRegi.BGM_VILLAGE_SNOWY);
        villageBiomeMusicMap.put(Biomes.TAIGA, SoundEventRegi.BGM_VILLAGE_TAIGA);
        villageBiomeMusicMap.put(Biomes.RIVER, SoundEventRegi.BGM_VILLAGE_TAIGA);
        villageBiomeMusicMap.put(Biomes.SWAMP, SoundEventRegi.BGM_VILLAGE_SWAMP);


        LOGGER.info("Biome Music Map Initialized with {} entries.", biomeMusicMap.size());
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player logged in. Resetting music state.");
        lastPlayedSoundEvent = null; // 状態リセット
        checkMusicConditionAndPlay(); // ★変更: 新しいチェックメソッドを呼ぶ
    }

    // ログアウト時 (lastPlayedSoundEvent もリセット)
    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player logged out. Stopping BGM.");
        stopCurrentMusic();
        lastPlayedSoundEvent = null; // ★状態リセット
    }

    // Tick イベント (チェックメソッド呼び出し以外は変更なし)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) {
                checkMusicConditionAndPlay(); // ★変更: 新しいチェックメソッドを呼ぶ
            }
        }
    }

    // ★条件チェックと音楽再生のメインロジック (更新)
    private static void checkMusicConditionAndPlay() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        Screen screen = mc.screen;

        if (player == null || level == null) {
            if (currentMusic != null || lastPlayedSoundEvent != null) {
                stopCurrentMusic();
            }
            return;
        }

        SoundEvent targetSoundEvent;
        String conditionMet;
        long timeOfDay = level.getDayTime() % 24000;
        boolean isNight = timeOfDay >= 13000 && timeOfDay < 23000;

        // --- 優先順位に従って再生する SoundEvent を決定 ---

        // 1. GUI (作業台)
        if (screen instanceof CraftingScreen) {
            targetSoundEvent = SoundEventRegi.BGM_CRAFTING.get();
            conditionMet = "Crafting GUI";
        }
        // 2. 戦闘中
        else if (updateCombatStateAndCheck(player, level)) {
            targetSoundEvent = SoundEventRegi.BGM_COMBAT.get(); // SoundEventRegi を使用
            conditionMet = "Combat (Persistent)";
        }
        // 3. 高高度
        else if (player.getY() > 150) {
            targetSoundEvent = SoundEventRegi.BGM_HIGH_ALTITUDE.get();
            conditionMet = "High Altitude (Y > 150)";
        }
        // 4. 低高度
        else if (player.getY() < 0) {
            targetSoundEvent = SoundEventRegi.BGM_LOW_ALTITUDE.get();
            conditionMet = "Low Altitude (Y < 0)";
        }
        // 5. 村
        else if (isInVillageHeuristic(player, level)) {
            // ▼▼▼ 村ブロック内部を変更 ▼▼▼
            // ★ 夜かどうかをチェック
            if (isNight) {
                // 5a. 夜の村
                targetSoundEvent = SoundEventRegi.BGM_VILLAGE_NIGHT.get(); // ★ 夜の村BGM
                conditionMet = "Village (Night)";
            } else {
                // 5b. 昼の村 (バイオーム別) - 既存のロジックをここに移動
                BlockPos playerPos = player.blockPosition();
                Holder<Biome> currentBiomeHolder = level.getBiome(playerPos);
                Optional<ResourceKey<Biome>> currentBiomeKeyOptional = currentBiomeHolder.unwrapKey();

                if (currentBiomeKeyOptional.isPresent()) {
                    ResourceKey<Biome> currentBiomeKey = currentBiomeKeyOptional.get();
                    targetSoundEvent = villageBiomeMusicMap.getOrDefault(
                            currentBiomeKey,
                            SoundEventRegi.BGM_VILLAGE_DEFAULT // 昼のデフォルト村BGM
                    ).get();
                    conditionMet = "Village (Day - " + currentBiomeKey.location().getPath() + ")";
                } else {
                    targetSoundEvent = SoundEventRegi.BGM_VILLAGE_DEFAULT.get();
                    conditionMet = "Village (Day - Unknown Biome)";
                    LOGGER.warn("Could not determine biome key in village heuristic, using default village music.");
                }
            }
        }
        // 6. ★ 夜 (汎用) - 新しい else if ブロックを挿入
        else if (isNight) {
            targetSoundEvent = SoundEventRegi.BGM_NIGHT.get();
            conditionMet = "Night (General)";
        }
        // 7. バイオーム
        else {
            // このブロックは、上記いずれの条件にも当てはまらない場合（主に昼間の村以外の場所）に実行される
            BlockPos playerPos = player.blockPosition();
            Holder<Biome> currentBiomeHolder = level.getBiome(playerPos);
            Optional<ResourceKey<Biome>> currentBiomeKeyOptional = currentBiomeHolder.unwrapKey();
            if (currentBiomeKeyOptional.isPresent()) {
                ResourceKey<Biome> currentBiomeKey = currentBiomeKeyOptional.get();
                // 通常のバイオームマップを使用
                targetSoundEvent = biomeMusicMap.getOrDefault(
                        currentBiomeKey,
                        SoundEventRegi.BGM_DEFAULT // ★ 全体のデフォルト BGM
                ).get();
                conditionMet = "Biome (Day/General - " + currentBiomeKey.location().getPath() + ")";
                // defaultMusicSound 変数と比較 (この変数が ClientEventHandler で定義されていると仮定)
                if (targetSoundEvent == defaultMusicSound) {
                    conditionMet = "Biome (Default Music)";
                }
            } else {
                // defaultMusicSound 変数を使用
                targetSoundEvent = defaultMusicSound;
                conditionMet = "Unknown Biome (Default Music)";
            }
        }

        // --- 再生処理 ---
        if (targetSoundEvent == null) {
            conditionMet = "Fallback Default";
        }

        boolean needsChange;
        if (currentMusic == null || !mc.getSoundManager().isActive(currentMusic)) {
            needsChange = (targetSoundEvent != null);
        } else {
            needsChange = (lastPlayedSoundEvent == null || !Objects.requireNonNull(targetSoundEvent).getLocation().equals(lastPlayedSoundEvent.getLocation()));
        }

        if (needsChange) {
            LOGGER.info("Music condition requires change. Target: [{}] determined by [{}].",
                    targetSoundEvent != null ? targetSoundEvent.getLocation() : "None", conditionMet);
            stopCurrentMusic();
            if (targetSoundEvent != null) {
                currentMusic = SimpleSoundInstance.forMusic(targetSoundEvent);
                mc.getSoundManager().play(currentMusic);
                lastPlayedSoundEvent = targetSoundEvent;
            }
        }
    }

    // ★ Helper: 戦闘状態か判定
    // ★ isCombatActive を置き換える、状態更新とチェックを行うメソッド
    private static boolean updateCombatStateAndCheck(LocalPlayer player, Level level) {
        // 1. 現在アグレッシブなMobを探し、IDを一時セットに追加
        Set<Integer> currentlyAggressiveIds = new HashSet<>();
        List<Mob> nearbyMobs = level.getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(COMBAT_CHECK_RADIUS),
                LivingEntity::isAlive // まずは生きているMobをリストアップ
        );

        for (Mob mob : nearbyMobs) {
            if (mob.isAggressive()) { // 現在アグレッシブかチェック
                currentlyAggressiveIds.add(mob.getId());
                // LOGGER.debug("Entity {} is currently aggressive.", mob.getId()); // Debug
            }
        }

        // 2. 新たにアグレッシブになったMobをメインの追跡セットに追加
        if (!currentlyAggressiveIds.isEmpty()) {
            // LOGGER.debug("Adding {} new aggressive entities to tracked set.", currentlyAggressiveIds.size()); // Debug
            activeCombatEntityIds.addAll(currentlyAggressiveIds);
        }


        // 3. 追跡中のエンティティが条件を満たさなくなったか確認し、セットから削除
        //    (Iteratorを使うことで安全にループ中に削除できる)
        Iterator<Integer> iterator = activeCombatEntityIds.iterator();
        // Debug
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            Entity entity = level.getEntity(entityId); // IDからエンティティを取得 (nullの場合あり)

            boolean shouldRemove = false;
            // Debug

            if (entity == null) {
                shouldRemove = true; // エンティティが存在しない (デスポーンなど)
            } else if (!entity.isAlive()) {
                shouldRemove = true; // エンティティが死んでいる
            } else if (entity.distanceToSqr(player) > COMBAT_CHECK_RADIUS * COMBAT_CHECK_RADIUS) {
                shouldRemove = true; // プレイヤーから離れすぎた
            }
            if (shouldRemove) {
                iterator.remove(); // セットから削除
                // LOGGER.debug("Removing entity {} from combat state (Reason: {}).", entityId, removeReason); // Debug
            }
        }
        // if (removedAny) LOGGER.debug("Pruned combat set. Remaining IDs: {}", activeCombatEntityIds); // Debug

        // 4. 追跡中のエンティティが1体でも残っていれば、戦闘状態はアクティブ


        return !activeCombatEntityIds.isEmpty();
    }


    // ★ Helper: 村にいるか簡易判定 (方法2)
    private static boolean isInVillageHeuristic(LocalPlayer player, Level level) {
        double villageCheckRadius = 48.0; // 村の判定範囲
        BlockPos playerPos = player.blockPosition();
        int searchHeightRange = 10; // 上下方向の検索範囲

        // 1. 鐘(Bell)があるかチェック (比較的軽量)
        boolean bellFound = BlockPos.betweenClosedStream(
                        playerPos.offset(- (int) villageCheckRadius, -searchHeightRange, - (int) villageCheckRadius),
                        playerPos.offset((int) villageCheckRadius, searchHeightRange, (int) villageCheckRadius))
                .anyMatch(pos -> level.getBlockState(pos.immutable()).is(Blocks.BELL)); // immutable()で安全に

        if (bellFound) {
            return true;
        }

        // 2. 村人(Villager)が一定数以上いるかチェック (エンティティ検索はやや重い)
        List<Villager> nearbyVillagers = level.getEntitiesOfClass(
                Villager.class,
                player.getBoundingBox().inflate(villageCheckRadius), // 判定範囲
                LivingEntity::isAlive
        );

        int villagerThreshold = 2; // 村と判定する村人の最低人数
        return nearbyVillagers.size() >= villagerThreshold;
    }

    // 音楽停止処理 (lastPlayedSoundEvent もリセット)
    private static void stopCurrentMusic() {
        if (currentMusic != null) {
            Minecraft.getInstance().getSoundManager().stop(currentMusic);
            // LOGGER.debug("Stopped music: {}", currentMusic.getLocation());
        }
        currentMusic = null;
        lastPlayedSoundEvent = null; // ★再生が止まったら、最後に再生した記録も消す
    }
}