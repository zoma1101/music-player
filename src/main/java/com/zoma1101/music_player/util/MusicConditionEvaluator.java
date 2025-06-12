package com.zoma1101.music_player.util;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.MusicDefinition;
import net.minecraft.ResourceLocationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;
// import java.util.stream.Collectors; // .toList() を使用する場合は不要な場合があるが、明示的に追加しても良い

public class MusicConditionEvaluator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MusicConditionEvaluator() {}

    public static CurrentContext getCurrentContext(@Nullable LocalPlayer player, @Nullable Level level, @Nullable Screen screen) {
        CurrentContext context = new CurrentContext();

        if (player == null || level == null) {
            LOGGER.trace("Player or Level is null, returning empty context.");
            return context;
        }

        context.dimension = level.dimension();
        context.biomeHolder = level.getBiome(player.blockPosition());
        context.time = level.getDayTime() % 24000;
        context.isRaining = level.isRaining();
        context.isThundering = level.isThundering();
        context.altitude = player.getY();
        context.isInCombat = GameContextHelper.updateCombatStateAndCheck(player, level);
        context.isInVillage = GameContextHelper.isInVillageHeuristic(player, level);
        context.currentGui = screen;
        context.isNight = context.time >= 13000 && context.time < 23000;
        context.dimensionId = level.dimension().location();

        return context;
    }

    public static boolean doesDefinitionMatch(MusicDefinition definition, CurrentContext context) {
        if (context == null) {
            LOGGER.trace("Context is null, cannot match definition.");
            return false;
        }
        String logDefId = definition.getSoundEventKey() != null ? definition.getSoundEventKey() : definition.getMusicFileInPack();

        try {
            // Biome Check
            if (definition.getBiomes() != null && !definition.getBiomes().isEmpty()) {
                if (context.biomeHolder == null || context.biomeHolder.is(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("empty")))) {
                    LOGGER.trace("Failed biome check: Current biome holder is null or empty for {}", logDefId);
                    return false;
                }
                boolean biomeMatchFound = false;
                for (String requiredBiomeOrTag : definition.getBiomes()) {
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

            // Altitude Check
            if (definition.getMinY() != null && context.altitude < definition.getMinY()) {
                LOGGER.trace("Failed minY check: required={}, current={}", definition.getMinY(), context.altitude);
                return false;
            }
            if (definition.getMaxY() != null && context.altitude > definition.getMaxY()) {
                LOGGER.trace("Failed maxY check: required={}, current={}", definition.getMaxY(), context.altitude);
                return false;
            }

            // isNight Check
            if (definition.isNight() != null && Boolean.TRUE.equals(definition.isNight()) != context.isNight) {
                LOGGER.trace("Failed isNight check: required={}, current={}", definition.isNight(), context.isNight);
                return false;
            }

            // inCombat Check
            if (definition.isCombat() != null && Boolean.TRUE.equals(definition.isCombat()) != context.isInCombat) {
                LOGGER.trace("Failed isCombat check: required={}, current={}", definition.isCombat(), context.isInCombat);
                return false;
            }

            // GUI Check
            if (definition.getGuiScreen() != null && !definition.getGuiScreen().isBlank()) {
                boolean guiMatch = false;
                String currentGuiClassName = (context.currentGui != null) ? context.currentGui.getClass().getName() : null;
                String currentGuiSimpleName = (context.currentGui != null) ? context.currentGui.getClass().getSimpleName() : null;
                String requiredGui = definition.getGuiScreen().trim();

                if (requiredGui.equals(currentGuiClassName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase(currentGuiSimpleName)) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("crafting") && context.currentGui instanceof CraftingScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("inventory") && context.currentGui instanceof InventoryScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("furnace") && context.currentGui instanceof FurnaceScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("brewing_stand") && context.currentGui instanceof BrewingStandScreen) guiMatch = true;
                else if (requiredGui.equalsIgnoreCase("chest") && (context.currentGui instanceof ContainerScreen || context.currentGui instanceof ShulkerBoxScreen)) guiMatch = true; // ContainerScreen は ChestScreen の親クラスの一つ
                else if (requiredGui.equalsIgnoreCase("creative") && context.currentGui instanceof CreativeModeInventoryScreen) guiMatch = true;
                else if ((requiredGui.equalsIgnoreCase("null") || requiredGui.equalsIgnoreCase("none")) && context.currentGui == null) guiMatch = true;

                if (!guiMatch) {
                    LOGGER.trace("Failed guiScreen check: required='{}', current='{}'", requiredGui, currentGuiClassName);
                    return false;
                }
            }

            // inVillage Check
            if (definition.isVillage() != null && Boolean.TRUE.equals(definition.isVillage()) != context.isInVillage) {
                LOGGER.trace("Failed isVillage check: required={}, current={}", definition.isVillage(), context.isInVillage);
                return false;
            }

            // Weather Check
            if (definition.getWeather() != null && !definition.getWeather().isEmpty()) {
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

            // Dimension Check
            if (definition.getDimensions() != null && !definition.getDimensions().isEmpty()) {
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

            // Entity Conditions Check
            List<String> entityConditions = definition.getEntityConditions();
            Double radius = definition.getRadius();
            Integer minCount = definition.getMinCount();
            Integer maxCount = definition.getMaxCount();

            if (entityConditions != null && !entityConditions.isEmpty()) {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer player = mc.player;
                Level level = mc.level;

                if (player == null || level == null || radius == null || radius <= 0) {
                    LOGGER.warn("Skipping entity condition check due to invalid state (player/level null or radius <= 0) for {}", logDefId);
                    return false;
                }

                List<String> includeConditions = entityConditions.stream()
                        .filter(s -> s != null && !s.startsWith("!"))
                        .toList(); // Java 16+
                List<String> excludeConditions = entityConditions.stream()
                        .filter(s -> s != null && s.startsWith("!"))
                        .map(s -> s.substring(1))
                        .toList(); // Java 16+

                Predicate<EntityType<?>> typeMatchesInclude = type -> {
                    if (includeConditions.isEmpty()) return true; // No specific entities to include, so all pass this part of the check (exclusion still applies)
                    ResourceLocation typeRL = EntityType.getKey(type);

                    for (String includeIdOrTag : includeConditions) {
                        if (includeIdOrTag.startsWith("#")) {
                            try {
                                ResourceLocation tagRL = ResourceLocation.parse(includeIdOrTag.substring(1));
                                TagKey<EntityType<?>> tagKey = TagKey.create(Registries.ENTITY_TYPE, tagRL);
                                if (type.is(tagKey)) return true;
                            } catch (ResourceLocationException e) {
                                LOGGER.warn("Invalid include entity tag format in definition [{}]: '{}'", logDefId, includeIdOrTag);
                            }
                        } else {
                            try {
                                ResourceLocation idRL = ResourceLocation.parse(includeIdOrTag);
                                if (typeRL.equals(idRL)) return true;
                            } catch (ResourceLocationException e) {
                                LOGGER.warn("Invalid include entity ID format in definition [{}]: '{}'", logDefId, includeIdOrTag);
                            }
                        }
                    }
                    return false;
                };

                Predicate<EntityType<?>> typeMatchesExclude = type -> {
                    if (excludeConditions.isEmpty()) return false; // No entities to exclude
                    ResourceLocation typeRL = EntityType.getKey(type);

                    for (String excludeIdOrTag : excludeConditions) {
                        // excludeIdOrTag already has "!" removed
                        if (excludeIdOrTag.startsWith("#")) {
                            try {
                                ResourceLocation tagRL = ResourceLocation.parse(excludeIdOrTag.substring(1)); // Remove # for tag path
                                TagKey<EntityType<?>> tagKey = TagKey.create(Registries.ENTITY_TYPE, tagRL);
                                if (type.is(tagKey)) return true;
                            } catch (ResourceLocationException e) {
                                LOGGER.warn("Invalid exclude entity tag format in definition [{}]: '{}'", logDefId, excludeIdOrTag);
                            }
                        } else {
                            try {
                                ResourceLocation idRL = ResourceLocation.parse(excludeIdOrTag);
                                if (typeRL.equals(idRL)) return true;
                            } catch (ResourceLocationException e) {
                                LOGGER.warn("Invalid exclude entity ID format in definition [{}]: '{}'", logDefId, excludeIdOrTag);
                            }
                        }
                    }
                    return false;
                };
                List<Entity> entitiesInRadius = level.getEntities(
                        (Entity) null, // Search for all entity types
                        player.getBoundingBox().inflate(radius),
                        // ↓ LivingEntityのインスタンスであるかのみをチェック
                        entity -> entity instanceof net.minecraft.world.entity.LivingEntity
                );

                int count = 0;
                List<String> countedEntityInfo = new ArrayList<>(); // ★ログ出力用リスト

                for (Entity entity : entitiesInRadius) { // entitiesInRadius には LivingEntity のみが含まれる想定
                    // isAliveチェックはlevel.getEntitiesのフィルタで実施済み、またはここで追加する
                    if (!entity.isAlive()) { // LivingEntityであっても生存していなければスキップ
                        continue;
                    }

                    EntityType<?> entityType = entity.getType();
                    boolean matchesInclude = typeMatchesInclude.test(entityType);
                    boolean matchesExclude = typeMatchesExclude.test(entityType);

                    boolean shouldCount = matchesInclude && !matchesExclude;

                    if (shouldCount) {
                        count++;
                        // ★カウントされたエンティティの情報をリストに追加
                        ResourceLocation entityKey = EntityType.getKey(entityType);
                        String entityRegName = entityKey.toString();
                        countedEntityInfo.add(
                                String.format("%s (ID: %s, Pos: %s)",
                                        entity.getName().getString(),
                                        entityRegName,
                                        entity.blockPosition()
                                ));
                    }
                }

                // ★カウントされたエンティティの情報をログに出力 (TRACEレベル)
                if (!countedEntityInfo.isEmpty()) {
                    LOGGER.trace("[{}] Entities counted towards condition ({}): {}",
                            logDefId,
                            entityConditions, // どのentity_conditionsかを示す
                            countedEntityInfo
                    );
                } else if (count == 0 && !includeConditions.isEmpty()){ // 含むべきものが指定されているのに0件だった場合
                    LOGGER.trace("[{}] No entities matched the include/exclude criteria for conditions ({}). Found in radius: {}",
                            logDefId, entityConditions, entitiesInRadius.size());
                }


                boolean currentConditionMet = minCount == null || count >= minCount;

                if (currentConditionMet && maxCount != null && count > maxCount) {
                    currentConditionMet = false;
                }

                if (!currentConditionMet) {
                    LOGGER.trace("Failed entity condition for {}: Required Entities={}, Radius={}, RequiredMin={}, RequiredMax={}, FoundCount={}",
                            logDefId, entityConditions, radius,
                            minCount != null ? minCount : "N/A",
                            maxCount != null ? maxCount : "N/A",
                            count);
                    return false;
                } else {
                    // このログは、min/max count条件も満たした場合に出力される
                    LOGGER.trace("Entity condition MET for {}: Required Entities={}, Radius={}, RequiredMin={}, RequiredMax={}, FoundCount={}",
                            logDefId, entityConditions, radius,
                            minCount != null ? minCount : "N/A",
                            maxCount != null ? maxCount : "N/A",
                            count);
                }
            } else if (radius != null || minCount != null || maxCount != null) {
                // entityConditions がないのに radius や count が指定されているのは不正
                LOGGER.warn("Invalid entity condition configuration for {}: entity_conditions list is empty/null but radius/min/max is specified.", logDefId);
                return false;
            }

            LOGGER.trace("Definition conditions MET for: {}", logDefId);
            return true;

        } catch (Exception e) {
            LOGGER.error("Error checking conditions for definition [{}]: {}", logDefId, e.getMessage(), e);
            return false; // エラー発生時は条件不一致として扱う
        }
    }

    public static class CurrentContext {
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