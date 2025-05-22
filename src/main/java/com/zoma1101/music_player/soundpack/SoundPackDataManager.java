package com.zoma1101.music_player.soundpack; // パッケージは適宜調整してください

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.MusicDefinition;
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

import static com.zoma1101.music_player.Music_Player.MOD_ID; // MOD_ID は適切に参照

public class SoundPackDataManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    // GsonBuilder().setPrettyPrinting().create() で整形して出力するとデバッグしやすい
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path SOUNDPACKS_BASE_DIR = Paths.get("soundpacks");
    private static final String PACK_METADATA_FILE = "pack.mcmeta";
    private static final String CONDITIONS_DIR_NAME = "conditions";
    // private static final String MUSIC_DIR_NAME = "music"; // music ファイルが置かれる想定のディレクトリ (MusicDefinitionのパスで指定される)

    // SoundPackInfo のリスト (pack.mcmeta の情報)
    private final List<SoundPack> loadedSoundPacks = new ArrayList<>();
    // ロードされた全ての MusicDefinition のリスト
    private final List<MusicDefinition> allMusicDefinitions = new ArrayList<>();
    // OGGファイルを提供するためのマッピング (MODのリソースとして解決されるID -> OGGの絶対パス)
    // ModSoundResourcePack がこのマップを使用してOGGファイルを提供する
    private final Map<ResourceLocation, Path> oggResourceMap = new HashMap<>();

    // アクティブなサウンドパックのIDリスト (将来的には設定で管理)
    // 初期状態ではロードされた全てのパックをアクティブとして扱う
    private final List<String> activeSoundPackIds = new ArrayList<>();

    public SoundPackDataManager() {
        // コンストラクタでは特にロードは行わない
        // ロードはクライアントセットアップイベントやリロードイベントで行う
    }

    /**
     * soundpacks ディレクトリからサウンドパックを発見し、メタデータと音楽定義をロードします。
     * クライアントのリロードイベントなどで呼び出されます。
     */
    public void discoverAndLoadPacks() {
        LOGGER.info("Discovering and loading sound packs from: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
        // 既存のデータをクリア
        loadedSoundPacks.clear();
        allMusicDefinitions.clear();
        oggResourceMap.clear();
        activeSoundPackIds.clear(); // リロード時にアクティブリストもリセット (デフォルトで全てアクティブにするため)

        // soundpacks ディレクトリが存在しない場合は作成
        if (!Files.exists(SOUNDPACKS_BASE_DIR)) {
            try {
                Files.createDirectories(SOUNDPACKS_BASE_DIR);
                LOGGER.info("Created soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create soundpacks directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
                return;
            }
        }
        // soundpacks パスがディレクトリでない場合はエラー
        if (!Files.isDirectory(SOUNDPACKS_BASE_DIR)) {
            LOGGER.error("Soundpacks path exists but is not a directory: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath());
            return;
        }

        // soundpacks ディレクトリ直下の各ディレクトリをサウンドパックとして処理
        try (Stream<Path> packDirs = Files.list(SOUNDPACKS_BASE_DIR)) {
            packDirs.filter(Files::isDirectory).forEach(this::loadSingleSoundPack);
        } catch (IOException e) {
            LOGGER.error("Error listing sound pack directories in: {}", SOUNDPACKS_BASE_DIR.toAbsolutePath(), e);
        }

        // デフォルトではロードされた全てのパックをアクティブにする (テスト用)
        // 将来的にはGUIやコンフィグで選択できるようにする
        if (!loadedSoundPacks.isEmpty()) {
            activeSoundPackIds.addAll(loadedSoundPacks.stream().map(SoundPack::getId).toList());
            LOGGER.info("Activated all loaded sound packs by default: {}", activeSoundPackIds);
        }

        LOGGER.info("Finished loading sound packs. Found {} packs, {} music definitions, {} OGG resources.",
                loadedSoundPacks.size(), allMusicDefinitions.size(), oggResourceMap.size());
    }

    /**
     * 指定されたサウンドパックディレクトリから pack.mcmeta と音楽定義をロードします。
     */
    private void loadSingleSoundPack(Path packRootDir) {
        // ディレクトリ名をパックIDとして使用 (小文字化し、不正な文字を置換)
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
            // description は Component として扱うのが望ましいが、ここではシンプルにStringとして取得
            String description = packMeta.has("description") ? packMeta.get("description").getAsString() : "No description for " + packId;
            int packFormat = packMeta.has("pack_format") ? packMeta.get("pack_format").getAsInt() : -1;

            if (packFormat == -1) {
                LOGGER.warn("  Missing 'pack_format' in {} for pack: {}. Skipping.", PACK_METADATA_FILE, packId);
                return;
            }

            // SoundPack オブジェクトを作成し、リストに追加
            soundPack = new SoundPack(packId, Component.literal(description), packFormat, packRootDir);
            loadedSoundPacks.add(soundPack);
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

        // conditions ディレクトリ以下の全ての .json ファイルを走査
        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> loadMusicDefinition(jsonPath, soundPack));
        } catch (IOException e) {
            LOGGER.error("  Error walking conditions directory {} for pack {}: {}", conditionsDir, packId, e.getMessage(), e);
        }
    }

    /**
     * 指定されたJSONファイルから単一の MusicDefinition をロードします。
     *
     * @param jsonPath  音楽定義JSONファイルのパス
     * @param soundPack この定義が属する SoundPack
     */
    private void loadMusicDefinition(Path jsonPath, SoundPack soundPack) {
        try (Reader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            // JSONを MusicDefinition オブジェクトに変換
            MusicDefinition definition = GSON.fromJson(reader, MusicDefinition.class);

            // 必須フィールドのチェック
            // MusicDefinition クラスに public String music; フィールドと getMusicFileInPack() メソッドがある前提
            if (definition == null || definition.getMusicFileInPack() == null || definition.getMusicFileInPack().isBlank()) {
                LOGGER.warn("  Invalid or incomplete music definition in file: {}. Missing 'music' field.", jsonPath);
                return;
            }

            // MusicDefinition に所属サウンドパックIDをセット
            definition.setSoundPackId(soundPack.getId());

            // OGGファイルの絶対パスを構築
            // JSONの "music" フィールドは "music/bgm_combat.ogg" のようなパック内の相対パスを想定
            // 絶対パス: soundpacks/pack_id/assets/pack_id/music/bgm_combat.ogg
            Path absoluteOggPath = soundPack.getAssetsDirectory().resolve(definition.getMusicFileInPack());

            // OGGファイルが存在するかチェック
            if (!Files.exists(absoluteOggPath) || !Files.isRegularFile(absoluteOggPath)) {
                LOGGER.warn("  Sound file not found for definition in {}: {} (Expected at {})",
                        jsonPath.getFileName(), definition.getMusicFileInPack(), absoluteOggPath);
                return;
            }
            // MusicDefinition に絶対パスをセット
            definition.setAbsoluteOggPath(absoluteOggPath);

            // sounds.json のトップレベルキー (SoundEventKey) を生成
            String soundEventKey = getString(soundPack, definition);

            // MusicDefinition に SoundEventKey をセット
            definition.setSoundEventKey(soundEventKey);

            // ModSoundResourcePack がOGGファイルを提供するための ResourceLocation を生成
            // 形式: "modid:pack_id/path/to/sound.ogg"
            // これは sounds.json の "sounds": [{"name": "..."}] で使われるID
            String oggResourcePath = soundPack.getId() + "/" + definition.getMusicFileInPack(); // 例: "pack_id/music/bgm_combat.ogg"
            // ResourceLocation として有効な文字のみを使用するようにクリーンアップ
            oggResourcePath = oggResourcePath.toLowerCase().replaceAll("[^a-z0-9_./-]", "_");

            try {
                ResourceLocation oggRL = ResourceLocation.fromNamespaceAndPath(MOD_ID, oggResourcePath);
                // MusicDefinition に OGG ResourceLocation をセット
                definition.setOggResourceLocation(oggRL);

                // MusicDefinition が最終的に有効かチェックし、リストとマップに追加
                if (definition.isValid()) {
                    allMusicDefinitions.add(definition);
                    // OGGファイル提供用のマップに登録
                    oggResourceMap.put(oggRL, absoluteOggPath);
                    LOGGER.debug("  Loaded music definition: {}, eventKey: {}, oggRL: {}",
                            definition.getMusicFileInPack(), definition.getSoundEventKey(), definition.getOggResourceLocation());
                } else {
                    // isValid() が false の場合 (例: 設定漏れなど)
                    LOGGER.warn("  Music definition from {} was parsed but deemed invalid after processing. Definition: {}", jsonPath, definition);
                }

            } catch (ResourceLocationException e) {
                // 生成された ResourceLocation が不正な場合
                LOGGER.warn("  Invalid characters in generated OGG ResourceLocation path '{}' for pack {}. Skipping definition from {}. Error: {}",
                        oggResourcePath, soundPack.getId(), jsonPath, e.getMessage());
            }

        } catch (Exception e) {
            // JSONパースやファイル読み込みエラーなど
            LOGGER.error("  Failed to parse or process music definition file: {}", jsonPath, e);
        }
    }

    private static @NotNull String getString(SoundPack soundPack, MusicDefinition definition) {
        String relativeOggPathWithoutExt = definition.getMusicFileInPack();
        // 拡張子 .ogg を取り除く
        if (relativeOggPathWithoutExt.toLowerCase().endsWith(".ogg")) {
            relativeOggPathWithoutExt = relativeOggPathWithoutExt.substring(0, relativeOggPathWithoutExt.length() - 4);
        }
        // ResourceLocation のパス部分として使用するため、小文字化し不正な文字を置換
        String soundEventPath = soundPack.getId() + "/" + relativeOggPathWithoutExt;
        // ResourceLocation の形式 "namespace:path" にするため、MOD_ID を名前空間として付与
        String soundEventKey = MOD_ID + ":" + soundEventPath;
        // ResourceLocation として有効な文字のみを使用するようにクリーンアップ
        soundEventKey = soundEventKey.toLowerCase().replaceAll("[^a-z0-9_./:-]", "_");
        return soundEventKey;
    }

}