package com.zoma1101.music_player.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import static com.zoma1101.music_player.Music_Player.MOD_ID;
import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;


public class SoundPackLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Gson GSON = new GsonBuilder().create();
    public static final Path SOUNDPACKS_DIR = Paths.get("soundpacks"); // Minecraftディレクトリからの相対パス

    // 読み込んだ定義とサウンドイベントのマッピングを保持
    public static final List<SoundDefinition> loadedDefinitions = new ArrayList<>();
    public static final Map<String, ResourceLocation> musicPathToEventMap = new HashMap<>();
    public static final Map<ResourceLocation, Path> eventToPathMap = new HashMap<>(); // イベントIDからoggパスへ
    public static final List<String> discoveredPackIds = new ArrayList<>(); // 発見したパックID

    public static void loadSoundPacks() {
        LOGGER.info("Starting SoundPack loading process...");
        loadedDefinitions.clear();
        musicPathToEventMap.clear();
        eventToPathMap.clear();
        discoveredPackIds.clear();

        try {
            if (!Files.exists(SOUNDPACKS_DIR)) {
                LOGGER.info("Soundpacks directory does not exist, creating: {}", SOUNDPACKS_DIR.toAbsolutePath());
                Files.createDirectories(SOUNDPACKS_DIR);
            }
            if (!Files.isDirectory(SOUNDPACKS_DIR)) {
                LOGGER.error("Soundpacks path exists but is not a directory: {}", SOUNDPACKS_DIR.toAbsolutePath());
                return;
            }

            try (Stream<Path> packDirs = Files.list(SOUNDPACKS_DIR)) {
                packDirs.filter(Files::isDirectory).forEach(SoundPackLoader::loadSinglePack);
            }

        } catch (IOException e) {
            LOGGER.error("Error accessing or listing soundpacks directory: {}", SOUNDPACKS_DIR.toAbsolutePath(), e);
        }
        LOGGER.info("Finished SoundPack loading. Loaded {} definitions from {} packs.", loadedDefinitions.size(), discoveredPackIds.size());
        // ロード後に優先度でソートしておくとマッチング時に効率的かも
        loadedDefinitions.sort((a, b) -> Integer.compare(b.priority, a.priority)); // Priority高い順
    }

    private static void loadSinglePack(Path packDir) {
        String packId = packDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        LOGGER.info("Loading SoundPack: {} (ID: {})", packDir.getFileName(), packId);
        discoveredPackIds.add(packId);

        // --- Path Definitions ---
        // ★ 修正 1: OGGファイルを探す正しいベースパス (assets/<packId>) ★
        // 例: .../soundpacks/test/assets/test/
        Path musicBaseDir = packDir.resolve("assets").resolve(packId);
        LOGGER.debug("  Music Base Dir set to: {}", musicBaseDir);

        // conditions JSON ファイルへのパス (ユーザーのコードに合わせて assets/music_player/ を維持)
        // 例: .../soundpacks/test/assets/music_player/conditions/
        // Music_Player.MOD_ID を使うのがより安全
        Path conditionsDir = packDir.resolve("assets").resolve(MOD_ID).resolve("conditions");
        LOGGER.debug("  Conditions Dir set to: {}", conditionsDir);


        if (!Files.isDirectory(conditionsDir)) {
            LOGGER.warn("Conditions directory not found in pack {}: {}", packId, conditionsDir);
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> {
                        LOGGER.debug("  Processing definition file: {}", jsonPath.getFileName()); // ファイル名をログに
                        try (Reader reader = Files.newBufferedReader(jsonPath)) {
                            SoundDefinition definition = GSON.fromJson(reader, SoundDefinition.class);
                            if (definition != null && definition.isValid()) {
                                definition.soundPackId = packId;

                                // musicPath を正規化 (例: "music/hoge.ogg")
                                // 先頭の/や\を削除し、パス区切りを / に統一
                                definition.musicPath = definition.musicPath.replaceFirst("^[\\\\/]+", "").replace('\\', '/');
                                LOGGER.debug("    Normalized relativeMusicPath: {}", definition.musicPath); // ログ追加

                                // ★ 修正 1 の musicBaseDir を使って物理 OGG パスを計算 ★
                                Path absoluteOggPath = musicBaseDir.resolve(definition.musicPath);
                                LOGGER.debug("    Calculated absoluteOggPath: {}", absoluteOggPath); // ログ追加

                                // 物理ファイルの存在チェック
                                if (!Files.exists(absoluteOggPath)) {
                                    // ★ エラーではなくWARNにして、処理を続ける ★
                                    LOGGER.warn("    Music file not found for definition {} in pack {}: {}", jsonPath.getFileName(), packId, absoluteOggPath);
                                    return; // この定義だけスキップ
                                }
                                definition.absoluteMusicPath = absoluteOggPath;
                                LOGGER.debug("    Verified absoluteOggPath exists."); // ログ追加


                                // ★ 修正 2: イベントパスに "sounds/" を追加 ★
                                // definition.musicPath ("music/hoge.ogg") から拡張子を除去 -> "music/hoge"
                                String relativeMusicPathWithoutOgg = definition.musicPath.substring(0, definition.musicPath.lastIndexOf('.'));
                                // 仮想的なイベントパスを生成 -> "test/sounds/music/hoge"
                                String virtualEventPath = packId + "/sounds/" + relativeMusicPathWithoutOgg;
                                // 安全のためサニタイズするならここだが、基本不要なはず
                                // virtualEventPath = virtualEventPath.replaceAll("[^a-z0-9_./-]", "_");
                                LOGGER.debug("    Generated virtualEventPath: {}", virtualEventPath); // ログ追加

                                // ★ 修正 2 の virtualEventPath を使って ResourceLocation を生成 ★
                                String eventNamespace = MOD_ID + "_soundpacks"; // 専用の名前空間
                                ResourceLocation soundEventLocation;
                                try {
                                    // new ResourceLocation を使うのが標準的
                                    soundEventLocation = fromNamespaceAndPath(eventNamespace, virtualEventPath);
                                } catch (ResourceLocationException e) {
                                    LOGGER.error("    Failed to create valid ResourceLocation (EventID): ns='{}' path='{}', skipping definition.", eventNamespace, virtualEventPath, e);
                                    return; // この定義をスキップ
                                }

                                definition.soundEventLocation = soundEventLocation;

                                // ★ 修正 3: マップに新しい EventID と物理パスを格納 ★
                                // musicPathToEventMap はおそらく不要になったのでコメントアウト or 削除
                                // if (!musicPathToEventMap.containsKey(definition.musicPath + "@" + packId)) {
                                //     musicPathToEventMap.put(definition.musicPath + "@" + packId, soundEventLocation);
                                // } else {
                                //     definition.soundEventLocation = musicPathToEventMap.get(definition.musicPath + "@" + packId);
                                // }

                                // eventToPathMap が DynamicSoundResourcePack で使われる
                                if (!eventToPathMap.containsKey(soundEventLocation)) {
                                    eventToPathMap.put(soundEventLocation, definition.absoluteMusicPath);
                                    // ★ ログで確認 ★ (INFO レベルに変更)
                                    LOGGER.info("    -> Creating sound event mapping: EventID='{}', OggPath='{}'", soundEventLocation, definition.absoluteMusicPath);
                                } else {
                                    // 既に同じイベントIDが存在する場合（通常は設計上起こらないはず）
                                    LOGGER.warn("    Duplicate sound event ID detected while mapping: {}", soundEventLocation);
                                    // 既存のマッピングを使う場合、definition.soundEventLocation は既に正しいはずなので何もしない
                                }

                                loadedDefinitions.add(definition);
                                LOGGER.debug("    Successfully loaded definition with EventID: {}", soundEventLocation); // 成功ログを改善

                            } else {
                                LOGGER.warn("    Invalid or incomplete definition in file: {}", jsonPath.getFileName());
                            }
                        } catch (Exception e) {
                            LOGGER.error("  Failed to parse or process JSON definition file: {}", jsonPath, e);
                        }
                    });
        } catch (IOException e) {
            // エラーログを改善
            LOGGER.error("Error reading conditions directory {} in pack {}: {}", conditionsDir, packId, e);
        }
    }

    public static Map<ResourceLocation, Path> filterSoundsForPack(String packId) {
        LOGGER.debug("Filtering sound map for packId: {}", packId);
        Map<ResourceLocation, Path> filteredMap = new HashMap<>();

        // ★ 修正: イベントパスのプレフィックスを "sounds/" を含めてチェック ★
        // eventToPathMap のキー (ResourceLocation) は music_player_soundpacks:test/sounds/music/alpha_day のようになっているはず
        String eventNamespace = MOD_ID + "_soundpacks"; // MOD_ID を使用
        String expectedPathPrefix = packId + "/sounds/"; // 例: "test/sounds/"

        LOGGER.debug("  Filtering based on namespace '{}' and prefix '{}'", eventNamespace, expectedPathPrefix);

        int count = 0;
        // グローバルな eventToPathMap をループ
        for (Map.Entry<ResourceLocation, Path> entry : eventToPathMap.entrySet()) {
            ResourceLocation eventLocation = entry.getKey();
            // 名前空間とパスのプレフィックスをチェック
            if (eventLocation.getNamespace().equals(eventNamespace) && eventLocation.getPath().startsWith(expectedPathPrefix)) {
                // 条件に一致すれば、新しいマップに追加
                filteredMap.put(eventLocation, entry.getValue());
                // TRACE レベルは通常表示されないので、デバッグしたい場合は DEBUG に変更
                LOGGER.trace("    -> Added entry: {}", eventLocation);
                count++;
            } else {
                LOGGER.trace("    -> Skipped entry (namespace/prefix mismatch): {}", eventLocation);
            }
        }
        // フィルタリング結果の数をログに出力
        LOGGER.debug("  Finished filtering for pack: {}, Found {} sounds.", packId, count);
        return filteredMap;
    }

    public static List<SoundDefinition> getLoadedDefinitions() {
        // Collections.unmodifiableList でラップして返すのが安全
        return Collections.unmodifiableList(loadedDefinitions);

        // もしくは単純にリストをそのまま返すことも可能ですが、
        // 外部クラスからリストの中身を変更できてしまう可能性があります。
        // return loadedDefinitions;
    }
}