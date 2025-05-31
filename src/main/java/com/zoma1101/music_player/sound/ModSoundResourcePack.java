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
            // In previous iterations, SoundPackManager.discoverAndLoadPacks() was called here.
            // Ensure that SoundPackManager's state is correctly managed if re-introducing that call.
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
        // Typically, root resources are not heavily used in MOD resource packs.
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
        LOGGER.debug("[{}] getResource - ENTRY for: {}", currentPackId, location);

        // 1. sounds.json processing
        if (location.equals(SOUNDS_JSON_RL)) {
            LOGGER.debug("[{}] getResource - Handling SOUNDS.JSON request for: {}", currentPackId, location);
            if ("{}".equals(this.soundsJsonContent)) {
                LOGGER.warn("[{}] getResource - SOUNDS.JSON was empty. FALLBACK: Regenerating data.", currentPackId);
                // Fallback to regenerate data if reload might not have completed in time.
                this.soundsJsonContent = Music_Player.soundPackManager.generateSoundsJsonContent();
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap();
                LOGGER.debug("[{}] getResource - FALLBACK COMPLETE: sounds.json length: {}, ogg files: {}",
                        currentPackId, this.soundsJsonContent.length(), this.oggResourceMap.size());
            }
            try {
                // Write current sounds.json content to a debug file.
                Path debugFile = Paths.get("debug_sounds_provided_by_modsoundresourcepack.json");
                Files.writeString(debugFile, this.soundsJsonContent, StandardCharsets.UTF_8);
                LOGGER.debug("[{}] Wrote current sounds.json content to {}", currentPackId, debugFile.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to write debug_sounds_provided_by_modsoundresourcepack.json", currentPackId, e);
            }
            return () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8));
        }

        // 2. OGG file request determination
        // The oggResourceMap can contain Paths within a ZipFS, which Files.newInputStream handles transparently.
        if (location.getPath().startsWith(OGG_RESOURCE_SOUNDS_PREFIX) && location.getPath().endsWith(".ogg")) {
            LOGGER.debug("[{}] getResource - OGG REQUEST identified for: {}", currentPackId, location);
            if (this.oggResourceMap == null || this.oggResourceMap.isEmpty()) {
                LOGGER.warn("[{}] getResource - OGG REQUEST: oggResourceMap is null or empty for {}. Attempting to re-populate.", currentPackId, location);
                this.oggResourceMap = Music_Player.soundPackManager.getOggResourceMap(); // Attempt to re-fetch
                LOGGER.debug("[{}] getResource - OGG REQUEST: oggResourceMap re-populated, new size: {}.", currentPackId, this.oggResourceMap.size());
            }

            if (this.oggResourceMap.containsKey(location)) {
                Path oggPath = this.oggResourceMap.get(location);
                // Added debug log to check FileSystem status
                LOGGER.debug("[{}] getResource - OGG REQUEST: Key FOUND for {}. Path: {}, FileSystem: {}, IsOpen: {}",
                        currentPackId, location, oggPath, oggPath.getFileSystem(), oggPath.getFileSystem().isOpen());

                if (oggPath.getFileSystem().isOpen() && Files.exists(oggPath) && Files.isRegularFile(oggPath)) {
                    LOGGER.debug("[{}] getResource - OGG REQUEST: Providing file {} from {}", currentPackId, location, oggPath);
                    try {
                        return () -> Files.newInputStream(oggPath);
                    } catch (Exception e) {
                        LOGGER.error("[{}] getResource - OGG REQUEST: Error creating InputStream for {}: {}", currentPackId, oggPath, e.getMessage(), e);
                        return null;
                    }
                } else {
                    LOGGER.error("[{}] getResource - OGG REQUEST: File in map but NOT FOUND, not a file, or FileSystem closed: {} (expected at {}, FileSystem Open: {})", currentPackId, location, oggPath, oggPath.getFileSystem().isOpen());
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

        // 3. pack.png (icon) processing
        // The RL generated by SoundPackManager is in the format music_player:<internalId>/pack.png
        String requestedPath = location.getPath();
        if (requestedPath.endsWith("/pack.png")) {
            // Find SoundPackInfo based on the icon's ResourceLocation
            SoundPackInfo packInfo = Music_Player.soundPackManager.getLoadedSoundPacks().stream()
                    .filter(spi -> spi.getIconLocation() != null && spi.getIconLocation().equals(location))
                    .findFirst().orElse(null);

            if (packInfo != null) {
                Path iconPhysicalPath = packInfo.getIconFileSystemPath(); // Get physical path from SoundPackInfo
                if (iconPhysicalPath != null) {
                    LOGGER.debug("[{}] getResource - Pack Icon REQUEST for: {}. Using physical path: {}",
                            currentPackId, location, iconPhysicalPath);
                    if (iconPhysicalPath.getFileSystem().isOpen() && Files.exists(iconPhysicalPath) && Files.isRegularFile(iconPhysicalPath)) {
                        try {
                            return () -> Files.newInputStream(iconPhysicalPath);
                        } catch (Exception e) {
                            LOGGER.error("[{}] getResource - Pack Icon REQUEST: Error creating InputStream for {}: {}",
                                    currentPackId, iconPhysicalPath, e.getMessage(), e);
                            return null;
                        }
                    } else {
                        LOGGER.warn("[{}] getResource - Pack Icon file (from SoundPackInfo.iconFileSystemPath) not found, not a file, or FileSystem closed: {} (Path: {}, FileSystem Open: {})",
                                currentPackId, location, iconPhysicalPath, iconPhysicalPath.getFileSystem().isOpen());
                        return null;
                    }
                } else {
                    LOGGER.warn("[{}] getResource - Pack Icon REQUEST: SoundPackInfo found for {}, but its iconFileSystemPath is null.", currentPackId, location);
                }
            } else {
                LOGGER.warn("[{}] getResource - Pack Icon REQUEST: Could not find SoundPackInfo for ResourceLocation: {}", currentPackId, location);
            }
        }

        LOGGER.debug("[{}] getResource - Resource in our namespace NOT HANDLED: {}", currentPackId, location);
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

            // 1. sounds.json listing
            if (path.isEmpty() || SOUNDS_JSON_RL.getPath().startsWith(path)) {
                if (!"{}".equals(this.soundsJsonContent) || path.isEmpty() || SOUNDS_JSON_RL.getPath().equals(path)) {
                    LOGGER.debug("[{}] listResources - Attempting to list {} for path query '{}'. Current soundsJsonContent length: {}",
                            currentPackId, SOUNDS_JSON_RL, path, this.soundsJsonContent.length());
                    resourceOutput.accept(SOUNDS_JSON_RL, () -> new ByteArrayInputStream(this.soundsJsonContent.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.debug("[{}] listResources - Successfully listed {} for path query '{}'", currentPackId, SOUNDS_JSON_RL, path);
                }
            }

            // 2. OGG file listing
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
                        continue;
                    }
                    if (fullOggRl.getPath().startsWith(path)) {
                        LOGGER.debug("[{}] listResources - Listing OGG: {} (for query path '{}', FileSystem Open: {})", currentPackId, fullOggRl, path, oggDiskPath.getFileSystem().isOpen());
                        resourceOutput.accept(fullOggRl, () -> {
                            try {
                                if (oggDiskPath.getFileSystem().isOpen() && Files.exists(oggDiskPath) && Files.isRegularFile(oggDiskPath)) {
                                    return Files.newInputStream(oggDiskPath);
                                } else {
                                    LOGGER.error("[{}] listResources - Listed OGG file not found on disk or FileSystem closed: {} (expected at {}, FileSystem Open: {})", currentPackId, fullOggRl, oggDiskPath, oggDiskPath.getFileSystem().isOpen());
                                    throw new FileNotFoundException("Listed OGG not found or FileSystem closed: " + oggDiskPath);
                                }
                            } catch (IOException e) {
                                LOGGER.error("[{}] listResources - IOException for OGG {}: {}", currentPackId, fullOggRl, e.getMessage());
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }

            // 3. pack.png (icon) listing
            List<SoundPackInfo> loadedPacks = Music_Player.soundPackManager.getLoadedSoundPacks();
            for (SoundPackInfo packInfo : loadedPacks) {
                ResourceLocation iconRl = packInfo.getIconLocation();
                Path iconPhysicalPath = packInfo.getIconFileSystemPath(); // Get physical path from SoundPackInfo

                if (iconRl != null && iconPhysicalPath != null && iconRl.getNamespace().equals(Music_Player.MOD_ID) && iconRl.getPath().startsWith(path)) {
                    LOGGER.debug("[{}] listResources - Listing Pack Icon: {} (Physical Path: {}, for query path '{}', FileSystem Open: {})",
                            currentPackId, iconRl, iconPhysicalPath, path, iconPhysicalPath.getFileSystem().isOpen());
                    resourceOutput.accept(iconRl, () -> {
                        try {
                            if (iconPhysicalPath.getFileSystem().isOpen() && Files.exists(iconPhysicalPath) && Files.isRegularFile(iconPhysicalPath)) {
                                return Files.newInputStream(iconPhysicalPath);
                            } else {
                                LOGGER.error("[{}] listResources - Listed Pack Icon file not found on disk or FileSystem closed: {} (expected at {}, FileSystem Open: {})", currentPackId, iconRl, iconPhysicalPath, iconPhysicalPath.getFileSystem().isOpen());
                                throw new FileNotFoundException("Listed Pack Icon not found or FileSystem closed: " + iconPhysicalPath);
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
        // This resource pack does not dynamically provide pack.mcmeta, so return null.
        return null;
    }

    @Override
    public @NotNull String packId() {
        return this.packId;
    }

    @Override
    public boolean isBuiltin() {
        // This resource pack is provided by the MOD, so return true.
        return true;
    }

    @Override
    public void close() {
        // ModSoundResourcePack itself does not open ZipFileSystems, so nothing to do here.
        // Closing ZipFileSystems is handled by SoundPackManager.
    }
}