package com.zoma1101.music_player.sound;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SoundPackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path SOUNDPACKS_BASE_DIR = Paths.get("soundpacks");
    private static final String PACK_METADATA_FILE = "pack.mcmeta";
    private static final String CONDITIONS_DIR_NAME = "conditions";
    private static final String OGG_RESOURCE_SOUNDS_PREFIX = "sounds/";

    private static final Path CONFIG_DIR = Paths.get("config");
    private static final String ACTIVE_PACKS_CONFIG_FILE_NAME = Music_Player.MOD_ID + "_active_packs.json";
    private static final String PACK_ORDER_CONFIG_FILE_NAME = Music_Player.MOD_ID + "_pack_order.json";

    private final List<SoundPackInfo> loadedSoundPacks = new CopyOnWriteArrayList<>();
    private final List<MusicDefinition> allMusicDefinitions = new CopyOnWriteArrayList<>();
    private final Map<ResourceLocation, Path> oggResourceMap = new ConcurrentHashMap<>();
    private final Map<String, MusicDefinition> musicDefinitionByEventKey = new ConcurrentHashMap<>();
    private List<String> activeSoundPackIds = new CopyOnWriteArrayList<>();
    private List<String> packOrder = new CopyOnWriteArrayList<>();

    private final List<FileSystem> openZipFileSystems = new CopyOnWriteArrayList<>();

    public SoundPackManager() {
        // コンストラクタでの loadActivePacksConfig() 呼び出しは discoverAndLoadPacks に移動
    }

    private void closeAllZipFileSystems() {
        LOGGER.debug("Closing all open ZipFileSystems (count: {})...", openZipFileSystems.size());
        for (FileSystem fs : this.openZipFileSystems) {
            try {
                if (fs.isOpen()) {
                    fs.close();
                    LOGGER.debug("Closed ZipFileSystem: {}", fs);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to close ZipFileSystem: {}", fs, e);
            }
        }
        this.openZipFileSystems.clear();
    }

    public void discoverAndLoadPacks() {
        LOGGER.info("Discovering and loading sound packs from: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());

        // 1. まず全てのサウンドパックをスキャンしてロード
        closeAllZipFileSystems();
        loadedSoundPacks.clear();
        allMusicDefinitions.clear();
        oggResourceMap.clear();
        musicDefinitionByEventKey.clear();
        // activeSoundPackIds はこの時点ではクリアせず、後で設定ファイルから読み込む

        if (!Files.exists(SOUNDPACKS_BASE_DIR)) {
            try {
                Files.createDirectories(SOUNDPACKS_BASE_DIR);
                LOGGER.info("Created soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
                // return; // ここでリターンすると設定読み込みも行われないので注意
            }
        }
        if (Files.exists(SOUNDPACKS_BASE_DIR) && !Files.isDirectory(SOUNDPACKS_BASE_DIR)) {
            LOGGER.error("Soundpacks path exists but is not a directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            // return;
        }

        if (Files.isDirectory(SOUNDPACKS_BASE_DIR)) { // ディレクトリが存在する場合のみスキャン
            try (Stream<Path> packDirs = Files.list(SOUNDPACKS_BASE_DIR)) {
                packDirs.filter(Files::isDirectory).forEach(this::loadSingleDirectorySoundPack);
            } catch (IOException e) {
                LOGGER.error("Error listing sound pack directories in: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
            }

            try (Stream<Path> zipFiles = Files.list(SOUNDPACKS_BASE_DIR)) {
                zipFiles.filter(p -> p.toString().toLowerCase().endsWith(".zip") && Files.isRegularFile(p))
                        .forEach(this::loadSingleZipSoundPack);
            } catch (IOException e) {
                LOGGER.error("Error listing sound pack ZIP files in: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
            }
        }
        LOGGER.info("Initial scan complete. Found {} potential sound packs.", loadedSoundPacks.size());

        // 順序設定の読み込みとソート
        List<String> loadedOrder = loadPackOrderConfig();
        List<SoundPackInfo> sortedPacks = new ArrayList<>();
        // まず loadedOrder に含まれるものを順番に追加
        for (String id : loadedOrder) {
            loadedSoundPacks.stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst()
                    .ifPresent(sortedPacks::add);
        }
        // 次に、loadedOrder に含まれない（新しく追加された）ものを追加
        for (SoundPackInfo pack : loadedSoundPacks) {
            if (!sortedPacks.contains(pack)) {
                sortedPacks.add(pack);
            }
        }
        // loadedSoundPacks をソート済みのものに置き換え
        this.loadedSoundPacks.clear();
        this.loadedSoundPacks.addAll(sortedPacks);

        // 最新の順序リストを packOrder に保存
        this.packOrder = this.loadedSoundPacks.stream()
                .map(SoundPackInfo::getId)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
        savePackOrderConfig();

        // 2. 次に、設定ファイルから前回のアクティブなパックIDを読み込む
        List<String> configuredActiveIds = loadActivePacksConfig(); // 読み込んだIDを一時変数に

        // 3. 最後に、読み込んだ設定とロードされたパック情報を照合
        if (!loadedSoundPacks.isEmpty()) {
            // 設定ファイルにあるIDのうち、現在ロードされているものだけを抽出（順序を維持）
            this.activeSoundPackIds = configuredActiveIds.stream()
                    .filter(id -> loadedSoundPacks.stream().anyMatch(pack -> pack.getId().equals(id)))
                    .collect(Collectors.toCollection(CopyOnWriteArrayList::new));

            if (configuredActiveIds.isEmpty()) {
                LOGGER.info("No active packs in configuration. New packs will remain inactive until manually enabled.");
            } else if (configuredActiveIds.size() != this.activeSoundPackIds.size()) {
                LOGGER.warn("Some configured active packs were not found among loaded packs. Updated valid list.");
                saveActivePacksConfig();
            }
        } else {
            this.activeSoundPackIds.clear();
            LOGGER.info("No sound packs loaded.");
        }

        LOGGER.info("Finished processing sound packs. Loaded: {} packs, {} music definitions. Active/Configured count: {}",
                loadedSoundPacks.size(), allMusicDefinitions.size(), this.activeSoundPackIds.size());
    }

    private List<String> loadActivePacksConfig() { // 戻り値をList<String>に変更
        Path configFile = CONFIG_DIR.resolve(ACTIVE_PACKS_CONFIG_FILE_NAME);
        List<String> loadedIds = new ArrayList<>(); // 設定の読み込み時は標準のArrayListでOK
        if (Files.exists(configFile) && Files.isRegularFile(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> parsedIds = GSON.fromJson(reader, listType);
                if (parsedIds != null) {
                    loadedIds.addAll(parsedIds);
                    LOGGER.info("Loaded active sound pack configuration from {}: {}", configFile.toAbsolutePath(), loadedIds);
                } else {
                    LOGGER.warn("Could not parse active sound pack configuration from {}. File might be empty or malformed.", configFile.toAbsolutePath());
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Failed to read or parse active sound pack configuration file: {}", configFile.toAbsolutePath(), e);
            }
        } else {
            LOGGER.info("Active sound pack configuration file not found at {}. No packs will be active by default initially.", configFile.toAbsolutePath());
        }
        return loadedIds; // 読み込んだIDのリスト（空かもしれない）を返す
    }

    private void saveActivePacksConfig() {
        Path configFile = CONFIG_DIR.resolve(ACTIVE_PACKS_CONFIG_FILE_NAME);
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this.activeSoundPackIds, writer); // 保存するのは現在の SoundPackManager の activeSoundPackIds
                LOGGER.info("Saved active sound pack configuration to {}: {}", configFile.toAbsolutePath(), this.activeSoundPackIds);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save active sound pack configuration file: {}", configFile.toAbsolutePath(), e);
        }
    }

    private List<String> loadPackOrderConfig() {
        Path configFile = CONFIG_DIR.resolve(PACK_ORDER_CONFIG_FILE_NAME);
        List<String> loadedIds = new ArrayList<>();
        if (Files.exists(configFile) && Files.isRegularFile(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> parsedIds = GSON.fromJson(reader, listType);
                if (parsedIds != null) {
                    loadedIds.addAll(parsedIds);
                    LOGGER.info("Loaded sound pack order configuration from {}: {}", configFile.toAbsolutePath(), loadedIds);
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Failed to read or parse sound pack order configuration file: {}", configFile.toAbsolutePath(), e);
            }
        }
        return loadedIds;
    }

    private void savePackOrderConfig() {
        Path configFile = CONFIG_DIR.resolve(PACK_ORDER_CONFIG_FILE_NAME);
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this.packOrder, writer);
                LOGGER.info("Saved sound pack order configuration to {}: {}", configFile.toAbsolutePath(), this.packOrder);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save sound pack order configuration file: {}", configFile.toAbsolutePath(), e);
        }
    }

    private void loadSingleDirectorySoundPack(Path packRootDir) {
        String displayName = packRootDir.getFileName().toString();
        LOGGER.info("Processing directory sound pack: '{}'", displayName);
        loadSingleSoundPackLogic(packRootDir, displayName, false);
    }

    private void loadSingleZipSoundPack(Path zipFilePath) {
        String zipFileName = zipFilePath.getFileName().toString();
        String displayName = zipFileName.substring(0, zipFileName.lastIndexOf('.'));
        LOGGER.info("Processing ZIP sound pack: '{}' (from file: {})", displayName, zipFileName);
        try {
            FileSystem zipFs = FileSystems.newFileSystem(zipFilePath, Collections.emptyMap());
            this.openZipFileSystems.add(zipFs);
            Path packRootInZip = zipFs.getPath("/");
            loadSingleSoundPackLogic(packRootInZip, displayName, true);
        } catch (ProviderNotFoundException e) {
            LOGGER.error("  ZIP file system provider not found for {}. This should not happen with standard Java.", zipFilePath, e);
        } catch (FileSystemAlreadyExistsException e) {
            LOGGER.warn("  FileSystem already exists for {}. This might indicate a bug or an unclosed FileSystem.", zipFilePath, e);
        }
        catch (IOException e) {
            LOGGER.error("  Failed to open or read ZIP sound pack: '{}'", zipFilePath, e);
        }
    }


    private void loadSingleSoundPackLogic(Path packRootPathInFs, String baseDisplayName, boolean isZip) {
        String internalId = baseDisplayName.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        if (internalId.isEmpty()) {
            LOGGER.error("  Generated internal ID for pack '{}' is empty. Skipping.", baseDisplayName);
            return;
        }
        LOGGER.info("  Internal ID: {}, Base Display Name: {}, IsZip: {}", internalId, baseDisplayName, isZip);

        Path mcmetaPath = packRootPathInFs.resolve(PACK_METADATA_FILE);
        if (!Files.exists(mcmetaPath) || !Files.isRegularFile(mcmetaPath)) {
            LOGGER.warn("  Missing {} in pack: '{}'. Skipping this pack.", PACK_METADATA_FILE, baseDisplayName);
            return;
        }

        SoundPackInfo soundPackInfo;
        String assetId;

        try (Reader reader = Files.newBufferedReader(mcmetaPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject packMeta = root.getAsJsonObject("pack");
            if (packMeta == null) {
                LOGGER.warn("  Invalid {} format (missing 'pack' object) in pack: '{}'. Skipping.", PACK_METADATA_FILE, baseDisplayName);
                return;
            }

            Path assetsDir = packRootPathInFs.resolve("assets");
            if (!Files.exists(assetsDir) || !Files.isDirectory(assetsDir)) {
                LOGGER.warn("  Missing 'assets' directory in pack: '{}'. Skipping.", baseDisplayName);
                return;
            }

            List<String> assetSubDirs;
            try (Stream<Path> stream = Files.list(assetsDir)) {
                assetSubDirs = stream
                        .filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
            } catch (IOException e) {
                LOGGER.error("  Failed to list subdirectories in 'assets' for pack: '{}'. Skipping.", baseDisplayName, e);
                return;
            }

            if (assetSubDirs.isEmpty()) {
                LOGGER.warn("  No subdirectories found in 'assets' directory for pack: '{}'. Cannot determine assetId. Skipping.", baseDisplayName);
                return;
            }
            if (assetSubDirs.size() > 1) {
                LOGGER.warn("  Multiple subdirectories found in 'assets' directory for pack: '{}' ({}). Cannot determine unique assetId. Skipping.", baseDisplayName, assetSubDirs);
                return;
            }
            assetId = assetSubDirs.get(0);
            LOGGER.info("  Automatically determined assetId: '{}' for pack: '{}'", assetId, baseDisplayName);

            if (!assetId.matches("[a-z0-9_.-]+")) {
                LOGGER.warn("  Automatically determined Asset ID '{}' for pack '{}' contains invalid characters. Only lowercase a-z, 0-9, '_', '.', '-' are allowed. Skipping.", assetId, baseDisplayName);
                return;
            }

            String descriptionText = packMeta.has("description") ? packMeta.get("description").getAsString() : "No description for " + baseDisplayName;
            int packFormat = packMeta.has("pack_format") ? packMeta.get("pack_format").getAsInt() : -1;

            if (packFormat == -1) {
                LOGGER.warn("  Missing 'pack_format' in {} for pack: '{}'. Skipping.", PACK_METADATA_FILE, baseDisplayName);
                return;
            }

            soundPackInfo = new SoundPackInfo(
                    internalId,
                    Component.literal(baseDisplayName),
                    assetId,
                    Component.literal(descriptionText),
                    packFormat,
                    packRootPathInFs
            );

            Path iconPhysicalPath = packRootPathInFs.resolve("pack.png");
            if (Files.exists(iconPhysicalPath) && Files.isRegularFile(iconPhysicalPath)) {
                soundPackInfo.setIconFileSystemPath(iconPhysicalPath);
                try {
                    ResourceLocation iconRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, internalId + "/pack.png");
                    soundPackInfo.setIconLocation(iconRl);
                    LOGGER.info("  Set pack icon RL: {} with physical path: {}", iconRl, iconPhysicalPath);
                } catch (ResourceLocationException e) {
                    LOGGER.warn("  Could not create RL for pack icon (InternalID '{}'): {}", internalId, e.getMessage());
                }
            } else {
                LOGGER.info("  No pack.png found for pack (InternalID '{}') at: {}", internalId, iconPhysicalPath);
            }

            loadedSoundPacks.add(soundPackInfo); // ここで loadedSoundPacks に追加
            LOGGER.info("  Loaded SoundPack: DisplayName='{}', AssetID='{}' (auto-detected), Format: {}, InternalID='{}', IsZip: {}",
                    baseDisplayName, soundPackInfo.getAssetId(), packFormat, internalId, isZip);

        } catch (JsonParseException | IOException e) {
            LOGGER.error("  Failed to read or parse {} for pack: '{}'", PACK_METADATA_FILE, baseDisplayName, e);
            return;
        } catch (Exception e) {
            LOGGER.error("  Unexpected error while processing metadata for pack: '{}'", baseDisplayName, e);
            return;
        }

        // MusicDefinitionのロードはSoundPackInfoが確定した後
        LOGGER.debug("  SoundPackInfo for conditions: InternalID='{}', AssetID='{}', PackRoot='{}'",
                soundPackInfo.getId(), soundPackInfo.getAssetId(), soundPackInfo.getPackRootPath());
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
            definition.setSoundPackId(soundPackInfo.getId());

            Path absoluteOggPath = soundPackInfo.getAssetsDirectory().resolve(definition.getMusicFileInPack());
            if (!Files.exists(absoluteOggPath) || !Files.isRegularFile(absoluteOggPath)) {
                LOGGER.warn("  Sound file not found for definition in {}: {} (Expected at {})",
                        jsonPath.getFileName(), definition.getMusicFileInPack(), absoluteOggPath);
                return;
            }
            definition.setAbsoluteOggPath(absoluteOggPath);

            String assetId = soundPackInfo.getAssetId();
            String relativeOggPathFromPackAssets = definition.getMusicFileInPack();
            String soundEventKey = getSoundEventKey(relativeOggPathFromPackAssets, assetId);
            definition.setSoundEventKey(soundEventKey);

            try {
                ResourceLocation oggRLForName = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);
                definition.setOggResourceLocation(oggRLForName);

                String mapKeyPath = OGG_RESOURCE_SOUNDS_PREFIX + soundEventKey + ".ogg";
                ResourceLocation mapKeyRL = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, mapKeyPath);
                oggResourceMap.put(mapKeyRL, absoluteOggPath);

                if (definition.isValid()) {
                    allMusicDefinitions.add(definition);
                    musicDefinitionByEventKey.put(definition.getSoundEventKey(), definition);
                    LOGGER.debug("  Loaded music definition: File='{}', EventKey='{}', NameRL='{}', MapKeyRL='{}', OggPath='{}'",
                            definition.getMusicFileInPack(),
                            definition.getSoundEventKey(),
                            definition.getOggResourceLocation(),
                            mapKeyRL,
                            absoluteOggPath);
                } else {
                    LOGGER.warn("  Music definition from {} was parsed but deemed invalid. Def: {}", jsonPath, definition);
                }
            } catch (ResourceLocationException e) {
                LOGGER.warn("  Invalid RL components for AssetID '{}', file '{}'. Skipping. Error: {}",
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

    private static @NotNull String getSoundEventKey(String relativeOggPathFromPackAssets, String assetId) {
        String pathWithoutExtension = relativeOggPathFromPackAssets;
        if (pathWithoutExtension.toLowerCase().endsWith(".ogg")) {
            pathWithoutExtension = pathWithoutExtension.substring(0, pathWithoutExtension.length() - 4);
        }
        return (assetId + "/" + pathWithoutExtension)
                .toLowerCase()
                .replaceAll("[^a-z0-9_./-]", "_");
    }

    public MusicDefinition getMusicDefinitionByEventKey(String eventKey) {
        return musicDefinitionByEventKey.get(eventKey);
    }

    public List<SoundPackInfo> getLoadedSoundPacks() {
        return Collections.unmodifiableList(loadedSoundPacks);
    }

    public List<MusicDefinition> getActiveMusicDefinitionsSorted() {
        // パックの優先順位（packOrder のインデックス）を考慮してソート
        // packOrder の先頭（インデックス0）が最も優先度が高いとする
        return allMusicDefinitions.stream()
                .filter(def -> activeSoundPackIds.contains(def.getSoundPackId()))
                .sorted((a, b) -> {
                    int indexA = packOrder.indexOf(a.getSoundPackId());
                    int indexB = packOrder.indexOf(b.getSoundPackId());
                    
                    if (indexA != indexB) {
                        return Integer.compare(indexA, indexB); // インデックスが小さい（リストの上の）パックを優先
                    }
                    // 同じパック内、または同じ優先順位のパック間では、曲自体の priority で比較
                    return Integer.compare(b.getPriority(), a.getPriority());
                })
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
        this.activeSoundPackIds = new CopyOnWriteArrayList<>(ids); // UIからの変更を直接反映
        LOGGER.info("Active sound packs updated by UI (based on internalId): {}", this.activeSoundPackIds);
        saveActivePacksConfig(); // UIからの変更は即座に保存
    }

    public List<String> getActiveSoundPackIds() {
        return Collections.unmodifiableList(activeSoundPackIds);
    }

    public void setPackOrder(List<String> order) {
        this.packOrder = new CopyOnWriteArrayList<>(order);
        // loadedSoundPacks の順序もこれに合わせて更新する
        List<SoundPackInfo> sortedPacks = new ArrayList<>();
        for (String id : this.packOrder) {
            loadedSoundPacks.stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst()
                    .ifPresent(sortedPacks::add);
        }
        for (SoundPackInfo pack : loadedSoundPacks) {
            if (!sortedPacks.contains(pack)) {
                sortedPacks.add(pack);
            }
        }
        this.loadedSoundPacks.clear();
        this.loadedSoundPacks.addAll(sortedPacks);
        
        savePackOrderConfig();
    }

    public List<String> getPackOrder() {
        return Collections.unmodifiableList(packOrder);
    }

    public void onShutdown() {
        closeAllZipFileSystems();
    }
}