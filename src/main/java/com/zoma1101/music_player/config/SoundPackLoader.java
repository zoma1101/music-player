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
        loadedDefinitions.sort((a, b) -> Integer.compare(b.priority, a.priority)); // Priority高い順
    }

    private static void loadSinglePack(Path packDir) {
        String packId = packDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        LOGGER.info("Loading SoundPack: {} (ID: {})", packDir.getFileName(), packId);
        discoveredPackIds.add(packId);

        Path musicBaseDir = packDir.resolve("assets").resolve(packId);
        Path conditionsDir = packDir.resolve("assets").resolve(MOD_ID).resolve("conditions");


        if (!Files.isDirectory(conditionsDir)) {
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> {
                        try (Reader reader = Files.newBufferedReader(jsonPath)) {
                            SoundDefinition definition = GSON.fromJson(reader, SoundDefinition.class);
                            if (definition != null && definition.isValid()) {
                                definition.soundPackId = packId;

                                // musicPath を正規化 (例: "music/hoge.ogg")
                                // 先頭の/や\を削除し、パス区切りを / に統一
                                definition.musicPath = definition.musicPath.replaceFirst("^[\\\\/]+", "").replace('\\', '/');
                                // ★ 修正 1 の musicBaseDir を使って物理 OGG パスを計算 ★
                                Path absoluteOggPath = musicBaseDir.resolve(definition.musicPath);
                                // 物理ファイルの存在チェック
                                if (!Files.exists(absoluteOggPath)) {
                                    return;
                                }
                                definition.absoluteMusicPath = absoluteOggPath;

                                // ★ 修正 2: イベントパスに "sounds/" を追加 ★
                                // definition.musicPath ("music/hoge.ogg") から拡張子を除去 -> "music/hoge"
                                String relativeMusicPathWithoutOgg = definition.musicPath.substring(0, definition.musicPath.lastIndexOf('.'));
                                // 仮想的なイベントパスを生成 -> "test/sounds/music/hoge"
                                String virtualEventPath = packId + "/sounds/" + relativeMusicPathWithoutOgg;
                                String eventNamespace = MOD_ID + "_soundpacks"; // 専用の名前空間
                                ResourceLocation soundEventLocation;
                                try {
                                    soundEventLocation = fromNamespaceAndPath(eventNamespace, virtualEventPath);
                                } catch (ResourceLocationException e) {
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
                                }
                                loadedDefinitions.add(definition);
                            }
                        } catch (Exception e) {
                            LOGGER.error("  Failed to parse or process JSON definition file: {}", jsonPath, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Error reading conditions directory {} in pack {}: {}", conditionsDir, packId, e);
        }
    }

    public static Map<ResourceLocation, Path> filterSoundsForPack(String packId) {
        Map<ResourceLocation, Path> filteredMap = new HashMap<>();
        String eventNamespace = MOD_ID + "_soundpacks"; // MOD_ID を使用
        String expectedPathPrefix = packId + "/sounds/"; // 例: "test/sounds/"
        // グローバルな eventToPathMap をループ
        for (Map.Entry<ResourceLocation, Path> entry : eventToPathMap.entrySet()) {
            ResourceLocation eventLocation = entry.getKey();
            // 名前空間とパスのプレフィックスをチェック
            if (eventLocation.getNamespace().equals(eventNamespace) && eventLocation.getPath().startsWith(expectedPathPrefix)) {
                filteredMap.put(eventLocation, entry.getValue());
            }
        }
        return filteredMap;
    }

    public static List<SoundDefinition> getLoadedDefinitions() {
        return Collections.unmodifiableList(loadedDefinitions);
    }
}