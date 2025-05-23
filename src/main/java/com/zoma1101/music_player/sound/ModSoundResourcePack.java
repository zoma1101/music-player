package com.zoma1101.music_player.sound;

import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ModSoundResourcePack implements PackResources, PreparableReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String packId;
    private String soundsJsonContent = "{}";
    private Map<ResourceLocation, Path> oggResourceMap = Collections.emptyMap();

    public static final ResourceLocation SOUNDS_JSON_RL = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, "sounds.json");

    public ModSoundResourcePack(String packId) {
        this.packId = packId;
        LOGGER.info("[{}] Initialized. Data will be loaded during reload.", packId);
    }

    @Override
    public @NotNull CompletableFuture<Void> reload(@NotNull PreparationBarrier stage, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller preparationsProfiler, @NotNull ProfilerFiller reloadProfiler, @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor) {
        LOGGER.info("[{}] Reload process started.", packId);
        return CompletableFuture.runAsync(() -> {
            preparationsProfiler.push("MusicPlayerSoundPackReloadPrepare");
            LOGGER.debug("[{}] Preparing Music Player sound pack data (prepare phase)...", packId); // INFO -> DEBUG
            preparationsProfiler.pop();
        }, backgroundExecutor).thenCompose(stage::wait).thenRunAsync(() -> {
            reloadProfiler.push("MusicPlayerSoundPackReloadApply");
            LOGGER.debug("[{}] Applying Music Player sound pack data (apply phase)...", packId); // INFO -> DEBUG
            this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
            this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
            LOGGER.info("[{}] Applied new sound data. sounds.json length: {}, ogg files: {}",
                    packId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            if ("{}".equals(this.soundsJsonContent) && !this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] sounds.json is empty but OGG files were found. This might indicate an issue in sounds.json generation.", packId);
            }
            reloadProfiler.pop();
            LOGGER.debug("[{}] Reload apply phase complete.", packId); // INFO -> DEBUG
        }, gameExecutor);
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String @NotNull ... pathParts) {
        String joinedPath = String.join("/", pathParts);
        LOGGER.trace("[{}] getRootResource called for: {}", packId, joinedPath);
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(@NotNull PackType type, @NotNull ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }

        final String currentPackId = this.packId();

        if (!location.getNamespace().equals(Music_Player.MOD_ID)) {
            return null;
        }

        LOGGER.debug("[{}] getResource - ENTRY for: {}", currentPackId, location); // INFO -> DEBUG

        // 1. sounds.json の処理
        if (location.equals(SOUNDS_JSON_RL)) {
            LOGGER.debug("[{}] getResource - Handling SOUNDS.JSON request for: {}", currentPackId, location); // INFO -> DEBUG
            if ("{}".equals(this.soundsJsonContent)) {
                LOGGER.warn("[{}] getResource - SOUNDS.JSON was empty. FALLBACK: Regenerating data.", currentPackId);
                this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.debug("[{}] getResource - FALLBACK COMPLETE: sounds.json length: {}, ogg files: {}", // INFO -> DEBUG
                        currentPackId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            }
            try {
                // デバッグファイル書き出しは DEBUG レベルに
                LOGGER.debug("[{}] Writing current sounds.json content to debug file.", currentPackId); // INFO -> DEBUG
                Path debugFile = Paths.get("debug_sounds_provided_by_modsoundresourcepack.json");
                Files.writeString(debugFile, this.soundsJsonContent, StandardCharsets.UTF_8);
                LOGGER.debug("[{}] Wrote current sounds.json content to {}", currentPackId, debugFile.toAbsolutePath()); // INFO -> DEBUG
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to write debug_sounds_provided_by_modsoundresourcepack.json", currentPackId, e);
            }
            return () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8));
        }

        // 2. OGGファイルのリクエストか判定
        if (location.getPath().startsWith("sounds/") && location.getPath().endsWith(".ogg")) {
            LOGGER.debug("[{}] getResource - OGG REQUEST identified for: {}", currentPackId, location); // INFO -> DEBUG
            if (this.oggResourceMap == null || this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is null or empty for {}. Attempting to re-populate.", currentPackId, location);
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.debug("[{}] getResource - OGG REQUEST: oggResourceMap re-populated, new size: {}.", currentPackId, this.oggResourceMap.size()); // INFO -> DEBUG
            }

            if (this.oggResourceMap.containsKey(location)) {
                Path oggPath = this.oggResourceMap.get(location);
                LOGGER.debug("[{}] getResource - OGG REQUEST: Key FOUND in oggResourceMap for {}. Path: {}", currentPackId, location, oggPath); // INFO -> DEBUG
                if (Files.exists(oggPath) && Files.isRegularFile(oggPath)) {
                    LOGGER.debug("[{}] getResource - OGG REQUEST: Providing file {} from {}", currentPackId, location, oggPath); // INFO -> DEBUG
                    try {
                        return () -> Files.newInputStream(oggPath);
                    } catch (Exception e) {
                        LOGGER.error("[{}] getResource - OGG REQUEST: Error creating InputStream for {}: {}", currentPackId, oggPath, e.getMessage(), e);
                        return null;
                    }
                } else {
                    LOGGER.error("[{}] getResource - OGG REQUEST: File in map but NOT FOUND or not a file: {} (expected at {})", currentPackId, location, oggPath);
                    return null;
                }
            } else {
                LOGGER.debug("[{}] getResource - OGG REQUEST: Key NOT FOUND in oggResourceMap for {}. Map size: {}", currentPackId, location, this.oggResourceMap.size()); // WARN -> DEBUG (頻繁に発生するため)
                if (!this.oggResourceMap.isEmpty()) {
                    // マップの中身ダンプは TRACE レベルに
                    LOGGER.trace("[{}] getResource - OGG REQUEST: Dumping oggResourceMap keys (first 10) for comparison with requested key <{}>:", currentPackId, location);
                    this.oggResourceMap.keySet().stream().limit(10).forEach(key -> LOGGER.trace("  - Map key: {}", key));
                }
                return null;
            }
        }

        // ★★★ ここから pack.png などのその他のリソースの処理 ★★★
        String pathInNamespace = location.getPath();
        String[] parts = pathInNamespace.split("/", 2);
        if (parts.length == 2) {
            String packIdFromLocation = parts[0];
            String relativePathInPack = parts[1];

            Path soundPackDir = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(packIdFromLocation);
            Path actualFilePath = soundPackDir.resolve(relativePathInPack);

            if (Files.exists(actualFilePath) && Files.isRegularFile(actualFilePath)) {
                return () -> Files.newInputStream(actualFilePath);
            } else {
                // ファイルが見つからない場合は WARN のまま
                LOGGER.warn("[{}] getResource - Pack resource file not found on disk: {} (Expected at: {})", currentPackId, location, actualFilePath);
            }
        }
        // ★★★ その他のリソースの処理ここまで ★★★
        // 処理されなかったリソースリクエストは DEBUG レベルに
        LOGGER.debug("[{}] getResource - Resource in our namespace NOT HANDLED (not sounds.json, OGG, or known pack resource): {}", currentPackId, location); // WARN -> DEBUG
        return null;
    }

    @Override
    public void listResources(@NotNull PackType type, @NotNull String namespace,
                              @NotNull String path, @NotNull ResourceOutput resourceOutput) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }

        final String currentPackId = this.packId();

        if (namespace.equals(Music_Player.MOD_ID)) {
            LOGGER.debug("[{}] listResources - Query received. Namespace: '{}', Path: '{}'", // INFO -> DEBUG
                    currentPackId, namespace, path);

            // 1. sounds.json のリストアップ
            if (path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path) || SOUNDS_JSON_RL.getPath().startsWith(path)) {
                if (!"{}".equals(this.soundsJsonContent) || path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path)) {
                    LOGGER.debug("[{}] listResources - Attempting to list {} for path query '{}'. Current soundsJsonContent length: {}", // INFO -> DEBUG
                            currentPackId, SOUNDS_JSON_RL, path, this.soundsJsonContent.length());
                    resourceOutput.accept(SOUNDS_JSON_RL, () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.debug("[{}] listResources - Successfully listed {} for path query '{}'", currentPackId, SOUNDS_JSON_RL, path); // INFO -> DEBUG
                }
            }

            // 2. OGG ファイルのリストアップ
            if (path.isEmpty() || path.equals("sounds") || path.startsWith("sounds/")) {
                Map<ResourceLocation, Path> managerMap = Music_Player.soundPackManager.getOggResourceMap();
                Map<ResourceLocation, Path> currentOggMapToUse = (managerMap != null && !managerMap.isEmpty()) ? managerMap : this.oggResourceMap;

                if (managerMap != null && !managerMap.isEmpty()) {
                    LOGGER.debug("[{}] listResources - Fetched oggResourceMap from SoundPackManager for OGG listing (size {}).", currentPackId, currentOggMapToUse.size()); // INFO -> DEBUG
                } else {
                    // マネージャーのマップが空の場合は WARN のまま
                    LOGGER.warn("[{}] listResources - Using instance oggResourceMap for OGG listing (size {}), as manager's map was null or empty.", currentPackId, currentOggMapToUse.size());
                }

                for (Map.Entry<ResourceLocation, Path> entry : currentOggMapToUse.entrySet()) {
                    ResourceLocation fullOggRl = entry.getKey();
                    Path oggDiskPath = entry.getValue();

                    if (!fullOggRl.getNamespace().equals(Music_Player.MOD_ID)) {
                        continue;
                    }
                    if (fullOggRl.getPath().startsWith(path)) {
                        LOGGER.debug("[{}] listResources - Listing OGG: {} (for query path '{}')", currentPackId, fullOggRl, path); // INFO -> DEBUG
                        resourceOutput.accept(fullOggRl, () -> {
                            try {
                                if (Files.exists(oggDiskPath) && Files.isRegularFile(oggDiskPath)) {
                                    return Files.newInputStream(oggDiskPath);
                                } else {
                                    // ファイルが見つからない場合は ERROR
                                    LOGGER.error("[{}] listResources - Listed OGG file not found on disk: {} (expected at {})", currentPackId, fullOggRl, oggDiskPath);
                                    throw new FileNotFoundException("Listed OGG not found: " + oggDiskPath);
                                }
                            } catch (IOException e) {
                                LOGGER.error("[{}] listResources - IOException for OGG {}: {}", currentPackId, fullOggRl, e.getMessage());
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }

            // ★★★ 3. pack.png などのその他のリソースのリストアップ (任意、必要に応じて) ★★★
            if (path.isEmpty() || path.contains("pack.png")) {
                List<SoundPackInfo> loadedPacks = Music_Player.soundPackManager.getLoadedSoundPacks();
                for (SoundPackInfo packInfo : loadedPacks) {
                    ResourceLocation iconRl = packInfo.getIconLocation();
                    if (iconRl != null && iconRl.getNamespace().equals(Music_Player.MOD_ID) && iconRl.getPath().startsWith(path)) {
                        String packIdFromIcon = packInfo.getId();
                        Path iconDiskPath = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(packIdFromIcon).resolve("pack.png");

                        LOGGER.debug("[{}] listResources - Listing Pack Icon: {} (for query path '{}')", currentPackId, iconRl, path); // INFO -> DEBUG
                        resourceOutput.accept(iconRl, () -> {
                            try {
                                if (Files.exists(iconDiskPath) && Files.isRegularFile(iconDiskPath)) {
                                    return Files.newInputStream(iconDiskPath);
                                } else {
                                    LOGGER.error("[{}] listResources - Listed Pack Icon file not found on disk: {} (expected at {})", currentPackId, iconRl, iconDiskPath);
                                    throw new FileNotFoundException("Listed Pack Icon not found: " + iconDiskPath);
                                }
                            } catch (IOException e) {
                                LOGGER.error("[{}] listResources - IOException for Pack Icon {}: {}", currentPackId, iconRl, e.getMessage());
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }
        }
    }

    @Override
    public @NotNull Set<String> getNamespaces(@NotNull PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            LOGGER.debug("[{}] getNamespaces called for CLIENT_RESOURCES, returning namespace: {}", packId(), Music_Player.MOD_ID); // INFO -> DEBUG
            return ImmutableSet.of(Music_Player.MOD_ID);
        }
        LOGGER.trace("[{}] getNamespaces called for type {}, returning empty set.", packId(), type);
        return ImmutableSet.of();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(@NotNull MetadataSectionSerializer<T> deserializer) {
        LOGGER.trace("[{}] getMetadataSection called for {}", packId(), deserializer.getMetadataSectionName());
        return null;
    }

    @Override
    public @NotNull String packId() {
        return this.packId;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
        // No-op
    }
}