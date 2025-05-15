package com.zoma1101.music_player; // パッケージは合わせてください

import com.zoma1101.music_player.config.SoundDefinition;
import com.zoma1101.music_player.config.SoundPackLoader; // クラス名が異なる場合は修正
import com.mojang.logging.LogUtils;
import net.minecraft.ResourceLocationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

import static com.zoma1101.music_player.config.ClientConfig.isOverride_BGM;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientMusicManager { // クラス名を変更

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 20; // 1秒ごとにチェック

    // --- 状態管理フィールド ---
    @Nullable
    private static SoundInstance currentMusicInstance = null; // 現在再生中の SoundInstance
    @Nullable
    private static ResourceLocation currentMusicLocation = null; // 現在再生されているべき曲の ResourceLocation
    private static boolean isStopping = false; // stopMusic 直後に再開しようとするのを防ぐフラグ

    // --- キャッシュ ---
    @Nullable
    private static SoundEvent defaultMusicSoundEvent = null; // デフォルトBGM

    // --- 条件判定用 ---
    private static final Set<Integer> activeCombatEntityIds = new HashSet<>();
    private static final double COMBAT_CHECK_RADIUS = 24.0;
    private static final double VILLAGE_CHECK_RADIUS = 48.0;
    private static final int VILLAGE_CHECK_HEIGHT = 10;
    private static final int VILLAGER_THRESHOLD = 2;

    /**
     * ClientSetup イベントで呼び出す初期化メソッド
     */
    public static void initialize() {
        LOGGER.info("Initializing ClientMusicManager for {}", Music_Player.MOD_ID);
        try {
            LOGGER.info("Default music event cached: {}", Objects.requireNonNull(defaultMusicSoundEvent).getLocation());
        } catch (Exception e) {
            LOGGER.error("Failed to get default music sound event!", e);
            defaultMusicSoundEvent = null;
        }
    }

    // --- イベントハンドラ ---

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) {
                updateMusic();
            }
            // 停止フラグは Tick 終了時にリセット
            if (isStopping) {
                isStopping = false;
                // LOGGER.trace("Reset 'isStopping' flag."); // 必要ならログ出力
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player logged in. Resetting music state.");
        stopMusic(false); // 既存の音楽を停止 (停止フラグは立てない)
        currentMusicLocation = null; // 意図する音楽もない状態に
        activeCombatEntityIds.clear(); // 戦闘状態クリア
        updateMusic(); // 初回チェック実行
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player logged out. Stopping music.");
        stopMusic(false); // 既存の音楽を停止 (停止フラグは立てない)
        currentMusicLocation = null; // 意図する音楽もない状態に
        activeCombatEntityIds.clear(); // 戦闘状態クリア
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance soundBeingPlayed = event.getSound(); // イベントで再生されようとしているサウンド
        if (soundBeingPlayed == null) {
            return;
        }
        ResourceLocation playingLocation = soundBeingPlayed.getLocation();
        SoundSource soundSource = soundBeingPlayed.getSource();
        if (SoundSource.MUSIC.equals(soundSource)) {
            String namespace = playingLocation.getNamespace();

            // バニラのBGMかチェック
            boolean isVanillaBackgroundMusic = "minecraft".equals(namespace) && playingLocation.getPath().startsWith("music.");
            // Mod自身のカスタムBGMかチェック
            boolean isOurCustomMusic = "music_player_soundpacks".equals(namespace);
            if (currentMusicLocation != null) {
                // 1. 再生されようとしているのがバニラのBGMのとき
                if (isVanillaBackgroundMusic) {
                    if (!playingLocation.equals(currentMusicLocation)) {
                        event.setSound(null); // バニラのBGM再生イベントをキャンセル
                    }
                }
                else if (!isOurCustomMusic) {
                    if (isOverride_BGM.get()) {
                        LOGGER.info("Detected background music from another mod: {}. Our mod is configured to override, stopping our music.", playingLocation);
                        stopMusic(true);
                    }
                    else {
                        event.setSound(null);
                    }
                }
            }
        }
    }


    /**
     * 現在の状況に基づいて再生すべき音楽を判断し、必要であれば再生・停止を行う
     */
    private static void updateMusic() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null) {
            if (currentMusicInstance != null) {
                LOGGER.warn("Player or Level became null, stopping music.");
                stopMusic(true); // 次の Tick で再開しないようにフラグを立てる
            }
            return;
        }

        // 直前に停止したばかりなら、この Tick では何もしない
        if (isStopping) {
            LOGGER.trace("Music stopping is in progress, skipping music update check.");
            return;
        }

        // 1. 現在の状況を取得
        CurrentContext context = getCurrentContext(player, level, mc.screen);

        // 2. 最適な SoundDefinition を検索
        List<SoundDefinition> definitions = SoundPackLoader.getLoadedDefinitions();
        SoundDefinition bestMatch = findBestMatch(definitions, context);

        // 3. 再生すべきターゲットの ResourceLocation を決定
        ResourceLocation targetLocation = null;
        String reason;
        if (bestMatch != null && bestMatch.soundEventLocation != null) {
            targetLocation = bestMatch.soundEventLocation;
            reason = "SoundPack: " + bestMatch.soundPackId + "/" + bestMatch.musicPath + " (Prio:" + bestMatch.priority + ")";
        } else if (defaultMusicSoundEvent != null) {
            targetLocation = defaultMusicSoundEvent.getLocation();
            reason = "Default Music";
        } else {
            reason = "No Match & No Default";
            // 再生すべきものがない
        }

        // 4. 現在の意図された曲 (currentMusicLocation) と比較して変更が必要か判断
        if (!Objects.equals(targetLocation, currentMusicLocation)) {
            LOGGER.info("Music change detected. Current Target: [{}], New Target: [{}]. Reason: [{}].",
                    currentMusicLocation, targetLocation, reason);

            // まず現在の音楽を停止する（必要ならフラグを立てる）
            stopMusic(true);

            // 新しいターゲットがあれば再生を試みる
            if (targetLocation != null) {
                playMusic(targetLocation);
            }
            // 最後に、現在の意図（ターゲット）を更新
            currentMusicLocation = targetLocation;

        } else {
            // ターゲットは同じだが、再生状態が意図と合っているか確認
            SoundManager soundManager = mc.getSoundManager();
            boolean shouldBePlaying = (currentMusicLocation != null);
            boolean isActuallyPlaying = (currentMusicInstance != null && soundManager.isActive(currentMusicInstance));

            if (shouldBePlaying && !isActuallyPlaying) {
                // 再生されているべきなのにされていない -> 再生試行
                LOGGER.warn("Music [{}] should be playing but isn't. Attempting to restart.", currentMusicLocation);
                playMusic(currentMusicLocation); // stopMusic は呼ばないので isStopping は false のまま
            } else if (!shouldBePlaying && isActuallyPlaying) {
                // 再生されているべきでないのにされている -> 停止
                LOGGER.warn("Music should NOT be playing, but instance [{}] is active. Stopping.", currentMusicInstance.getLocation());
                stopMusic(true); // 停止フラグを立てる
                currentMusicLocation = null; // ターゲットも null にしておく
            } else {
                LOGGER.trace("Music check: Target [{}] matches current state. No change needed.", targetLocation);
            }
        }
    }

    // --- ヘルパーメソッド ---

    /**
     * 指定された ResourceLocation の音楽再生を試みる
     * @param location 再生するサウンドイベントの ResourceLocation
     */
    private static void playMusic(ResourceLocation location) {
        if (isStopping) {
            return; // 停止処理中なら再生しない
        }
        if (location == null) {
            LOGGER.warn("playMusic called with null location.");
            return;
        }

        try {
            // ResourceLocation から直接 SimpleSoundInstance を作成
            currentMusicInstance = new SimpleSoundInstance(
                    location, SoundSource.MUSIC,
                    1.0f, 1.0f, SoundInstance.createUnseededRandom(),
                    true, 0, SoundInstance.Attenuation.NONE,
                    0.0D, 0.0D, 0.0D, true
            );
            Minecraft.getInstance().getSoundManager().play(currentMusicInstance);

        } catch (Exception e) {
            LOGGER.error("Exception occurred trying to play music [{}]: {}", location, e.getMessage(), e);
            currentMusicInstance = null; // エラー時はインスタンスをクリア
        }
    }


    private static void stopMusic(boolean setStoppingFlag) {
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (currentMusicInstance != null) {
            ResourceLocation stoppedLocation = currentMusicInstance.getLocation();
            LOGGER.debug("Stopping music instance: {}", stoppedLocation);
            soundManager.stop(currentMusicInstance);
            currentMusicInstance = null;
        }
        // 意図的な停止の場合、次の Tick での即時再開を防ぐ
        if (setStoppingFlag) {
            isStopping = true;
        }
    }

    @Nullable
    private static SoundDefinition findBestMatch(List<SoundDefinition> definitions, CurrentContext context) {
        SoundDefinition bestMatch = null;
        int highestPriority = Integer.MIN_VALUE; // 最小値から比較開始

        if (definitions == null) return null; // Loader が準備できていない場合

        for (SoundDefinition definition : definitions) {
            if (definition == null || !definition.isValid() || definition.soundEventLocation == null) continue; // 無効な定義はスキップ

            if (definition.priority > highestPriority && doesDefinitionMatch(definition, context)) {
                highestPriority = definition.priority;
                bestMatch = definition;
            }
        }
        return bestMatch;
    }

    private static boolean doesDefinitionMatch(SoundDefinition definition, CurrentContext context) {
        // このメソッドは、definition の条件が context にすべて一致すれば true を返す
        // 1つでも一致しない条件があれば false を返す

        try {
            // --- Biome Check (タグ対応版に置き換え) ---
            if (definition.biomes != null && !definition.biomes.isEmpty()) {
                // 現在のバイオーム情報がなければマッチしない
                if (context.biomeHolder == null || context.biomeHolder.is(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("empty")))) { // biomeHolder が有効かチェック
                    LOGGER.trace("Failed biome check: Current biome holder is null or empty for {}", definition.soundEventLocation);
                    return false;
                }

                boolean biomeMatchFound = false; // リスト内のいずれかにマッチしたかを示すフラグ
                for (String requiredBiomeOrTag : definition.biomes) {
                    if (requiredBiomeOrTag == null || requiredBiomeOrTag.isBlank()) continue; // 無効なエントリは無視

                    if (requiredBiomeOrTag.startsWith("#")) {
                        // --- タグ指定の場合 ---
                        try {
                            ResourceLocation tagRL = ResourceLocation.parse(requiredBiomeOrTag.substring(1));
                            TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagRL);
                            if (context.biomeHolder.is(tagKey)) {
                                biomeMatchFound = true;
                                LOGGER.trace("Biome tag match: {} is in tag {}", context.biomeHolder.unwrapKey().map(k->k.location().toString()).orElse("?"), tagKey.location());
                                break; // 一致が見つかったのでループ終了
                            }
                        } catch (ResourceLocationException e) {
                            LOGGER.warn("Invalid biome tag format in definition [{}]: '{}' - {}",
                                    definition.soundEventLocation != null ? definition.soundEventLocation : definition.musicPath,
                                    requiredBiomeOrTag, e.getMessage());
                        }
                    } else {
                        // --- ID指定の場合 ---
                        Optional<ResourceKey<Biome>> currentKeyOpt = context.biomeHolder.unwrapKey();
                        if (currentKeyOpt.isPresent() && currentKeyOpt.get().location().toString().equals(requiredBiomeOrTag)) {
                            biomeMatchFound = true;
                            LOGGER.trace("Biome ID match: {} == {}", currentKeyOpt.get().location(), requiredBiomeOrTag);
                            break; // 一致が見つかったのでループ終了
                        }
                    }
                } // end for loop

                // ループ後、一致が一つも見つからなければ false
                if (!biomeMatchFound) {
                    LOGGER.trace("Failed biome check: Current biome [{}] did not match any in list {}. Def: {}",
                            context.biomeHolder.unwrapKey().map(k->k.location().toString()).orElse("unknown"),
                            definition.biomes,
                            definition.soundEventLocation != null ? definition.soundEventLocation : definition.musicPath);
                    return false;
                }
            } // end biome check block

            // --- Altitude Check (変更なし) ---
            if (definition.minY != null && context.altitude < definition.minY) {
                LOGGER.trace("Failed minY check: required={}, current={}", definition.minY, context.altitude);
                return false;
            }
            if (definition.maxY != null && context.altitude > definition.maxY) {
                LOGGER.trace("Failed maxY check: required={}, current={}", definition.maxY, context.altitude);
                return false;
            }

            // --- isNight Check (変更なし) ---
            if (definition.isNight != null && definition.isNight != context.isNight) {
                LOGGER.trace("Failed isNight check: required={}, current={}", definition.isNight, context.isNight);
                return false;
            }

            // --- inCombat Check (変更なし) ---
            if (definition.isCombat != null && definition.isCombat != context.isInCombat) {
                LOGGER.trace("Failed isCombat check: required={}, current={}", definition.isCombat, context.isInCombat);
                return false;
            }

            // --- GUI Check (isMatch ヘルパーを使わず、インラインで実装) ---
            if (definition.guiScreen != null && !definition.guiScreen.isBlank()) {
                boolean guiMatch = false;
                String currentGuiClassName = (context.currentGui != null) ? context.currentGui.getClass().getName() : null;
                String currentGuiSimpleName = (context.currentGui != null) ? context.currentGui.getClass().getSimpleName() : null;
                String requiredGui = definition.guiScreen.trim();

                // チェックロジック (以前の回答と同じ)
                if (requiredGui.equals(currentGuiClassName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase(currentGuiSimpleName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("crafting") && context.currentGui instanceof CraftingScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("brewing_stand") && context.currentGui instanceof BrewingStandScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("shulker_box") && context.currentGui instanceof ShulkerBoxScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("furnace") && context.currentGui instanceof FurnaceScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("anvil") && context.currentGui instanceof AnvilScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("enchantment") && context.currentGui instanceof EnchantmentScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("cartographytable") && context.currentGui instanceof CartographyTableScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("smithing") && context.currentGui instanceof SmithingScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("merchant") && context.currentGui instanceof MerchantScreen) guiMatch = true;
                else if ((requiredGui.equalsIgnoreCase("null") || requiredGui.equalsIgnoreCase("none")) && context.currentGui == null) guiMatch = true;

                if (!guiMatch) {
                    LOGGER.trace("Failed guiScreen check: required='{}', current='{}'", requiredGui, currentGuiClassName);
                    return false; // GUI 条件にマッチしなかったら false
                }
            }

            // --- inVillage Check (形式を他のチェックに合わせる) ---
            if (definition.isVillage != null && definition.isVillage != context.isInVillage) {
                LOGGER.trace("Failed isVillage check: required={}, current={}", definition.isVillage, context.isInVillage);
                return false; // 村条件にマッチしなかったら false
            }
            if (definition.weather != null && !definition.weather.isEmpty()) {
                boolean weatherMatchFound = false;
                // isThundering は isRaining を包含することが多いので、雷雨を先にチェック
                boolean currentlyThundering = context.isThundering;
                // 雷雨でない雨 (isRaining=true かつ isThundering=false)
                boolean currentlyRainingOnly = context.isRaining && !context.isThundering;
                // 晴れ (雨でも雷雨でもない)
                boolean currentlyClear = !context.isRaining && !context.isThundering;

                for (String requiredWeather : definition.weather) {
                    if (requiredWeather == null) continue;
                    switch (requiredWeather.toLowerCase()) { // 小文字で比較
                        case "clear":
                            if (currentlyClear) weatherMatchFound = true;
                            break;
                        case "rain":
                            if (currentlyRainingOnly) weatherMatchFound = true;
                            break;
                        case "thunder":
                            if (currentlyThundering) weatherMatchFound = true;
                            break;
                        default:
                            LOGGER.warn("Unknown weather condition '{}' in definition [{}]", requiredWeather, definition.soundEventLocation);
                            break; // 不明な指定は無視
                    }
                    if (weatherMatchFound) {
                        LOGGER.trace("Weather match: current(R={}, T={}) matched '{}'", context.isRaining, context.isThundering, requiredWeather);
                        break; // 一致が見つかればループ終了
                    }
                }
                // ループ後、一致がなければ false
                if (!weatherMatchFound) {
                    LOGGER.trace("Failed weather check: current(R={}, T={}) did not match any in {}. Def: {}", context.isRaining, context.isThundering, definition.weather, definition.soundEventLocation);
                    return false;
                }
            }

            if (definition.dimensions != null && !definition.dimensions.isEmpty()) {
                // 現在のディメンションIDが null でなければチェック
                if (context.dimensionId != null) {
                    String currentDimensionIdStr = context.dimensionId.toString();
                    boolean dimensionMatchFound = false;
                    for (String requiredDimension : definition.dimensions) {
                        if (currentDimensionIdStr.equals(requiredDimension)) {
                            dimensionMatchFound = true;
                            LOGGER.trace("Dimension match: {} == {}", currentDimensionIdStr, requiredDimension);
                            break; // 一致が見つかればループ終了
                        }
                    }
                    // リスト内に現在のディメンションが含まれていなければ false
                    if (!dimensionMatchFound) {
                        LOGGER.trace("Failed dimension check: current {} not in required list {}. Def: {}", currentDimensionIdStr, definition.dimensions, definition.soundEventLocation);
                        return false;
                    }
                } else {
                    // 現在のディメンションIDが不明な場合は、ディメンション条件があれば常に false とする
                    LOGGER.trace("Failed dimension check: current dimension ID is null. Def: {}", definition.soundEventLocation);
                    return false;
                }
            }
            // すべての定義済み条件チェックを通過した (または条件が指定されていなかった)
            LOGGER.trace("Definition conditions MET for: {}", definition.soundEventLocation != null ? definition.soundEventLocation : definition.musicPath);
            return true; // すべて OK なら true を返す

        } catch (Exception e) {
            LOGGER.error("Error checking conditions for definition [{}]: {}",
                    definition.soundEventLocation != null ? definition.soundEventLocation : definition.musicPath,
                    e.getMessage(), e);
            return false; // 例外発生時は false
        }
    }


    private static CurrentContext getCurrentContext(LocalPlayer player, Level level, @Nullable Screen screen) {
        CurrentContext context = new CurrentContext();
        context.dimension = level.dimension();
        // ★ level.getBiome() で Holder<Biome> を直接取得 ★
        context.biomeHolder = level.getBiome(player.blockPosition());
        context.time = level.getDayTime() % 24000;
        context.isRaining = level.isRaining();
        context.isThundering = level.isThundering();
        context.altitude = player.getY();
        context.isInCombat = updateCombatStateAndCheck(player, level);
        context.currentGui = screen;
        context.isInVillage = isInVillageHeuristic(player, level);
        context.isNight = context.time >= 13000 && context.time < 23000;
        context.dimensionId = level.dimension().location(); // ResourceKey から ResourceLocation を取得

        return context;
    }

    // --- 戦闘・村判定ヘルパー (内容は以前と同じ) ---
    private static boolean updateCombatStateAndCheck(LocalPlayer player, Level level) {
        // ... (実装は省略、以前のコードと同じ) ...
        Set<Integer> currentlyAggressiveIds = new HashSet<>();
        List<Mob> nearbyMobs = level.getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(COMBAT_CHECK_RADIUS),
                LivingEntity::isAlive);
        for (Mob mob : nearbyMobs) { if (mob.isAggressive()) { currentlyAggressiveIds.add(mob.getId()); } }
        if (!currentlyAggressiveIds.isEmpty()) { activeCombatEntityIds.addAll(currentlyAggressiveIds); }
        Iterator<Integer> iterator = activeCombatEntityIds.iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            Entity entity = level.getEntity(entityId);
            if (entity == null || !entity.isAlive() || entity.distanceToSqr(player) > COMBAT_CHECK_RADIUS * COMBAT_CHECK_RADIUS) { iterator.remove(); }
        }
        return !activeCombatEntityIds.isEmpty();
    }
    private static boolean isInVillageHeuristic(LocalPlayer player, Level level) {
        // ... (実装は省略、以前のコードと同じ) ...
        BlockPos playerPos = player.blockPosition();
        boolean bellFound = BlockPos.betweenClosedStream( playerPos.offset(-(int) VILLAGE_CHECK_RADIUS, -VILLAGE_CHECK_HEIGHT, -(int) VILLAGE_CHECK_RADIUS), playerPos.offset((int) VILLAGE_CHECK_RADIUS, VILLAGE_CHECK_HEIGHT, (int) VILLAGE_CHECK_RADIUS)).anyMatch(pos -> level.getBlockState(pos.immutable()).is(Blocks.BELL));
        if (bellFound) return true;
        List<Villager> nearbyVillagers = level.getEntitiesOfClass( Villager.class, player.getBoundingBox().inflate(VILLAGE_CHECK_RADIUS), LivingEntity::isAlive);
        return nearbyVillagers.size() >= VILLAGER_THRESHOLD;
    }

    // --- コンテキスト保持用クラス ---
    private static class CurrentContext {
        ResourceKey<Level> dimension;
        // @Nullable ResourceKey<Biome> biome; // ← ResourceKey は Holder から取得できるので不要かも
        @Nullable
        Holder<Biome> biomeHolder; // ★ Holder<Biome> を追加 ★
        long time;
        boolean isRaining;
        boolean isThundering;
        double altitude;
        boolean isInCombat;
        @Nullable Screen currentGui;
        boolean isInVillage;
        boolean isNight;
        @Nullable ResourceLocation dimensionId;
    }
}