package com.zoma1101.music_player.soundpack; // パッケージは適宜調整してください

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject; // pack.mcmeta パース用
import com.google.gson.JsonParser; // pack.mcmeta パース用
import com.mojang.logging.LogUtils;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zoma1101.music_player.Music_Player.MOD_ID; // MOD_ID は適切に参照

public class SoundPackDataManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create(); // 必要なら setPrettyPrinting() など
    public static final Path SOUNDPACKS_BASE_DIR = Paths.get("soundpacks");
    private static final String PACK_METADATA_FILE = "pack.mcmeta";
    private static final String CONDITIONS_DIR_NAME = "conditions";

    // MOD ID を使ったサウンドイベントの名前空間
    public static final String SOUND_EVENT_NAMESPACE = MOD_ID.toLowerCase() + "_soundpacks";

    private final List<SoundPack> availableSoundPacks = new ArrayList<>();
    private final List<MusicDefinition> allMusicDefinitions = new ArrayList<>();
    // SoundEventLocation から OGG ファイルの絶対パスへのマッピング (DynamicSoundResourcePack で使用)
    private final Map<ResourceLocation, Path> soundEventToOggPathMap = new HashMap<>();

    // アクティブなサウンドパックのIDリスト (設定などで管理)
    private List<String> activeSoundPackIds = new ArrayList<>(); // 例: ["dragon_quest", "another_pack"]

    public SoundPackDataManager() {
        // 初期化時にロード処理を呼び出すなど
        // loadActiveSoundPackIdsFromConfig(); // 設定からアクティブなパックIDをロード
        // reloadSoundPacks();
    }

    public void reloadSoundPacks() {
        LOGGER.info("Reloading all sound packs from: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
        availableSoundPacks.clear();
        allMusicDefinitions.clear();
        soundEventToOggPathMap.clear();

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

        LOGGER.info("Finished reloading sound packs. Found {} packs, {} music definitions.",
                availableSoundPacks.size(), allMusicDefinitions.size());
    }

    private void loadSingleSoundPack(Path packRootDir) {
        String packId = packRootDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        LOGGER.info("Processing sound pack directory: {} (ID: {})", packRootDir.getFileName(), packId);

        // 1. pack.mcmeta の読み込み
        Path mcmetaPath = packRootDir.resolve(PACK_METADATA_FILE);
        if (!Files.exists(mcmetaPath) || !Files.isRegularFile(mcmetaPath)) {
            LOGGER.warn("  Missing {} in pack: {}. Skipping this pack.", PACK_METADATA_FILE, packId);
            return;
        }

        SoundPack soundPack;
        try (Reader reader = Files.newBufferedReader(mcmetaPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject packMeta = root.getAsJsonObject("pack");
            if (packMeta == null) {
                LOGGER.warn("  Invalid {} format (missing 'pack' object) in pack: {}. Skipping.", PACK_METADATA_FILE, packId);
                return;
            }
            String description = packMeta.has("description") ? packMeta.get("description").getAsString() : "No description";
            int packFormat = packMeta.has("pack_format") ? packMeta.get("pack_format").getAsInt() : -1; // 不正な値

            if (packFormat == -1) { // pack_format は必須と考える
                LOGGER.warn("  Missing 'pack_format' in {} for pack: {}. Skipping.", PACK_METADATA_FILE, packId);
                return;
            }

            soundPack = new SoundPack(packId, Component.literal(description), packFormat, packRootDir);
            availableSoundPacks.add(soundPack);
            LOGGER.info("  Loaded SoundPack metadata: '{}', format: {}", description, packFormat);

        } catch (Exception e) {
            LOGGER.error("  Failed to read or parse {} for pack {}: {}", PACK_METADATA_FILE, packId, e.getMessage(), e);
            return; // このパックの処理を中止
        }

        // 2. conditions/*.json の読み込み
        //    パス構造: soundpacks/任意の名前/assets/pack_id/conditions/~~.json
        Path conditionsDir = soundPack.getAssetsDirectory().resolve(CONDITIONS_DIR_NAME);
        if (!Files.exists(conditionsDir) || !Files.isDirectory(conditionsDir)) {
            LOGGER.info("  No conditions directory found at: {}. No music definitions will be loaded for this pack.", conditionsDir);
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> loadMusicDefinition(jsonPath, soundPack));
        } catch (IOException e) {
            LOGGER.error("  Error walking conditions directory {} for pack {}: {}", conditionsDir, packId, e.getMessage(), e);
        }
    }

    private void loadMusicDefinition(Path jsonPath, SoundPack soundPack) {
        try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            MusicDefinition definition = GSON.fromJson(reader, MusicDefinition.class);
            if (definition == null || definition.music == null || definition.music.isBlank()) {
                LOGGER.warn("  Invalid or incomplete music definition in file: {}. Missing 'music' field.", jsonPath);
                return;
            }

            definition.setSoundPackId(soundPack.getId());

            // "music" フィールドのパース: "pack_id/music/bgm_combat.ogg"
            String[] musicPathParts = definition.getMusicPathFromJson().split("/", 2);
            if (musicPathParts.length < 2) {
                LOGGER.warn("  Invalid 'music' path format in {}: '{}'. Expected 'pack_id/path/to/sound.ogg'.", jsonPath, definition.getMusicPathFromJson());
                return;
            }
            String musicPathPackId = musicPathParts[0];
            String relativeOggPath = musicPathParts[1]; // 例: "music/bgm_combat.ogg"

            if (!musicPathPackId.equals(soundPack.getId())) {
                LOGGER.warn("  'music' path in {} contains pack_id '{}' which does not match current sound pack '{}'. Skipping.",
                        jsonPath, musicPathPackId, soundPack.getId());
                return;
            }
            definition.setRelativeOggPathInPack(relativeOggPath);


            // OGGファイルの絶対パスを構築
            // soundpacks/任意の名前/assets/pack_id/music/bgm_combat.ogg
            Path absoluteOggPath = soundPack.getAssetsDirectory().resolve(relativeOggPath);
            if (!Files.exists(absoluteOggPath) || !Files.isRegularFile(absoluteOggPath)) {
                LOGGER.warn("  Sound file not found for definition in {}: {} (Expected at {})",
                        jsonPath.getFileName(), definition.getMusicPathFromJson(), absoluteOggPath);
                return;
            }
            definition.setAbsoluteOggPath(absoluteOggPath);

            // サウンドイベントの ResourceLocation を生成
            // パス部分は pack_id/music/bgm_combat (拡張子なし)
            String eventPathName = soundPack.getId() + "/" + relativeOggPath.substring(0, relativeOggPath.lastIndexOf('.'));
            try {
                ResourceLocation eventLoc = ResourceLocation.fromNamespaceAndPath(SOUND_EVENT_NAMESPACE, eventPathName);
                definition.setSoundEventLocation(eventLoc);

                if (definition.isValid()) {
                    allMusicDefinitions.add(definition);
                    soundEventToOggPathMap.put(eventLoc, absoluteOggPath);
                    LOGGER.debug("  Loaded music definition: {}, event: {}", definition.getMusicPathFromJson(), eventLoc);
                } else {
                    LOGGER.warn("  Music definition from {} was parsed but deemed invalid after processing.", jsonPath);
                }

            } catch (ResourceLocationException e) {
                LOGGER.warn("  Invalid characters in generated sound event path '{}' for pack {}. Skipping definition from {}.",
                        eventPathName, soundPack.getId(), jsonPath, e);
            }

        } catch (Exception e) {
            LOGGER.error("  Failed to parse or process music definition file: {}", jsonPath, e);
        }
    }

    // --- データアクセス用メソッド ---

    public List<SoundPack> getAvailableSoundPacks() {
        return List.copyOf(availableSoundPacks);
    }

    /**
     * アクティブなサウンドパックに含まれ、優先度順にソートされたMusicDefinitionのリストを取得します。
     */
    public List<MusicDefinition> getActiveMusicDefinitionsSorted() {
        return allMusicDefinitions.stream()
                .filter(def -> activeSoundPackIds.contains(def.getSoundPackId()))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority())) // 優先度高い順
                .collect(Collectors.toList());
    }

    public Map<ResourceLocation, Path> getSoundEventToOggPathMap() {
        // アクティブなパックのサウンドのみをフィルタリングして返すことも検討
        // ここでは簡単のため全て返す (DynamicSoundResourcePack側でフィルタリングするならそれでも良い)
        return Map.copyOf(soundEventToOggPathMap);
    }

    /**
     * 特定のサウンドパックIDに対応するサウンドイベントとOGGパスのマッピングを取得します。
     * DynamicSoundResourcePack のコンストラクタなどで使用します。
     */
    public Map<ResourceLocation, Path> getSoundsForPack(String packId) {
        Map<ResourceLocation, Path> filteredMap = new HashMap<>();
        String expectedEventPathPrefix = packId + "/"; // イベントパスは "pack_id/..." で始まる

        for (Map.Entry<ResourceLocation, Path> entry : soundEventToOggPathMap.entrySet()) {
            ResourceLocation eventLocation = entry.getKey();
            // 名前空間が一致し、かつパスが期待されるプレフィックスで始まるか
            if (eventLocation.getNamespace().equals(SOUND_EVENT_NAMESPACE) &&
                    eventLocation.getPath().startsWith(expectedEventPathPrefix)) {
                filteredMap.put(eventLocation, entry.getValue());
            }
        }
        return filteredMap;
    }


    // --- アクティブなサウンドパックの管理 (例) ---
    public void setActiveSoundPackIds(List<String> ids) {
        this.activeSoundPackIds = new ArrayList<>(ids);
        LOGGER.info("Active sound packs updated: {}", activeSoundPackIds);
        // 必要であれば、ここでキャッシュの再構築などを行う
    }

    public List<String> getActiveSoundPackIds() {
        return List.copyOf(activeSoundPackIds);
    }

    // (設定ファイルからアクティブなIDをロード/セーブするメソッドもここに追加すると良いでしょう)
}