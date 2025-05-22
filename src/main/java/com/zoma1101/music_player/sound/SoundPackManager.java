package com.zoma1101.music_player.sound;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SoundPackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path SOUNDPACKS_BASE_DIR = Paths.get("soundpacks");
    private static final String PACK_METADATA_FILE = "pack.mcmeta";
    private static final String CONDITIONS_DIR_NAME = "conditions";
    // OGGリソースのResourceLocationパスのプレフィックス
    private static final String OGG_RESOURCE_SOUNDS_PREFIX = "sounds/";


    private final List<SoundPackInfo> loadedSoundPacks = new ArrayList<>();
    private final List<MusicDefinition> allMusicDefinitions = new ArrayList<>();
    private final Map<ResourceLocation, Path> oggResourceMap = new HashMap<>();
    private final Map<String, MusicDefinition> musicDefinitionByEventKey = new HashMap<>();

    private List<String> activeSoundPackIds = new ArrayList<>();

    public SoundPackManager() {
        // Initialization if needed
    }

    public void discoverAndLoadPacks() {
        LOGGER.info("Discovering and loading sound packs from: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
        loadedSoundPacks.clear();
        allMusicDefinitions.clear();
        oggResourceMap.clear();
        musicDefinitionByEventKey.clear();
        activeSoundPackIds.clear();

        if (!Files.exists(SOUNDPACKS_BASE_DIR)) {
            try {
                Files.createDirectories(SOUNDPACKS_BASE_DIR);
                LOGGER.info("Created soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
                return;
            }
        }
        if (!Files.isDirectory(SOUNDPACKS_BASE_DIR)) {
            LOGGER.error("Soundpacks path exists but is not a directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            return;
        }

        try (Stream<Path> packDirs = Files.list(SOUNDPACKS_BASE_DIR)) {
            packDirs.filter(Files::isDirectory).forEach(this::loadSingleSoundPack);
        } catch (IOException e) {
            LOGGER.error("Error listing sound pack directories in: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
        }

        if (!loadedSoundPacks.isEmpty() && activeSoundPackIds.isEmpty()) {
            activeSoundPackIds.addAll(loadedSoundPacks.stream().map(SoundPackInfo::getId).toList());
            LOGGER.info("Activated all loaded sound packs by default: {}", activeSoundPackIds);
        }

        LOGGER.info("Finished loading sound packs. Found {} packs, {} music definitions, {} ogg resources.",
                loadedSoundPacks.size(), allMusicDefinitions.size(), oggResourceMap.size());
    }

    private void loadSingleSoundPack(Path packRootDir) {
        String packId = packRootDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        LOGGER.info("Processing sound pack directory: {} (ID: {})", packRootDir.getFileName(), packId);

        Path mcmetaPath = packRootDir.resolve(PACK_METADATA_FILE);
        if (!Files.exists(mcmetaPath) || !Files.isRegularFile(mcmetaPath)) {
            LOGGER.warn("  Missing {} in pack: {}. Skipping this pack.", PACK_METADATA_FILE, packId);
            return;
        }

        SoundPackInfo soundPackInfo;
        try (Reader reader = Files.newBufferedReader(mcmetaPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject packMeta = root.getAsJsonObject("pack");
            if (packMeta == null) {
                LOGGER.warn("  Invalid {} format (missing 'pack' object) in pack: {}. Skipping.", PACK_METADATA_FILE, packId);
                return;
            }
            String description = packMeta.has("description") ? packMeta.get("description").getAsString() : "No description for " + packId;
            int packFormat = packMeta.has("pack_format") ? packMeta.get("pack_format").getAsInt() : -1;

            if (packFormat == -1) {
                LOGGER.warn("  Missing 'pack_format' in {} for pack: {}. Skipping.", PACK_METADATA_FILE, packId);
                return;
            }

            soundPackInfo = new SoundPackInfo(packId, Component.literal(description), packRootDir);

            Path iconPath = packRootDir.resolve("pack.png");
            if (Files.exists(iconPath) && Files.isRegularFile(iconPath)) {
                try {
                    // アイコンのResourceLocation: music_player:pack_id/pack.png
                    // ModSoundResourcePackはこれを受け取り、packRootDir/pack.png を提供する
                    ResourceLocation iconRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, packId + "/pack.png");
                    soundPackInfo.setIconLocation(iconRl);
                    LOGGER.info("  Found pack icon for {}: {}", packId, iconRl);
                } catch (ResourceLocationException e) {
                    LOGGER.warn("  Could not create ResourceLocation for pack icon for {}: {}", packId, e.getMessage());
                }
            } else {
                LOGGER.info("  No pack.png found for pack: {}", packId);
            }

            loadedSoundPacks.add(soundPackInfo);
            LOGGER.info("  Loaded SoundPack metadata: '{}', format: {}", description, packFormat);

        } catch (JsonParseException | IOException e) { // より具体的な例外をキャッチ
            LOGGER.error("  Failed to read or parse {} for pack {}: {}", PACK_METADATA_FILE, packId, e.getMessage(), e);
            return;
        } catch (Exception e) { // 予期せぬエラー
            LOGGER.error("  Unexpected error while processing metadata for pack {}: {}", packId, e.getMessage(), e);
            return;
        }

        // soundPackInfo.getAssetsDirectory() は packRootDir.resolve("assets").resolve(packId) のような
        // パック固有のアセットルートを返すことを期待
        Path conditionsDir = soundPackInfo.getAssetsDirectory().resolve(CONDITIONS_DIR_NAME);
        if (!Files.exists(conditionsDir) || !Files.isDirectory(conditionsDir)) {
            LOGGER.info("  No conditions directory found at: {}. No music definitions will be loaded for this pack.", conditionsDir);
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> loadMusicDefinition(jsonPath, soundPackInfo));
        } catch (IOException e) {
            LOGGER.error("  Error walking conditions directory {} for pack {}: {}", conditionsDir, packId, e.getMessage(), e);
        }
    }

    private void loadMusicDefinition(Path jsonPath, SoundPackInfo soundPackInfo) {
        try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            MusicDefinition definition = GSON.fromJson(reader, MusicDefinition.class);
            if (definition == null || definition.musicFileInPack == null || definition.musicFileInPack.isBlank()) {
                LOGGER.warn("  Invalid or incomplete music definition in file: {}. Missing 'musicFileInPack' field.", jsonPath);
                return;
            }
            // musicFileInPack は packId 固有のアセットディレクトリからの相対パス (例: "sounds/music/track1.ogg")
            definition.setSoundPackId(soundPackInfo.getId());

            // absoluteOggPath は packRootDir/assets/packId/sounds/music/track1.ogg のような絶対パス
            Path absoluteOggPath = soundPackInfo.getAssetsDirectory().resolve(definition.getMusicFileInPack());
            if (!Files.exists(absoluteOggPath) || !Files.isRegularFile(absoluteOggPath)) {
                LOGGER.warn("  Sound file not found for definition in {}: {} (Expected at {})",
                        jsonPath.getFileName(), definition.getMusicFileInPack(), absoluteOggPath);
                return;
            }
            definition.setAbsoluteOggPath(absoluteOggPath);

            String packId = soundPackInfo.getId();
            // relativeOggPathFromPackAssets は musicFileInPack と同じ (例: "sounds/music/track1.ogg")
            String relativeOggPathFromPackAssets = definition.getMusicFileInPack();

            // soundEventKey: sounds.json のトップレベルキー (例: "your_pack_id/sounds/music/track1")
            String soundEventKey = getSoundEventKey(relativeOggPathFromPackAssets, packId);
            definition.setSoundEventKey(soundEventKey);

            try {
                // oggRLForName: sounds.json の "name" フィールド用 (例: "music_player:your_pack_id/sounds/music/track1")
                // SoundManager はこれを見て "music_player:sounds/your_pack_id/sounds/music/track1.ogg" を要求する
                ResourceLocation oggRLForName = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);
                definition.setOggResourceLocation(oggRLForName);

                // mapKeyRL: oggResourceMap のキー、ModSoundResourcePack が受け取るリクエスト
                // (例: "music_player:sounds/your_pack_id/sounds/music/track1.ogg")
                String mapKeyPath = OGG_RESOURCE_SOUNDS_PREFIX + soundEventKey + ".ogg";
                ResourceLocation mapKeyRL = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, mapKeyPath);
                oggResourceMap.put(mapKeyRL, absoluteOggPath);

                if (definition.isValid()) {
                    allMusicDefinitions.add(definition);
                    musicDefinitionByEventKey.put(definition.getSoundEventKey(), definition);
                    LOGGER.debug("  Loaded music definition: File='{}', EventKey='{}', NameRL='{}', MapKeyRL='{}'",
                            definition.getMusicFileInPack(),
                            definition.getSoundEventKey(),
                            definition.getOggResourceLocation(),
                            mapKeyRL);
                } else {
                    LOGGER.warn("  Music definition from {} was parsed but deemed invalid after processing. Definition: {}", jsonPath, definition);
                }

            } catch (ResourceLocationException e) {
                LOGGER.warn("  Invalid characters in generated ResourceLocation components for pack '{}', file '{}'. Skipping. Error: {}",
                        packId, relativeOggPathFromPackAssets, e.getMessage());
            }

        } catch (JsonSyntaxException e) {
            LOGGER.error("  Failed to parse JSON for music definition file: {}", jsonPath, e);
        } catch (IOException e) {
            LOGGER.error("  Failed to read music definition file: {}", jsonPath, e);
        } catch (Exception e) { // その他の予期せぬエラー
            LOGGER.error("  Unexpected error processing music definition file: {}", jsonPath, e);
        }
    }

    private static @NotNull String getSoundEventKey(String relativeOggPathFromPackAssets, String packId) {
        String pathWithoutExtension = relativeOggPathFromPackAssets;
        if (pathWithoutExtension.toLowerCase().endsWith(".ogg")) {
            pathWithoutExtension = pathWithoutExtension.substring(0, pathWithoutExtension.length() - 4);
        }
        // soundEventKey (例: "your_pack_id/sounds/music/track1")
        return (packId + "/" + pathWithoutExtension)
                .toLowerCase()
                .replaceAll("[^a-z0-9_./-]", "_"); // ResourceLocationのパスとして有効な文字にクリーニング
    }

    public MusicDefinition getMusicDefinitionByEventKey(String eventKey) {
        return musicDefinitionByEventKey.get(eventKey);
    }

    public List<SoundPackInfo> getLoadedSoundPacks() {
        return Collections.unmodifiableList(loadedSoundPacks);
    }

    public List<MusicDefinition> getActiveMusicDefinitionsSorted() {
        return allMusicDefinitions.stream()
                .filter(def -> activeSoundPackIds.contains(def.getSoundPackId()))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .collect(Collectors.toList());
    }

    public Map<ResourceLocation, Path> getOggResourceMap() {
        return Collections.unmodifiableMap(oggResourceMap);
    }

    public String generateSoundsJsonContent() {
        List<MusicDefinition> definitionsToInclude = getActiveMusicDefinitionsSorted();

        if (definitionsToInclude.isEmpty()) {
            LOGGER.info("No active music definitions found, generating empty sounds.json content.");
            return "{}";
        }

        JsonObject rootJson = new JsonObject();
        LOGGER.info("Generating sounds.json for {} active music definitions.", definitionsToInclude.size());

        for (MusicDefinition def : definitionsToInclude) {
            if (!def.isValid()) {
                LOGGER.warn("Skipping invalid definition during sounds.json generation: {}", def);
                continue;
            }

            JsonObject soundEntry = new JsonObject();
            JsonArray soundsArray = new JsonArray();
            JsonObject soundObject = new JsonObject();

            soundObject.addProperty("name", def.getOggResourceLocation().toString());
            soundObject.addProperty("stream", true);

            soundsArray.add(soundObject);
            soundEntry.add("sounds", soundsArray);

            rootJson.add(def.getSoundEventKey(), soundEntry);
        }

        if (rootJson.size() == 0) {
            LOGGER.warn("Generated sounds.json is empty after filtering active/valid definitions.");
            return "{}";
        }
        String jsonOutput = GSON.toJson(rootJson);
        LOGGER.info("Generated sounds.json content (length {}): {}", jsonOutput.length(), jsonOutput.substring(0, Math.min(jsonOutput.length(), 500)) + (jsonOutput.length() > 500 ? "..." : ""));
        return jsonOutput;
    }

    public void setActiveSoundPackIds(List<String> ids) {
        this.activeSoundPackIds = new ArrayList<>(ids);
        LOGGER.info("Active sound packs updated: {}", this.activeSoundPackIds);
        // 必要であれば、ここでリソースリロードやClientMusicManagerへの通知などを検討
    }

    public List<String> getActiveSoundPackIds() {
        return Collections.unmodifiableList(activeSoundPackIds);
    }
}