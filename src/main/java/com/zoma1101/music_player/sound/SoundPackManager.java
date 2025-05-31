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
    // private static final String ASSET_ID_FIELD = "asset_id"; // この行を削除またはコメントアウト
    private static final String OGG_RESOURCE_SOUNDS_PREFIX = "sounds/";


    private final List<SoundPackInfo> loadedSoundPacks = new ArrayList<>();
    private final List<MusicDefinition> allMusicDefinitions = new ArrayList<>();
    private final Map<ResourceLocation, Path> oggResourceMap = new HashMap<>();
    private final Map<String, MusicDefinition> musicDefinitionByEventKey = new HashMap<>();

    private List<String> activeSoundPackIds = new ArrayList<>(); // ここで保持するのは internalId

    public SoundPackManager() {
        // Initialization if needed
    }

    public void discoverAndLoadPacks() {
        LOGGER.info("Discovering and loading sound packs from: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
        loadedSoundPacks.clear();
        allMusicDefinitions.clear();
        oggResourceMap.clear();
        musicDefinitionByEventKey.clear();
        // activeSoundPackIds はここではクリアせず、設定画面などからの変更を保持する
        // もしリロード時に常にリセットしたい場合はクリアする
        // activeSoundPackIds.clear();


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

        // 初回ロード時や、アクティブなパックが一つもない場合にデフォルトで全てをアクティブにする
        if (!loadedSoundPacks.isEmpty() && activeSoundPackIds.isEmpty()) {
            activeSoundPackIds.addAll(loadedSoundPacks.stream().map(SoundPackInfo::getId).toList());
            LOGGER.info("Activated all loaded sound packs by default (using internalId): {}", activeSoundPackIds);
        } else {
            // 既にアクティブなパックIDがある場合は、それらがロードされたパックに含まれているか検証し、
            // 存在しないものは activeSoundPackIds から除去するなどの処理も検討できる
            List<String> validActiveIds = activeSoundPackIds.stream()
                    .filter(id -> loadedSoundPacks.stream().anyMatch(pack -> pack.getId().equals(id)))
                    .collect(Collectors.toList());
            if (validActiveIds.size() != activeSoundPackIds.size()) {
                LOGGER.info("Some previously active sound packs are no longer available. Updating active list.");
                activeSoundPackIds = validActiveIds;
            }
        }


        LOGGER.info("Finished loading sound packs. Found {} packs, {} music definitions, {} ogg resources.",
                loadedSoundPacks.size(), allMusicDefinitions.size(), oggResourceMap.size());
    }

    private void loadSingleSoundPack(Path packRootDir) {
        String directoryName = packRootDir.getFileName().toString();
        String internalId = directoryName.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        LOGGER.info("Processing sound pack directory: '{}' (Internal ID: {})", directoryName, internalId);

        Path mcmetaPath = packRootDir.resolve(PACK_METADATA_FILE);
        if (!Files.exists(mcmetaPath) || !Files.isRegularFile(mcmetaPath)) {
            LOGGER.warn("  Missing {} in pack directory: '{}'. Skipping this pack.", PACK_METADATA_FILE, directoryName);
            return;
        }

        SoundPackInfo soundPackInfo;
        String assetId; // 自動検出するアセットID

        try (Reader reader = Files.newBufferedReader(mcmetaPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject packMeta = root.getAsJsonObject("pack");
            if (packMeta == null) {
                LOGGER.warn("  Invalid {} format (missing 'pack' object) in pack directory: '{}'. Skipping.", PACK_METADATA_FILE, directoryName);
                return;
            }

            // --- assetId の自動検出処理 ---
            Path assetsDir = packRootDir.resolve("assets");
            if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
                LOGGER.warn("  Missing 'assets' directory in pack: '{}'. Skipping.", directoryName);
                return;
            }

            List<String> assetSubDirs;
            try (Stream<Path> stream = Files.list(assetsDir)) {
                assetSubDirs = stream
                        .filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
            } catch (IOException e) {
                LOGGER.error("  Failed to list subdirectories in 'assets' for pack: '{}'. Skipping.", directoryName, e);
                return;
            }

            if (assetSubDirs.isEmpty()) {
                LOGGER.warn("  No subdirectories found in 'assets' directory for pack: '{}'. Cannot determine assetId. Skipping.", directoryName);
                return;
            }
            if (assetSubDirs.size() > 1) {
                LOGGER.warn("  Multiple subdirectories found in 'assets' directory for pack: '{}' ({}). Cannot determine unique assetId. Skipping.", directoryName, assetSubDirs);
                return;
            }
            assetId = assetSubDirs.get(0);
            LOGGER.info("  Automatically determined assetId: '{}' for pack: '{}'", assetId, directoryName);

            // assetId のバリデーション (ResourceLocationのパスとして安全な文字かなど)
            if (!assetId.matches("[a-z0-9_.-]+")) {
                LOGGER.warn("  Automatically determined Asset ID '{}' for pack directory '{}' contains invalid characters. Only lowercase a-z, 0-9, '_', '.', '-' are allowed. Skipping.", assetId, directoryName);
                return;
            }
            // --- assetId の自動検出処理ここまで ---


            String descriptionText = packMeta.has("description") ? packMeta.get("description").getAsString() : "No description for " + directoryName;
            int packFormat = packMeta.has("pack_format") ? packMeta.get("pack_format").getAsInt() : -1;

            if (packFormat == -1) {
                LOGGER.warn("  Missing 'pack_format' in {} for pack directory: '{}'. Skipping.", PACK_METADATA_FILE, directoryName);
                return;
            }

            soundPackInfo = new SoundPackInfo(
                    internalId,
                    Component.literal(directoryName),
                    assetId,                          // 例: "dq_bgm"
                    Component.literal(descriptionText),
                    packRootDir
            );

            Path iconPath = packRootDir.resolve("pack.png"); // アイコンは引き続きパックルート直下を想定
            if (Files.exists(iconPath) && Files.isRegularFile(iconPath)) {
                try {
                    // アイコンのResourceLocationを music_player:<internalId>/pack.png とする
                    // ModSoundResourcePack側でこの形式を解決する必要がある
                    ResourceLocation iconRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, internalId + "/pack.png");
                    soundPackInfo.setIconLocation(iconRl);
                    LOGGER.info("  Set pack icon ResourceLocation using InternalID '{}': {}", internalId, iconRl);
                } catch (ResourceLocationException e) {
                    LOGGER.warn("  Could not create ResourceLocation for pack icon using InternalID '{}': {}", internalId, e.getMessage());
                }
            } else {
                LOGGER.info("  No pack.png found for pack with InternalID: {}", internalId);
            }

            loadedSoundPacks.add(soundPackInfo);
            LOGGER.info("  Loaded SoundPack: DisplayName='{}', AssetID='{}' (auto-detected), Description='{}', Format: {}, InternalID='{}'",
                    directoryName, soundPackInfo.getAssetId(), descriptionText, packFormat, internalId);

        } catch (JsonParseException | IOException e) {
            LOGGER.error("  Failed to read or parse {} for pack directory '{}': {}", PACK_METADATA_FILE, directoryName, e.getMessage(), e);
            return;
        } catch (Exception e) {
            LOGGER.error("  Unexpected error while processing metadata for pack directory '{}': {}", directoryName, e.getMessage(), e);
            return;
        }

        // soundPackInfo.getAssetsDirectory() は packRootDir.resolve("assets").resolve(assetId) を返す
        LOGGER.debug("  SoundPackInfo for conditions: InternalID='{}', AssetID='{}', PackDir='{}'",
                soundPackInfo.getId(), soundPackInfo.getAssetId(), soundPackInfo.getPackDirectory());
        Path conditionsDir = soundPackInfo.getAssetsDirectory().resolve(CONDITIONS_DIR_NAME);
        LOGGER.info("  Attempting to load conditions from: {}", conditionsDir);

        if (!Files.exists(conditionsDir) || !Files.isDirectory(conditionsDir)) {
            LOGGER.info("  No conditions directory found at: {}. No music definitions will be loaded for this pack.", conditionsDir);
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> loadMusicDefinition(jsonPath, soundPackInfo));
        } catch (IOException e) {
            LOGGER.error("  Error walking conditions directory {} for pack with AssetID '{}': {}", conditionsDir, soundPackInfo.getAssetId(), e.getMessage(), e);
        }
    }

    private void loadMusicDefinition(Path jsonPath, SoundPackInfo soundPackInfo) {
        try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            MusicDefinition definition = GSON.fromJson(reader, MusicDefinition.class);
            if (definition == null || definition.musicFileInPack == null || definition.musicFileInPack.isBlank()) {
                LOGGER.warn("  Invalid or incomplete music definition in file: {}. Missing 'musicFileInPack' field.", jsonPath);
                return;
            }
            // MusicDefinition には internalId (ディレクトリ名ベースのID) をセット
            definition.setSoundPackId(soundPackInfo.getId());

            // absoluteOggPath は packRootDir/assets/assetId/sounds/music/track1.ogg のような絶対パス
            Path absoluteOggPath = soundPackInfo.getAssetsDirectory().resolve(definition.getMusicFileInPack());
            if (!Files.exists(absoluteOggPath) || !Files.isRegularFile(absoluteOggPath)) {
                LOGGER.warn("  Sound file not found for definition in {}: {} (Expected at {})",
                        jsonPath.getFileName(), definition.getMusicFileInPack(), absoluteOggPath);
                return;
            }
            definition.setAbsoluteOggPath(absoluteOggPath);

            String assetId = soundPackInfo.getAssetId(); // 自動検出した assetId
            String relativeOggPathFromPackAssets = definition.getMusicFileInPack();

            // soundEventKey: sounds.json のトップレベルキー (例: "dq_bgm/sounds/music/track1")
            String soundEventKey = getSoundEventKey(relativeOggPathFromPackAssets, assetId);
            definition.setSoundEventKey(soundEventKey);

            try {
                // oggRLForName: sounds.json の "name" フィールド用 (例: "music_player:dq_bgm/sounds/music/track1")
                ResourceLocation oggRLForName = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);
                definition.setOggResourceLocation(oggRLForName);

                // mapKeyRL: oggResourceMap のキー、ModSoundResourcePack が受け取るリクエスト
                // (例: "music_player:sounds/dq_bgm/sounds/music/track1.ogg")
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
                LOGGER.warn("  Invalid characters in generated ResourceLocation components for AssetID '{}', file '{}'. Skipping. Error: {}",
                        assetId, relativeOggPathFromPackAssets, e.getMessage());
            }

        } catch (JsonSyntaxException e) {
            LOGGER.error("  Failed to parse JSON for music definition file: {}", jsonPath, e);
        } catch (IOException e) {
            LOGGER.error("  Failed to read music definition file: {}", jsonPath, e);
        } catch (Exception e) {
            LOGGER.error("  Unexpected error processing music definition file: {}", jsonPath, e);
        }
    }

    // assetId は自動検出された assets 内のサブディレクトリ名
    private static @NotNull String getSoundEventKey(String relativeOggPathFromPackAssets, String assetId) {
        String pathWithoutExtension = relativeOggPathFromPackAssets;
        if (pathWithoutExtension.toLowerCase().endsWith(".ogg")) {
            pathWithoutExtension = pathWithoutExtension.substring(0, pathWithoutExtension.length() - 4);
        }
        // soundEventKey (例: "dq_bgm/sounds/music/track1")
        return (assetId + "/" + pathWithoutExtension)
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
                .filter(def -> activeSoundPackIds.contains(def.getSoundPackId())) // soundPackId は internalId
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

            // "name" フィールドには、MOD_ID と assetId ベースの soundEventKey を使用
            soundObject.addProperty("name", def.getOggResourceLocation().toString());
            soundObject.addProperty("stream", true);

            soundsArray.add(soundObject);
            soundEntry.add("sounds", soundsArray);

            // sounds.json のトップレベルキーは assetId ベースの soundEventKey
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
        // ここで受け取る ids は internalId (ディレクトリ名ベース) のリスト
        this.activeSoundPackIds = new ArrayList<>(ids);
        LOGGER.info("Active sound packs updated (based on internalId): {}", this.activeSoundPackIds);
    }

    public List<String> getActiveSoundPackIds() {
        return Collections.unmodifiableList(activeSoundPackIds);
    }
}