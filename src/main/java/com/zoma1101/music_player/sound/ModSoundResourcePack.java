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
        // 通常、動的リソースパックではあまり使用されません
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

        final String packId = this.packId(); // packId() を使うか、フィールドから取得

        // このMODの名前空間のリソースに対するリクエストか最初にログ出力
        if (location.getNamespace().equals(Music_Player.MOD_ID)) {
            LOGGER.info("[{}] getResource - ENTRY for: {}", packId, location);
        }

        if (location.equals(SOUNDS_JSON_RL)) {
            LOGGER.info("[{}] getResource - Handling SOUNDS.JSON request for: {}", packId, location);
            // フォールバックロジック (oggResourceMap.isEmpty() の条件を一時的に外して、sounds.jsonが空なら必ず再取得を試みる)
            if ("{}".equals(this.soundsJsonContent)) {
                LOGGER.warn("[{}] getResource - SOUNDS.JSON was empty. FALLBACK: Regenerating data.", packId);
                this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap(); // マップを再取得
                LOGGER.info("[{}] getResource - FALLBACK COMPLETE: sounds.json length: {}, ogg files: {}",
                        packId, this.soundsJsonContent.length(), this.oggResourceMap.size());
                if (!this.oggResourceMap.isEmpty()) {
                    LOGGER.info("[{}] getResource - FALLBACK oggResourceMap keys (first 5 after regen):", packId);
                    this.oggResourceMap.keySet().stream().limit(5).forEach(k -> LOGGER.info("  - {}", k));
                } else {
                    LOGGER.warn("[{}] getResource - FALLBACK oggResourceMap is EMPTY after regen.", packId);
                }
            }
            // デバッグファイル書き出しなど (既存の処理)
            try {
                Path debugFile = Paths.get("debug_sounds_provided_by_modsoundresourcepack.json");
                Files.writeString(debugFile, this.soundsJsonContent, StandardCharsets.UTF_8);
                LOGGER.info("[{}] Wrote current sounds.json content to {}", packId, debugFile.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to write debug_sounds_provided_by_modsoundresourcepack.json", packId, e);
            }
            return () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8));
        }

        // OGGファイルのリクエストか判定
        boolean isOggRequest = location.getNamespace().equals(Music_Player.MOD_ID) &&
                location.getPath().startsWith("sounds/") &&
                location.getPath().endsWith(".ogg");

        if (isOggRequest) {
            LOGGER.info("[{}] getResource - OGG REQUEST identified for: {}", packId, location);

            if (this.oggResourceMap == null) {
                LOGGER.error("[{}] getResource - OGG REQUEST: oggResourceMap is NULL for {}. This should not happen if sounds.json fallback worked.", packId, location);
                return null;
            }
            if (this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is EMPTY for {}. Attempting to re-populate if SoundPackManager is available.", packId, location);
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.info("[{}] getResource - OGG REQUEST: oggResourceMap re-populated, new size: {}. Keys (first 5):", packId, this.oggResourceMap.size());
                this.oggResourceMap.keySet().stream().limit(5).forEach(k -> LOGGER.info("  - {}", k));
                if (this.oggResourceMap.isEmpty()) {
                    LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is STILL EMPTY after re-population.", packId);
                }
            }

            if (this.oggResourceMap.containsKey(location)) {
                Path oggPath = this.oggResourceMap.get(location);
                LOGGER.info("[{}] getResource - OGG REQUEST: Key FOUND in oggResourceMap for {}. Path: {}", packId, location, oggPath);
                if (Files.exists(oggPath) && Files.isRegularFile(oggPath)) {
                    LOGGER.info("[{}] getResource - OGG REQUEST: Providing file {} from {}", packId, location, oggPath);
                    try {
                        return () -> Files.newInputStream(oggPath);
                    } catch (Exception e) { // IOExceptionなど
                        LOGGER.error("[{}] getResource - OGG REQUEST: Error creating InputStream for {}: {}", packId, oggPath, e.getMessage(), e);
                        return null;
                    }
                } else {
                    LOGGER.error("[{}] getResource - OGG REQUEST: File in map but NOT FOUND or not a file: {} (expected at {})", packId, location, oggPath);
                    return null;
                }
            } else {
                LOGGER.warn("[{}] getResource - OGG REQUEST: Key NOT FOUND in oggResourceMap for {}. Map size: {}", packId, location, this.oggResourceMap.size());
                if (!this.oggResourceMap.isEmpty()) {
                    LOGGER.warn("[{}] getResource - OGG REQUEST: Dumping oggResourceMap keys (first 10) for comparison with requested key <{}>:", packId, location);
                    this.oggResourceMap.keySet().stream().limit(10).forEach(key -> LOGGER.warn("  - Map key: {}", key));
                }
                return null; // 明示的にnullを返す
            }
        }

        // MODの名前空間だが、sounds.jsonでもOGGでもないリクエスト
        if (location.getNamespace().equals(Music_Player.MOD_ID) && !location.equals(SOUNDS_JSON_RL)) {
            LOGGER.warn("[{}] getResource - Resource in our namespace NOT HANDLED (not sounds.json or OGG): {}", packId, location);
        }
        return null; // デフォルトでnullを返す
    }

    @Override
    public void listResources(@NotNull PackType type, @NotNull String namespace,
                              @NotNull String path, @NotNull ResourceOutput resourceOutput) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }

        final String packId = this.packId();

        if (namespace.equals(Music_Player.MOD_ID)) {
            LOGGER.info("[{}] listResources - Query received. Namespace: '{}', Path: '{}'",
                    packId, namespace, path);

            // 1. sounds.json のリストアップ
            //    path が空か、"sounds.json" に一致または前方一致する場合
            if (path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path) || SOUNDS_JSON_RL.getPath().startsWith(path)) {
                // soundsJsonContent は getResource のフォールバックで最新化されることを期待。
                // listResources 時点では、reload -> apply でセットされたものか初期値。
                // より確実にするなら、ここでも SoundPackManager から取得するが、
                // getResource のフォールバックがあるため、ここでは既存のものをリストする。
                if (!"{}".equals(this.soundsJsonContent) || path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path)) { // 空でもsounds.json自体はリストする
                    LOGGER.info("[{}] listResources - Attempting to list {} for path query '{}'. Current soundsJsonContent length: {}",
                            packId, SOUNDS_JSON_RL, path, this.soundsJsonContent.length());
                    resourceOutput.accept(SOUNDS_JSON_RL, () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.info("[{}] listResources - Successfully listed {} for path query '{}'", packId, SOUNDS_JSON_RL, path);
                }
            }

            // 2. OGG ファイルのリストアップ
            //    path が空か、"sounds" または "sounds/" で始まる場合
            if (path.isEmpty() || path.equals("sounds") || path.startsWith("sounds/")) {
                Map<ResourceLocation, Path> currentOggMapToUse = this.oggResourceMap; // デフォルトはインスタンスのマップ
                // ★★★ 修正点 ★★★
                LOGGER.info("[{}] listResources - For OGG listing under path '{}', using current oggResourceMap (size {}). Consider fetching from SoundPackManager if stale.",
                        packId, path, currentOggMapToUse.size()); // currentOggMap を currentOggMapToUse に変更
                // --- デバッグ/確実性のため、SoundPackManagerから取得する ---
                Map<ResourceLocation, Path> managerMap = Music_Player.soundPackManager.getOggResourceMap();
                if (managerMap != null && !managerMap.isEmpty()) {
                    currentOggMapToUse = managerMap;
                    LOGGER.info("[{}] listResources - Fetched oggResourceMap from SoundPackManager for listing (size {}).", packId, currentOggMapToUse.size());
                } else if (managerMap == null) {
                    LOGGER.warn("[{}] listResources - SoundPackManager is available but getOggResourceMap() returned null.", packId);
                } else { // managerMap is empty
                    LOGGER.warn("[{}] listResources - SoundPackManager's oggResourceMap is empty. No OGGs will be listed from manager.", packId);
                }
                // --- ここまで ---


                for (Map.Entry<ResourceLocation, Path> entry : currentOggMapToUse.entrySet()) {
                    ResourceLocation fullOggRl = entry.getKey(); // 例: music_player:sounds/pack/file.ogg
                    Path oggDiskPath = entry.getValue();

                    if (!fullOggRl.getNamespace().equals(Music_Player.MOD_ID)) {
                        continue;
                    }

                    // SoundManager は path で指定されたディレクトリ直下のファイルを期待する。
                    // fullOggRl.getPath() が "sounds/pack/file.ogg" で、
                    // path が "sounds" の場合、SoundManager は "pack/file.ogg" を期待するかもしれないが、
                    // resourceOutput.accept に渡すのは完全な ResourceLocation。
                    // SoundManager がフィルタリングしてくれるはず。
                    // ここでは、path が fullOggRl.getPath() のプレフィックスであればリストする。
                    if (fullOggRl.getPath().startsWith(path)) {
                        LOGGER.info("[{}] listResources - Listing OGG: {} (for query path '{}')", packId, fullOggRl, path);
                        resourceOutput.accept(fullOggRl, () -> {
                            try {
                                if (Files.exists(oggDiskPath) && Files.isRegularFile(oggDiskPath)) {
                                    return Files.newInputStream(oggDiskPath);
                                } else {
                                    LOGGER.error("[{}] listResources - Listed OGG file not found on disk: {} (expected at {})", packId, fullOggRl, oggDiskPath);
                                    throw new FileNotFoundException("Listed OGG not found: " + oggDiskPath);
                                }
                            } catch (IOException e) {
                                LOGGER.error("[{}] listResources - IOException for OGG {}: {}", packId, fullOggRl, e.getMessage());
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
            LOGGER.info("[{}] getNamespaces called for CLIENT_RESOURCES, returning namespace: {}", packId, Music_Player.MOD_ID);
            return ImmutableSet.of(Music_Player.MOD_ID);
        }
        LOGGER.trace("[{}] getNamespaces called for type {}, returning empty set.", packId, type);
        return ImmutableSet.of();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(@NotNull MetadataSectionSerializer<T> deserializer) {
        // 通常、動的リソースパックではpack.mcmetaは提供しない
        LOGGER.trace("[{}] getMetadataSection called for {}", packId, deserializer.getMetadataSectionName());
        return null;
    }

    @Override
    public @NotNull String packId() {
        return this.packId;
    }

    @Override
    public boolean isBuiltin() {
        return true; // これによりpack.mcmetaが不要になる
    }

    @Override
    public void close() {
        // 必要に応じてリソース解放処理
    }
}