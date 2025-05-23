package com.zoma1101.music_player.util;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.MusicDefinition;
import net.minecraft.ResourceLocationException;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * サウンド再生条件の評価とゲーム状況の収集を行うクラス。
 */
public class MusicConditionEvaluator {

    // ロギングのためのLOGGERを新しく定義
    private static final Logger LOGGER = LogUtils.getLogger();

    // コンストラクタを隠蔽（静的メソッドのみを提供するため）
    private MusicConditionEvaluator() {}

    /**
     * 現在のプレイヤーとレベルの状況を収集し、CurrentContext オブジェクトを作成します。
     * @param player 判定対象のプレイヤー
     * @param level プレイヤーがいるレベル
     * @param screen 現在開いているGUIスクリーン
     * @return 現在の状況を表す CurrentContext オブジェクト
     */
    public static CurrentContext getCurrentContext(@Nullable LocalPlayer player, @Nullable Level level, @Nullable Screen screen) {
        CurrentContext context = new CurrentContext();

        // プレイヤーやレベルが null の場合に対応
        if (player == null || level == null) {
            LOGGER.trace("Player or Level is null, returning empty context.");
            // context のデフォルト値 (null や false) が設定される
            return context;
        }

        context.dimension = level.dimension(); // ディメンションの ResourceKey
        context.biomeHolder = level.getBiome(player.blockPosition()); // バイオーム Holder
        context.time = level.getDayTime() % 24000; // 昼夜サイクル時間 (0-23999)
        context.isRaining = level.isRaining(); // 雨が降っているか
        context.isThundering = level.isThundering(); // 雷雨か
        context.altitude = player.getY(); // プレイヤーのY座標

        // GameContextHelper の静的メソッドを呼び出して戦闘状態と村の状態を取得
        context.isInCombat = GameContextHelper.updateCombatStateAndCheck(player, level);
        context.isInVillage = GameContextHelper.isInVillageHeuristic(player, level);

        context.currentGui = screen; // 現在開いている GUI スクリーン
        context.isNight = context.time >= 13000 && context.time < 23000; // 時間帯が夜か (簡略判定)
        context.dimensionId = level.dimension().location(); // ディメンションの ResourceLocation

        return context;
    }


    /**
     * 指定された SoundDefinition の条件が、与えられた CurrentContext にすべて一致するかを判定します。
     * 条件が定義されていない場合は常に一致とみなされます。
     * @param definition 評価する SoundDefinition
     * @param context 現在のゲーム状況
     * @return 条件が一致すれば true、そうでなければ false
     */
    public static boolean doesDefinitionMatch(MusicDefinition definition, CurrentContext context) { // 型を変更
        if (context == null) {
            LOGGER.trace("Context is null, cannot match definition.");
            return false;
        }
        // soundEventLocation の代わりに soundEventKey や oggResourceLocation をログで使う
        String logDefId = definition.getSoundEventKey() != null ? definition.getSoundEventKey() : definition.getMusicFileInPack();

        try {
            // --- Biome Check ---
            if (definition.getBiomes() != null && !definition.getBiomes().isEmpty()) {
                if (context.biomeHolder == null || context.biomeHolder.is(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("empty")))) {
                    LOGGER.trace("Failed biome check: Current biome holder is null or empty for {}", logDefId);
                    return false;
                }
                // ... (definition.biomes を definition.getBiomes() に変更して同様のロジック) ...
                // ログ出力の definition.soundEventLocation を logDefId に変更
                boolean biomeMatchFound = false;
                for (String requiredBiomeOrTag : definition.getBiomes()) { // getterを使用
                    if (requiredBiomeOrTag == null || requiredBiomeOrTag.isBlank()) continue;

                    if (requiredBiomeOrTag.startsWith("#")) {
                        try {
                            ResourceLocation tagRL = ResourceLocation.parse(requiredBiomeOrTag.substring(1));
                            TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagRL);
                            if (context.biomeHolder.is(tagKey)) {
                                biomeMatchFound = true;
                                LOGGER.trace("Biome tag match: {} is in tag {}", context.biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("?"), tagKey.location());
                                break;
                            }
                        } catch (ResourceLocationException e) {
                            LOGGER.warn("Invalid biome tag format in definition [{}]: '{}' - {}", logDefId, requiredBiomeOrTag, e.getMessage());
                        }
                    } else {
                        Optional<ResourceKey<Biome>> currentKeyOpt = context.biomeHolder.unwrapKey();
                        if (currentKeyOpt.isPresent() && currentKeyOpt.get().location().toString().equals(requiredBiomeOrTag)) {
                            biomeMatchFound = true;
                            LOGGER.trace("Biome ID match: {} == {}", currentKeyOpt.get().location(), requiredBiomeOrTag);
                            break;
                        }
                    }
                }
                if (!biomeMatchFound) {
                    LOGGER.trace("Failed biome check: Current biome [{}] did not match any in list {}. Def: {}",
                            context.biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"),
                            definition.getBiomes(), logDefId);
                    return false;
                }
            }

            // --- Altitude Check ---
            if (definition.getMinY() != null && context.altitude < definition.getMinY()) { // getterを使用
                LOGGER.trace("Failed minY check: required={}, current={}", definition.getMinY(), context.altitude);
                return false;
            }
            if (definition.getMaxY() != null && context.altitude > definition.getMaxY()) { // getterを使用
                LOGGER.trace("Failed maxY check: required={}, current={}", definition.getMaxY(), context.altitude);
                return false;
            }

            // --- isNight Check ---
            if (definition.isNight() != null && Boolean.TRUE.equals(definition.isNight()) != context.isNight) { // getterを使用
                LOGGER.trace("Failed isNight check: required={}, current={}", definition.isNight(), context.isNight);
                return false;
            }

            // --- inCombat Check ---
            if (definition.isCombat() != null && Boolean.TRUE.equals(definition.isCombat()) != context.isInCombat) { // getterを使用
                LOGGER.trace("Failed isCombat check: required={}, current={}", definition.isCombat(), context.isInCombat);
                return false;
            }

            // --- GUI Check ---
            if (definition.getGuiScreen() != null && !definition.getGuiScreen().isBlank()) { // getterを使用
                // ... (definition.guiScreen を definition.getGuiScreen() に変更して同様のロジック) ...
                boolean guiMatch = false;
                String currentGuiClassName = (context.currentGui != null) ? context.currentGui.getClass().getName() : null;
                String currentGuiSimpleName = (context.currentGui != null) ? context.currentGui.getClass().getSimpleName() : null;
                String requiredGui = definition.getGuiScreen().trim();

                if (requiredGui.equals(currentGuiClassName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase(currentGuiSimpleName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("crafting") && context.currentGui instanceof CraftingScreen) guiMatch = true;
                    // ... (他のGUIタイプも同様に) ...
                else if ((requiredGui.equalsIgnoreCase("null") || requiredGui.equalsIgnoreCase("none")) && context.currentGui == null) guiMatch = true;

                if (!guiMatch) {
                    LOGGER.trace("Failed guiScreen check: required='{}', current='{}'", requiredGui, currentGuiClassName);
                    return false;
                }
            }

            // --- inVillage Check ---
            if (definition.isVillage() != null && Boolean.TRUE.equals(definition.isVillage()) != context.isInVillage) { // getterを使用
                LOGGER.trace("Failed isVillage check: required={}, current={}", definition.isVillage(), context.isInVillage);
                return false;
            }

            // --- Weather Check ---
            if (definition.getWeather() != null && !definition.getWeather().isEmpty()) { // getterを使用
                boolean weatherMatchFound = false;
                boolean currentlyThundering = context.isThundering;
                boolean currentlyRainingOnly = context.isRaining && !context.isThundering;
                boolean currentlyClear = !context.isRaining && !context.isThundering;

                for (String requiredWeather : definition.getWeather()) {
                    if (requiredWeather == null) continue;
                    switch (requiredWeather.toLowerCase(Locale.ROOT)) {
                        case "clear": if (currentlyClear) weatherMatchFound = true; break;
                        case "rain":  if (currentlyRainingOnly) weatherMatchFound = true; break;
                        case "thunder": if (currentlyThundering) weatherMatchFound = true; break;
                        default: LOGGER.warn("Unknown weather condition '{}' in definition [{}]", requiredWeather, logDefId); break;
                    }
                    if (weatherMatchFound) {
                        LOGGER.trace("Weather match: current(R={}, T={}) matched '{}'", context.isRaining, context.isThundering, requiredWeather);
                        break;
                    }
                }
                if (!weatherMatchFound) {
                    LOGGER.trace("Failed weather check: current(R={}, T={}) did not match any in {}. Def: {}", context.isRaining, context.isThundering, definition.getWeather(), logDefId);
                    return false;
                }
            }

            // --- Dimension Check ---
            if (definition.getDimensions() != null && !definition.getDimensions().isEmpty()) { // getterを使用
                if (context.dimensionId != null) {
                    String currentDimensionIdStr = context.dimensionId.toString();
                    boolean dimensionMatchFound = false;
                    for (String requiredDimension : definition.getDimensions()) {
                        if (currentDimensionIdStr.equals(requiredDimension)) {
                            dimensionMatchFound = true;
                            LOGGER.trace("Dimension match: {} == {}", currentDimensionIdStr, requiredDimension);
                            break;
                        }
                    }
                    if (!dimensionMatchFound) {
                        LOGGER.trace("Failed dimension check: current {} not in required list {}. Def: {}", currentDimensionIdStr, definition.getDimensions(), logDefId);
                        return false;
                    }
                } else {
                    LOGGER.trace("Failed dimension check: current dimension ID is null. Def: {}", logDefId);
                    return false;
                }
            }

            LOGGER.trace("Definition conditions MET for: {}", logDefId);
            return true;

        } catch (Exception e) {
            LOGGER.error("Error checking conditions for definition [{}]: {}", logDefId, e.getMessage(), e);
            return false;
        }
    }


    // --- コンテキスト保持用クラス ---
    // CurrentContext クラスを MusicConditionEvaluator クラス内に移動
    /**
     * ゲームの現在の状況を表すデータを保持する内部クラス。
     * ClientMusicManager によって収集され、MusicConditionEvaluator によって使用されます。
     */
    public static class CurrentContext { // public static にして外部からアクセス可能にする
        public ResourceKey<Level> dimension;
        @Nullable
        public Holder<Biome> biomeHolder;
        public long time;
        public boolean isRaining;
        public boolean isThundering;
        public double altitude;
        public boolean isInCombat;
        @Nullable
        public Screen currentGui;
        public boolean isInVillage;
        public boolean isNight;
        @Nullable
        public ResourceLocation dimensionId;

        public CurrentContext() {}
    }
}