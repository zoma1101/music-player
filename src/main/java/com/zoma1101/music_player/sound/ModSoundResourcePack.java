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
    private final String packId; // This is the ID of this ModSoundResourcePack instance itself, e.g., "music_player_soundpacks"
    private String soundsJsonContent = "{}";
    private Map<ResourceLocation, Path> oggResourceMap = Collections.emptyMap();

    public static final ResourceLocation SOUNDS_JSON_RL = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, "sounds.json");
    // OGGリソースのResourceLocationパスのプレフィックス (SoundPackManagerと合わせる)
    private static final String OGG_RESOURCE_SOUNDS_PREFIX = "sounds/";


    public ModSoundResourcePack(String packId) {
        this.packId = packId;
        LOGGER.info("[{}] Initialized. Data will be loaded during reload.", packId);
    }

    @Override
    public @NotNull CompletableFuture<Void> reload(@NotNull PreparationBarrier stage, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller preparationsProfiler, @NotNull ProfilerFiller reloadProfiler, @NotNull Executor backgroundExecutor, @NotNull Executor gameExecutor) {
        LOGGER.info("[{}] Reload process started.", packId);
        return CompletableFuture.runAsync(() -> {
            preparationsProfiler.push("MusicPlayerSoundPackReloadPrepare");
            LOGGER.debug("[{}] Preparing Music Player sound pack data (prepare phase)...", packId);
            preparationsProfiler.pop();
        }, backgroundExecutor).thenCompose(stage::wait).thenRunAsync(() -> {
            reloadProfiler.push("MusicPlayerSoundPackReloadApply");
            LOGGER.debug("[{}] Applying Music Player sound pack data (apply phase)...", packId);
            this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
            this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
            LOGGER.info("[{}] Applied new sound data. sounds.json length: {}, ogg files: {}",
                    packId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            if ("{}".equals(this.soundsJsonContent) && !this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] sounds.json is empty but OGG files were found. This might indicate an issue in sounds.json generation.", packId);
            }
            reloadProfiler.pop();
            LOGGER.debug("[{}] Reload apply phase complete.", packId);
        }, gameExecutor);
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String @NotNull ... pathParts) {
        String joinedPath = String.join("/", pathParts);
        LOGGER.trace("[{}] getRootResource called for: {}", packId, joinedPath);
        // 通常、MODのリソースパックではルートリソースはあまり使われない
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(@NotNull PackType type, @NotNull ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }

        final String currentPackId = this.packId(); // MODリソースパック自体のID

        if (!location.getNamespace().equals(Music_Player.MOD_ID)) {
            // このリソースパックが扱う名前空間でなければ何もしない
            return null;
        }

        LOGGER.debug("[{}] getResource - ENTRY for: {}", currentPackId, location);

        // 1. sounds.json の処理
        if (location.equals(SOUNDS_JSON_RL)) {
            LOGGER.debug("[{}] getResource - Handling SOUNDS.JSON request for: {}", currentPackId, location);
            if ("{}".equals(this.soundsJsonContent)) {
                LOGGER.warn("[{}] getResource - SOUNDS.JSON was empty. FALLBACK: Regenerating data.", currentPackId);
                // リロードが間に合わなかった場合などのフォールバックとして再生成を試みる
                this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.debug("[{}] getResource - FALLBACK COMPLETE: sounds.json length: {}, ogg files: {}",
                        currentPackId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            }
            try {
                // デバッグ用に現在のsounds.jsonの内容をファイルに書き出す
                Path debugFile = Paths.get("debug_sounds_provided_by_modsoundresourcepack.json");
                Files.writeString(debugFile, this.soundsJsonContent, StandardCharsets.UTF_8);
                LOGGER.debug("[{}] Wrote current sounds.json content to {}", currentPackId, debugFile.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to write debug_sounds_provided_by_modsoundresourcepack.json", currentPackId, e);
            }
            return () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8));
        }

        // 2. OGGファイルのリクエストか判定
        // location.getPath() が "sounds/assetId/path/to/file.ogg" の形式になっているか確認
        if (location.getPath().startsWith(OGG_RESOURCE_SOUNDS_PREFIX) && location.getPath().endsWith(".ogg")) {
            LOGGER.debug("[{}] getResource - OGG REQUEST identified for: {}", currentPackId, location);
            if (this.oggResourceMap == null || this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is null or empty for {}. Attempting to re-populate.", currentPackId, location);
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap(); // 再取得を試みる
                LOGGER.debug("[{}] getResource - OGG REQUEST: oggResourceMap re-populated, new size: {}.", currentPackId, this.oggResourceMap.size());
            }

            if (this.oggResourceMap.containsKey(location)) {
                Path oggPath = this.oggResourceMap.get(location);
                LOGGER.debug("[{}] getResource - OGG REQUEST: Key FOUND in oggResourceMap for {}. Path: {}", currentPackId, location, oggPath);
                if (Files.exists(oggPath) && Files.isRegularFile(oggPath)) {
                    LOGGER.debug("[{}] getResource - OGG REQUEST: Providing file {} from {}", currentPackId, location, oggPath);
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
                LOGGER.debug("[{}] getResource - OGG REQUEST: Key NOT FOUND in oggResourceMap for {}. Map size: {}", currentPackId, location, this.oggResourceMap.size());
                if (!this.oggResourceMap.isEmpty()) {
                    LOGGER.trace("[{}] getResource - OGG REQUEST: Dumping oggResourceMap keys (first 10) for comparison with requested key <{}>:", currentPackId, location);
                    this.oggResourceMap.keySet().stream().limit(10).forEach(key -> LOGGER.trace("  - Map key: {}", key));
                }
                return null;
            }
        }

        // 3. pack.png (アイコン) の処理
        // SoundPackManagerで生成されたRLは music_player:<internalId>/pack.png の形式
        String requestedPath = location.getPath();
        if (requestedPath.endsWith("/pack.png")) {
            // パスから internalId を抽出 (例: "dq_music_v1.00/pack.png" -> "dq_music_v1.00")
            String packInternalIdFromRL = requestedPath.substring(0, requestedPath.lastIndexOf("/pack.png"));

            // internalId が空でないことを確認
            if (!packInternalIdFromRL.isEmpty() && !packInternalIdFromRL.contains("/")) { // internalId自体にスラッシュが含まれていないことを確認
                // packInternalIdFromRL を使って SoundPackInfo を検索
                SoundPackInfo packInfo = Music_Player.soundPackManager.getLoadedSoundPacks().stream()
                        .filter(spi -> spi.getId().equals(packInternalIdFromRL)) // getId() は internalId (dq_music_v1.00) を返す
                        .findFirst().orElse(null);

                if (packInfo != null) {
                    // 元のディレクトリ名 (例: "DQ Music v1.00") を取得
                    String actualPackDirectoryName = packInfo.getPackDirectory().getFileName().toString();
                    Path iconDiskPath = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(actualPackDirectoryName).resolve("pack.png");

                    LOGGER.debug("[{}] getResource - Pack Icon REQUEST identified for: {} (Derived InternalID: {}, Actual Dir: {}). Expected path: {}",
                            currentPackId, location, packInternalIdFromRL, actualPackDirectoryName, iconDiskPath);

                    if (Files.exists(iconDiskPath) && Files.isRegularFile(iconDiskPath)) {
                        LOGGER.debug("[{}] getResource - Pack Icon REQUEST: Providing file {} from {}",
                                currentPackId, location, iconDiskPath);
                        try {
                            return () -> Files.newInputStream(iconDiskPath);
                        } catch (Exception e) {
                            LOGGER.error("[{}] getResource - Pack Icon REQUEST: Error creating InputStream for {}: {}",
                                    currentPackId, iconDiskPath, e.getMessage(), e);
                            return null;
                        }
                    } else {
                        LOGGER.warn("[{}] getResource - Pack Icon file not found on disk: {} (Expected at: {})",
                                currentPackId, location, iconDiskPath);
                        return null;
                    }
                } else {
                    LOGGER.warn("[{}] getResource - Pack Icon REQUEST: Could not find SoundPackInfo for InternalID: {}", currentPackId, packInternalIdFromRL);
                }
            } else {
                LOGGER.warn("[{}] getResource - Pack Icon REQUEST: Could not derive a valid internalId from path: {}", currentPackId, requestedPath);
            }
        }

        LOGGER.debug("[{}] getResource - Resource in our namespace NOT HANDLED (not sounds.json, OGG, or pack icon): {}", currentPackId, location);
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
            LOGGER.debug("[{}] listResources - Query received. Namespace: '{}', Path: '{}'",
                    currentPackId, namespace, path);

            // 1. sounds.json のリストアップ
            if (path.isEmpty() || SOUNDS_JSON_RL.getPath().startsWith(path)) { // pathが空か、sounds.jsonのパスで始まる場合
                if (!"{}".equals(this.soundsJsonContent) || path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path)) {
                    LOGGER.debug("[{}] listResources - Attempting to list {} for path query '{}'. Current soundsJsonContent length: {}",
                            currentPackId, SOUNDS_JSON_RL, path, this.soundsJsonContent.length());
                    resourceOutput.accept(SOUNDS_JSON_RL, () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.debug("[{}] listResources - Successfully listed {} for path query '{}'", currentPackId, SOUNDS_JSON_RL, path);
                }
            }

            // 2. OGG ファイルのリストアップ
            // pathが空か、"sounds" または "sounds/" で始まる場合
            if (path.isEmpty() || path.equals(OGG_RESOURCE_SOUNDS_PREFIX.substring(0, OGG_RESOURCE_SOUNDS_PREFIX.length()-1)) || path.startsWith(OGG_RESOURCE_SOUNDS_PREFIX)) {
                Map<ResourceLocation, Path> managerMap = Music_Player.soundPackManager.getOggResourceMap();
                Map<ResourceLocation, Path> currentOggMapToUse = (managerMap != null && !managerMap.isEmpty()) ? managerMap : this.oggResourceMap;

                if (managerMap != null && !managerMap.isEmpty()) {
                    LOGGER.debug("[{}] listResources - Fetched oggResourceMap from SoundPackManager for OGG listing (size {}).", currentPackId, currentOggMapToUse.size());
                } else {
                    LOGGER.warn("[{}] listResources - Using instance oggResourceMap for OGG listing (size {}), as manager's map was null or empty.", currentPackId, currentOggMapToUse.size());
                }

                for (Map.Entry<ResourceLocation, Path> entry : currentOggMapToUse.entrySet()) {
                    ResourceLocation fullOggRl = entry.getKey();
                    Path oggDiskPath = entry.getValue();

                    if (!fullOggRl.getNamespace().equals(Music_Player.MOD_ID)) {
                        continue; // 念のため名前空間をチェック
                    }
                    // fullOggRl.getPath() がクエリパス path で始まる場合にリストアップ
                    if (fullOggRl.getPath().startsWith(path)) {
                        LOGGER.debug("[{}] listResources - Listing OGG: {} (for query path '{}')", currentPackId, fullOggRl, path);
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

            // 3. pack.png (アイコン) のリストアップ
            // SoundPackManagerで生成されたRLは music_player:<internalId>/pack.png
            List<SoundPackInfo> loadedPacks = Music_Player.soundPackManager.getLoadedSoundPacks();
            for (SoundPackInfo packInfo : loadedPacks) {
                ResourceLocation iconRl = packInfo.getIconLocation();
                if (iconRl != null && iconRl.getNamespace().equals(Music_Player.MOD_ID) && iconRl.getPath().startsWith(path)) {
                    // 元のディレクトリ名 (例: "DQ Music v1.00") を取得
                    String actualPackDirectoryName = packInfo.getPackDirectory().getFileName().toString();
                    Path iconDiskPath = SoundPackManager.SOUNDPACKS_BASE_DIR.resolve(actualPackDirectoryName).resolve("pack.png");

                    LOGGER.debug("[{}] listResources - Listing Pack Icon: {} (Actual Dir: {}, for query path '{}')",
                            currentPackId, iconRl, actualPackDirectoryName, path);
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

    @Override
    public @NotNull Set<String> getNamespaces(@NotNull PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            LOGGER.debug("[{}] getNamespaces called for CLIENT_RESOURCES, returning namespace: {}", packId(), Music_Player.MOD_ID);
            return ImmutableSet.of(Music_Player.MOD_ID);
        }
        LOGGER.trace("[{}] getNamespaces called for type {}, returning empty set.", packId(), type);
        return ImmutableSet.of();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(@NotNull MetadataSectionSerializer<T> deserializer) {
        LOGGER.trace("[{}] getMetadataSection called for {}", packId(), deserializer.getMetadataSectionName());
        // このリソースパックはpack.mcmetaを動的に提供しないため、nullを返す
        return null;
    }

    @Override
    public @NotNull String packId() {
        return this.packId;
    }

    @Override
    public boolean isBuiltin() {
        // このリソースパックはMODによって提供されるため、trueを返す
        return true;
    }

    @Override
    public void close() {
        // リソースを解放する必要があればここで行う (今回は特に不要)
    }
}