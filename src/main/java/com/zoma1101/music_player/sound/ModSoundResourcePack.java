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
            LOGGER.info("[{}] Preparing Music Player sound pack data (prepare phase)...", packId);
            preparationsProfiler.pop();
        }, backgroundExecutor).thenCompose(stage::wait).thenRunAsync(() -> {
            reloadProfiler.push("MusicPlayerSoundPackReloadApply");
            LOGGER.info("[{}] Applying Music Player sound pack data (apply phase)...", packId);
            this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
            this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
            LOGGER.info("[{}] Applied new sound data. sounds.json length: {}, ogg files: {}",
                    packId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            if ("{}".equals(this.soundsJsonContent) && !this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] sounds.json is empty but OGG files were found. This might indicate an issue in sounds.json generation.", packId);
            }
            reloadProfiler.pop();
            LOGGER.info("[{}] Reload apply phase complete.", packId);
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

        final String currentPackId = this.packId(); // packId() を使うか、フィールドから取得

        if (!location.getNamespace().equals(Music_Player.MOD_ID)) {
            // このMODのネームスペースでなければ処理しない
            return null;
        }

        LOGGER.info("[{}] getResource - ENTRY for: {}", currentPackId, location);

        // 1. sounds.json の処理
        if (location.equals(SOUNDS_JSON_RL)) {
            LOGGER.info("[{}] getResource - Handling SOUNDS.JSON request for: {}", currentPackId, location);
            if ("{}".equals(this.soundsJsonContent)) {
                LOGGER.warn("[{}] getResource - SOUNDS.JSON was empty. FALLBACK: Regenerating data.", currentPackId);
                this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.info("[{}] getResource - FALLBACK COMPLETE: sounds.json length: {}, ogg files: {}",
                        currentPackId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            }
            try {
                Path debugFile = Paths.get("debug_sounds_provided_by_modsoundresourcepack.json");
                Files.writeString(debugFile, this.soundsJsonContent, StandardCharsets.UTF_8);
                LOGGER.info("[{}] Wrote current sounds.json content to {}", currentPackId, debugFile.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to write debug_sounds_provided_by_modsoundresourcepack.json", currentPackId, e);
            }
            return () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8));
        }

        // 2. OGGファイルのリクエストか判定
        if (location.getPath().startsWith("sounds/") && location.getPath().endsWith(".ogg")) {
            LOGGER.info("[{}] getResource - OGG REQUEST identified for: {}", currentPackId, location);
            if (this.oggResourceMap == null || this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is null or empty for {}. Attempting to re-populate.", currentPackId, location);
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.info("[{}] getResource - OGG REQUEST: oggResourceMap re-populated, new size: {}.", currentPackId, this.oggResourceMap.size());
            }

            if (this.oggResourceMap.containsKey(location)) {
                Path oggPath = this.oggResourceMap.get(location);
                LOGGER.info("[{}] getResource - OGG REQUEST: Key FOUND in oggResourceMap for {}. Path: {}", currentPackId, location, oggPath);
                if (Files.exists(oggPath) && Files.isRegularFile(oggPath)) {
                    LOGGER.info("[{}] getResource - OGG REQUEST: Providing file {} from {}", currentPackId, location, oggPath);
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
                LOGGER.warn("[{}] getResource - OGG REQUEST: Key NOT FOUND in oggResourceMap for {}. Map size: {}", currentPackId, location, this.oggResourceMap.size());
                if (!this.oggResourceMap.isEmpty()) {
                    LOGGER.warn("[{}] getResource - OGG REQUEST: Dumping oggResourceMap keys (first 10) for comparison with requested key <{}>:", currentPackId, location);
                    this.oggResourceMap.keySet().stream().limit(10).forEach(key -> LOGGER.warn("  - Map key: {}", key));
                }
                return null;
            }
        }

        // ★★★ ここから pack.png などのその他のリソースの処理を追加 ★★★
        // SoundPackManager での ResourceLocation 生成は `Music_Player.MOD_ID, packId + "/pack.png"`
        // なので、location.getPath() は "soundpack_id/pack.png" のような形式になる
        String pathInNamespace = location.getPath();
        // "soundpack_id/" の形式になっているか、または単純にファイル名かなどを考慮
        // ここでは、パスが "soundpack_id/actual_file_path_in_pack" の形式であることを期待する
        String[] parts = pathInNamespace.split("/", 2);
        if (parts.length == 2) {
            String packIdFromLocation = parts[0];
            String relativePathInPack = parts[1];

            // SoundPackManager.SOUNDPACKS_BASE_DIR を使って実際のファイルパスを構築
            Path soundPackDir = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(packIdFromLocation);
            Path actualFilePath = soundPackDir.resolve(relativePathInPack);

            if (Files.exists(actualFilePath) && Files.isRegularFile(actualFilePath)) {
                LOGGER.info("[{}] getResource - Providing pack resource: {} -> {}", currentPackId, location, actualFilePath);
                return () -> Files.newInputStream(actualFilePath);
            } else {
                LOGGER.warn("[{}] getResource - Pack resource file not found on disk: {} (Expected at: {})", currentPackId, location, actualFilePath);
            }
        }
        // ★★★ その他のリソースの処理ここまで ★★★

        LOGGER.warn("[{}] getResource - Resource in our namespace NOT HANDLED (not sounds.json, OGG, or known pack resource): {}", currentPackId, location);
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
            LOGGER.info("[{}] listResources - Query received. Namespace: '{}', Path: '{}'",
                    currentPackId, namespace, path);

            // 1. sounds.json のリストアップ
            if (path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path) || SOUNDS_JSON_RL.getPath().startsWith(path)) {
                if (!"{}".equals(this.soundsJsonContent) || path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path)) {
                    LOGGER.info("[{}] listResources - Attempting to list {} for path query '{}'. Current soundsJsonContent length: {}",
                            currentPackId, SOUNDS_JSON_RL, path, this.soundsJsonContent.length());
                    resourceOutput.accept(SOUNDS_JSON_RL, () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.info("[{}] listResources - Successfully listed {} for path query '{}'", currentPackId, SOUNDS_JSON_RL, path);
                }
            }

            // 2. OGG ファイルのリストアップ
            if (path.isEmpty() || path.equals("sounds") || path.startsWith("sounds/")) {
                Map<ResourceLocation, Path> managerMap = Music_Player.soundPackManager.getOggResourceMap();
                Map<ResourceLocation, Path> currentOggMapToUse = (managerMap != null && !managerMap.isEmpty()) ? managerMap : this.oggResourceMap;

                if (managerMap != null && !managerMap.isEmpty()) {
                    LOGGER.info("[{}] listResources - Fetched oggResourceMap from SoundPackManager for OGG listing (size {}).", currentPackId, currentOggMapToUse.size());
                } else {
                    LOGGER.warn("[{}] listResources - Using instance oggResourceMap for OGG listing (size {}), as manager's map was null or empty.", currentPackId, currentOggMapToUse.size());
                }

                for (Map.Entry<ResourceLocation, Path> entry : currentOggMapToUse.entrySet()) {
                    ResourceLocation fullOggRl = entry.getKey();
                    Path oggDiskPath = entry.getValue();

                    if (!fullOggRl.getNamespace().equals(Music_Player.MOD_ID)) {
                        continue;
                    }
                    if (fullOggRl.getPath().startsWith(path)) {
                        LOGGER.info("[{}] listResources - Listing OGG: {} (for query path '{}')", currentPackId, fullOggRl, path);
                        resourceOutput.accept(fullOggRl, () -> {
                            try {
                                if (Files.exists(oggDiskPath) && Files.isRegularFile(oggDiskPath)) {
                                    return Files.newInputStream(oggDiskPath);
                                } else {
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
            // TextureManager は通常 listResources を使わずに直接 getResource でテクスチャを取得するため、
            // ここでのリストアップは必須ではないかもしれませんが、完全性のために追加することも可能です。
            // SoundPackManager からロードされた全ての SoundPackInfo を取得し、
            // それぞれの iconLocation (pack.png の ResourceLocation) をリストに追加します。
            if (path.isEmpty() || path.contains("pack.png")) { // 簡単なフィルタリング例
                List<SoundPackInfo> loadedPacks = Music_Player.soundPackManager.getLoadedSoundPacks();
                for (SoundPackInfo packInfo : loadedPacks) {
                    ResourceLocation iconRl = packInfo.getIconLocation();
                    if (iconRl != null && iconRl.getNamespace().equals(Music_Player.MOD_ID) && iconRl.getPath().startsWith(path)) {
                        // iconRl.getPath() は "soundpack_id/pack.png"
                        String packIdFromIcon = packInfo.getId(); // packInfo.getId() を使用
                        Path iconDiskPath = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(packIdFromIcon).resolve("pack.png"); // pack.png と仮定

                        LOGGER.info("[{}] listResources - Listing Pack Icon: {} (for query path '{}')", currentPackId, iconRl, path);
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
            LOGGER.info("[{}] getNamespaces called for CLIENT_RESOURCES, returning namespace: {}", packId(), Music_Player.MOD_ID);
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