package com.zoma1101.music_player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zoma1101.music_player.config.SoundDefinition;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;


public class SoundPackLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
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
        String packId = packDir.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9_.-]", "_"); // パックIDを生成
        LOGGER.info("Loading SoundPack: {} (ID: {})", packDir.getFileName(), packId);
        discoveredPackIds.add(packId);

        Path conditionsDir = packDir.resolve("assets/music_player/conditions");
        Path musicBaseDir = packDir.resolve("assets/music_player"); // musicフォルダの親

        if (!Files.isDirectory(conditionsDir)) {
            LOGGER.warn("Conditions directory not found in pack {}: {}", packId, conditionsDir);
            return;
        }

        try (Stream<Path> jsonFiles = Files.walk(conditionsDir)) {
            jsonFiles.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                    .forEach(jsonPath -> {
                        try (Reader reader = Files.newBufferedReader(jsonPath)) {
                            SoundDefinition definition = GSON.fromJson(reader, SoundDefinition.class);
                            if (definition != null && definition.isValid()) {
                                definition.soundPackId = packId;

                                // musicPath を assets/music_player からの相対パスに正規化 (例: music/hoge.ogg)
                                Path relativeMusicPath = Paths.get(definition.musicPath.replaceFirst("^[/\\\\]", "")); // 先頭の/を削除
                                definition.musicPath = relativeMusicPath.toString().replace('\\', '/'); // パス区切りを / に統一

                                // 対応するOGGファイルのフルパスを取得
                                Path absoluteOggPath = musicBaseDir.resolve(definition.musicPath);
                                if (!Files.exists(absoluteOggPath)) {
                                    LOGGER.error("Music file not found for definition {} in pack {}: {}", jsonPath.getFileName(), packId, absoluteOggPath);
                                    return; // OGGファイルが見つからない定義は無視
                                }
                                definition.absoluteMusicPath = absoluteOggPath;


                                // この音楽ファイルに対応するユニークな SoundEvent ResourceLocation を生成
                                String eventPath = (packId + "/" + definition.musicPath).replaceAll("[^a-z0-9_./-]", "_");
                                ResourceLocation soundEventLocation = fromNamespaceAndPath(Music_Player.MOD_ID + "_soundpacks", eventPath); // 専用の名前空間を使う

                                definition.soundEventLocation = soundEventLocation;

                                // マッピングに追加（同じ音楽ファイルが複数定義で使われる場合を考慮）
                                if (!musicPathToEventMap.containsKey(definition.musicPath + "@" + packId)) { // パックごとに区別
                                    musicPathToEventMap.put(definition.musicPath + "@" + packId, soundEventLocation);
                                    eventToPathMap.put(soundEventLocation, definition.absoluteMusicPath);
                                    LOGGER.debug("Mapped sound event: {} -> {}", soundEventLocation, definition.absoluteMusicPath);
                                } else {
                                    // 既存のマッピングを使用
                                    definition.soundEventLocation = musicPathToEventMap.get(definition.musicPath + "@" + packId);
                                }


                                loadedDefinitions.add(definition);
                                LOGGER.debug("Loaded definition: {}", definition);

                            } else {
                                LOGGER.warn("Invalid or incomplete definition in file: {}", jsonPath);
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to parse or process JSON definition file: {}", jsonPath, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Error reading conditions directory in pack {}: {}", packId, conditionsDir, e);
        }
    }
}