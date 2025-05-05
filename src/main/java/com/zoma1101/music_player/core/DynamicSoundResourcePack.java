package com.zoma1101.music_player.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.zoma1101.music_player.config.SoundPackLoader.GSON;
import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;

public class DynamicSoundResourcePack extends AbstractPackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String packId; // このリソースパック(SoundPack)のユニークID (例: "mysoundpack1")
    private final Path soundPackBasePath; // 元のSoundPackフォルダへの絶対パス
    private final Path assetsBasePath; // assets/music_player へのパス

    private final Map<ResourceLocation, Path> soundEventToOggPathMap;

    private static final int PACK_FORMAT = 15;

    /**
     * コンストラクタ
     * @param packId このパックのID
     * @param soundPackBasePath 元のSoundPackフォルダのパス
     * @param soundsForThisPack このパックが提供するSoundEvent -> OGG Path のマッピング
     */
    public DynamicSoundResourcePack(String packId, Path soundPackBasePath, Map<ResourceLocation, Path> soundsForThisPack) {
        super(packId, false);

        this.packId = packId;
        this.soundPackBasePath = soundPackBasePath; // 例: .../soundpacks/test
        this.assetsBasePath = soundPackBasePath.resolve("assets").resolve(this.packId);
        this.soundEventToOggPathMap = Collections.unmodifiableMap(new HashMap<>(soundsForThisPack));
        // ★ ログも更新して新しいパスを表示
        LOGGER.info("Initialized DynamicSoundResourcePack for ID: {} (Provides {} sounds), Assets Base: {}",
                this.packId, this.soundEventToOggPathMap.size(), this.assetsBasePath);
    }
    // --- リソース取得のコアメソッド (ResourceLocationベース) ---

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType != PackType.CLIENT_RESOURCES) return null;
        String namespace = location.getNamespace();
        String path = location.getPath(); // 例: "sound/music/alpha_forest_day.ogg"

        LOGGER.info("[{}] getResource called for: {}", this.packId, location);

        // --- 共通 sounds.json のチェック (変更なし) ---
        final String commonSoundNamespace = Music_Player.MOD_ID + "_soundpacks";
        if (namespace.equals(commonSoundNamespace) && path.equals("sounds.json")) {
            LOGGER.info("[{}] Providing generated sounds.json for namespace '{}'", this.packId, namespace);
            return generateSoundsJsonIoSupplier();
        }

        // --- OGG ファイルリクエストのチェック ---
        LOGGER.info("[{}] Checking if request matches OGG pattern: ns='{}', path='{}'", this.packId, namespace, path);
        // ★ "sounds/music/" プレフィックスをチェック ★
        if (namespace.equals(this.packId)
                && path.startsWith("sounds/music/")
                && path.endsWith(".ogg"))
        {
            LOGGER.info("[{}] Matched OGG request (sounds/music/) in getResource: {}", this.packId, location);
            // findActualOggPath が "sounds/" プレフィックスを扱えるように修正が必要 (下記 E)
            Path absoluteOggPath = findActualOggPath(location);
            if (absoluteOggPath != null && Files.exists(absoluteOggPath) && Files.isRegularFile(absoluteOggPath)) {
                LOGGER.info("[{}] OGG file found by findActualOggPath for: {}", this.packId, location);
                return () -> Files.newInputStream(absoluteOggPath);
            } else {
                LOGGER.warn("[{}] OGG file request matched, but findActualOggPath did not find/verify the file for: {}", this.packId, location);
                return null;
            }
        }
        // 他の return null はそのまま
        return null;
    }

    // --- ルートリソース取得 ---

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... pathParts) {
        String joinedPath = String.join("/", pathParts);
        // LOGGER.trace("getRootResource Request in pack [{}]: {}", this.packId, joinedPath); // デバッグ用

        // pack.mcmeta のみルートで提供
        if ("pack.mcmeta".equals(joinedPath)) {
            return generatePackMcmetaIoSupplier();
        }

        return null; // 他のルートファイルは提供しない
    }




    @Override
    public void listResources(PackType packType, String namespace, String pathPrefix, ResourceOutput resourceOutput) {
        if (packType != PackType.CLIENT_RESOURCES) return;

        LOGGER.info("[{}] listResources: Handling request. ns='{}', pathPrefix='{}'", this.packId, namespace, pathPrefix);
        if (namespace.equals(this.packId)) {
            if (pathPrefix.equals("sounds")) {
                LOGGER.info("[{}] listResources: Starting OGG loop for 'sounds' request. Map size: {}", this.packId, this.soundEventToOggPathMap.size());
                if (this.soundEventToOggPathMap.isEmpty()) { /* ... */ return; }

                for (Map.Entry<ResourceLocation, Path> entry : this.soundEventToOggPathMap.entrySet()) {
                    ResourceLocation eventLocation = entry.getKey(); // music_player_soundpacks:test/sounds/music/alpha_day
                    Path absoluteOggPath = entry.getValue(); // 物理パス

                    // ★報告用 ResourceLocation (test:sounds/music/alpha_day.ogg) を生成★
                    if (!eventLocation.getPath().startsWith(this.packId + "/")) continue; // Safety check
                    String relativeEventPath = eventLocation.getPath().substring(this.packId.length() + 1); // "sounds/music/alpha_day"
                    String oggResourcePath = relativeEventPath + ".ogg"; // "sounds/music/alpha_day.ogg"

                    try {
                        ResourceLocation virtualOggLocation = fromNamespaceAndPath(this.packId, oggResourcePath); // "test:sounds/music/alpha_day.ogg"

                        // "sounds" プレフィックスで要求されているので、"sounds/" で始まるものだけ報告
                        if (virtualOggLocation.getPath().startsWith(pathPrefix)) {
                            LOGGER.info("[{}] Reporting OGG in listResources ('sounds' request): {}", this.packId, virtualOggLocation);
                            resourceOutput.accept(virtualOggLocation, IoSupplier.create(absoluteOggPath));
                        }
                    } catch (ResourceLocationException e) {
                        LOGGER.error("[{}] Failed to create ResourceLocation for OGG path '{}' in listResources", this.packId, oggResourcePath, e);
                    }
                }
            } else {
                // 他のプレフィックスの場合は OGG を報告しない
                LOGGER.warn("[{}] listResources: Skipping OGG listing because pathPrefix ('{}') is not 'sounds'", this.packId, pathPrefix);
            }

        }
        // --- 共通 sounds.json のチェック (変更なし) ---
        final String commonSoundNamespace = Music_Player.MOD_ID + "_soundpacks";
        if (namespace.equals(commonSoundNamespace)) {
            if ("sounds.json".startsWith(pathPrefix) && !this.soundEventToOggPathMap.isEmpty()) {
                LOGGER.info("[{}] Reporting sounds.json for namespace '{}'", this.packId, namespace);
                resourceOutput.accept(fromNamespaceAndPath(namespace, "sounds.json"), generateSoundsJsonIoSupplier());
            }
            return; // 共有名前空間の場合はここで終わり
        }

        // --- このパックの名前空間('test')に対するリクエストかチェック ---
        if (!namespace.equals(this.packId)) {
            // LOGGER.debug("[{}] listResources: Skipping namespace '{}'", this.packId, namespace);
            return; // 自分のパックの名前空間でなければ何もしない
        }

        // --- OGG ファイルのリストアップ処理 ---
        // ★★★ Minecraft がルート("")を問い合わせてきた時に、強制的にOGGリストを報告する ★★★
        if (pathPrefix.isEmpty()) {
            LOGGER.info("[{}] listResources: Starting OGG loop for root request (''). Map size: {}", this.packId, this.soundEventToOggPathMap.size());
            if (this.soundEventToOggPathMap.isEmpty()) {
                LOGGER.warn("[{}] listResources: soundEventToOggPathMap is empty, cannot report OGGs.", this.packId);
                return;
            }

            for (Map.Entry<ResourceLocation, Path> entry : this.soundEventToOggPathMap.entrySet()) {
                ResourceLocation eventLocation = entry.getKey(); // 例: music_player_soundpacks:test/music/alpha_day
                // ResourceLocation として報告する必要があるのは、パック固有の名前空間と相対パス
                // 例: test:music/alpha_day.ogg を生成したい

                // eventLocation のパス部分 ("test/music/alpha_day") からパックIDプレフィックス("test/")を除去
                if (eventLocation.getPath().startsWith(this.packId + "/")) {
                    String relativePathWithoutPackId = eventLocation.getPath().substring(this.packId.length() + 1); // "music/alpha_day"
                    String oggResourcePath = relativePathWithoutPackId + ".ogg"; // "music/alpha_day.ogg"

                    try {
                        ResourceLocation virtualOggLocation = fromNamespaceAndPath(this.packId, oggResourcePath); // "test:music/alpha_day.ogg"

                        // ルート("")リクエストなので、基本的に全てのOGGを報告する
                        // (pathPrefix が空なので startsWith(pathPrefix) は常に true)
                        LOGGER.info("[{}] Reporting OGG in listResources (root request): {}", this.packId, virtualOggLocation);
                        resourceOutput.accept(virtualOggLocation, IoSupplier.create(entry.getValue())); // Path を直接渡す IoSupplier も使えるはず

                    } catch (ResourceLocationException e) {
                        LOGGER.error("[{}] Failed to create ResourceLocation for OGG path '{}' in listResources", this.packId, oggResourcePath, e);
                    }
                } else {
                    LOGGER.warn("[{}] Event path '{}' does not start with expected packId '{}'. Skipping OGG report.", this.packId, eventLocation.getPath(), this.packId);
                }
            }
        }
        // pathPrefix が "music/" の場合のチェックも残しておく (現状呼ばれないが念のため)
        else if (pathPrefix.startsWith("music/")) {
            LOGGER.info("[{}] listResources: Starting OGG loop for specific 'music/' request. Map size: {}", this.packId, this.soundEventToOggPathMap.size());
            // ... (同様のループだが、生成した virtualOggLocation.getPath() が pathPrefix で始まるかチェックする) ...
            // この部分は現状呼ばれない可能性が高いので省略しても良いかもしれない
        }
        // その他の pathPrefix (textures など) は OGG を報告しない
        else {
            // OGG リストアップ条件に一致しなかった場合のログ（以前と同じ）
            LOGGER.warn("[{}] listResources: Skipping OGG listing because pathPrefix ('{}') is not empty or starting with 'music/'", this.packId, pathPrefix);
        }
    }

    // --- メタデータ、名前空間、その他 ---

    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        // pack.mcmeta の内容を要求された場合にパースして返す
        if ("pack".equals(deserializer.getMetadataSectionName())) {
            try (InputStream stream = generatePackMcmetaIoSupplier().get();
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
            {
                JsonObject jsonObject = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                // "pack" オブジェクト部分を渡す
                return deserializer.fromJson(jsonObject.getAsJsonObject("pack"));
            } catch (Exception e) {
                LOGGER.error("Failed to read or parse generated pack.mcmeta for pack {}", this.packId, e);
                // エラー発生時は null を返すか、IOException を再スローする
                // return null;
                throw new IOException("Failed to get pack metadata for " + this.packId, e);
            }
        }
        return null; // "pack" 以外のメタデータは提供しない
    }

    @Override
    public String packId() {
        // パックIDを返す (Forge/MCバージョンにより getName() の場合あり)
        return this.packId;
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            // ★修正: 自身のIDと、共通サウンドイベント名前空間の両方を提供するようにする
            return Set.of(this.packId, Music_Player.MOD_ID + "_soundpacks");
        }
        return Collections.emptySet();
    }

    @Override
    public void close() {
        // リソースを開放する必要があればここで行う (通常はファイルストリーム等が自動で閉じるため不要)
        // LOGGER.debug("Closing DynamicSoundResourcePack for ID: {}", this.packId);
    }


    // --- ヘルパーメソッド ---

    /**
     * pack.mcmeta の内容を動的に生成して IoSupplier<InputStream> として返す
     */
    private IoSupplier<InputStream> generatePackMcmetaIoSupplier() {
        String json = String.format(Locale.ROOT, """
                {
                  "pack": {
                    "description": "Dynamic SoundPack: %s (Generated by %s)",
                    "pack_format": %d
                  }
                }
                """, this.packId, Music_Player.MOD_ID, PACK_FORMAT);
        // try-with-resources を使わないため、呼び出し側で閉じる必要がある InputStream を返す
        return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * このリソースパックインスタンスが提供するサウンドに基づいて、
     * 動的に sounds.json の内容を生成し、その InputStream を提供する IoSupplier を返します。
     *
     * @return sounds.json の内容を提供する IoSupplier<InputStream>。サウンドがない場合は空のJSON (`{}`) を返す。
     */
    private IoSupplier<InputStream> generateSoundsJsonIoSupplier() {
        // マップが null または空の場合は、空の JSON を返す
        if (this.soundEventToOggPathMap == null || this.soundEventToOggPathMap.isEmpty()) {
            LOGGER.warn("[{}] soundEventToOggPathMap is null or empty. Generating empty sounds.json.", this.packId);
            return () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        }

        JsonObject rootJson = new JsonObject();
        LOGGER.debug("[{}] Generating sounds.json content for {} sounds...", this.packId, this.soundEventToOggPathMap.size());

        // マップ内の各エントリを処理
        for (Map.Entry<ResourceLocation, Path> entry : this.soundEventToOggPathMap.entrySet()) {
            ResourceLocation eventLocation = entry.getKey(); // 例: music_player_soundpacks:test/sounds/music/alpha_day

            // 1. JSON のキーを生成 (イベントIDのパス部分)
            //    例: "test/sounds/music/alpha_day"
            String jsonKey = eventLocation.getPath();
            String expectedPrefix = this.packId + "/sounds/"; // "test/sounds/"
            if (!jsonKey.startsWith(expectedPrefix)) {
                LOGGER.warn("[{}] generateSoundsJson: Event path '{}' does not start with expected prefix '{}'. Skipping.",
                        this.packId, jsonKey, expectedPrefix);
                continue; // 予期しない形式のキーはスキップ
            }

            // 2. JSON 内の "name" フィールドの値を生成
            //    例: "test:music/alpha_day" (先頭の "sounds/" を除去)
            String relativeMusicPath = jsonKey.substring(expectedPrefix.length()); // "music/alpha_day"
            String soundName = this.packId + ":" + relativeMusicPath; // "test:music/alpha_day"

            // 3. このサウンドイベントに対応する JSON 構造を作成
            //    { "sounds": [ { "name": "test:music/alpha_day", "stream": true } ] }
            JsonObject soundEntry = new JsonObject();
            JsonArray soundsArray = new JsonArray();
            JsonObject soundObject = new JsonObject();

            soundObject.addProperty("name", soundName); // ★ "sounds/" を含まないサウンド名を使用 ★
            soundObject.addProperty("stream", true);    // BGM は通常ストリーミング再生

            soundsArray.add(soundObject);
            soundEntry.add("sounds", soundsArray);

            // 4. ルート JSON オブジェクトに、イベントIDのパス部分をキーとして追加
            rootJson.add(jsonKey, soundEntry);
            // ログで生成内容を確認
            LOGGER.debug("  Added to generated sounds.json: Key='{}', SoundName='{}'", jsonKey, soundName);
        }

        // 実際にエントリが追加されたか確認 (ループ内でスキップされた場合など)
        if (rootJson.size() == 0) {
            LOGGER.warn("[{}] Generated sounds.json for pack {} is empty after processing map.", this.packId, this.packId);
            return () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        }

        // JSON オブジェクトを文字列に変換 (PrettyPrinting で整形)
        String jsonContent = GSON.toJson(rootJson);
        // 生成された JSON 全体をログに出力 (デバッグ用、本番では DEBUG レベル以下推奨)
        LOGGER.info("[{}] Generated sounds.json content:\n{}", this.packId, jsonContent);

        // 文字列を InputStream にして返す IoSupplier を生成
        return () -> new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8));
    }



    private Path findActualOggPath(ResourceLocation requestedLocation) {
        String packNs = requestedLocation.getNamespace(); // "test"
        String requestedPath = requestedLocation.getPath(); // 例: "sounds/music/alpha_day.ogg"

        LOGGER.debug("findActualOggPath received request for: {}", requestedLocation);

        if (!packNs.equals(this.packId)) {
            LOGGER.warn("findActualOggPath called with wrong namespace: {}", packNs);
            return null;
        }

        // ★ requestedPath ("sounds/music/alpha_day.ogg") から Map のキーを再構築 ★
        String canonicalPathPart = requestedPath;
        if (canonicalPathPart.startsWith("sounds/")) {
            // "sounds/" プレフィックスを除去 -> "music/alpha_day.ogg"
            canonicalPathPart = canonicalPathPart.substring("sounds/".length());
            LOGGER.debug("Stripped 'sounds/' prefix, canonical part: {}", canonicalPathPart);
        } else {
            // 予期しないパス形式
            LOGGER.error("findActualOggPath received path without expected 'sounds/' prefix: {}", requestedPath);
            return null;
        }

        // ".ogg" 拡張子を除去 -> "music/alpha_day"
        if (!canonicalPathPart.endsWith(".ogg")) {
            LOGGER.error("findActualOggPath received canonical part without .ogg suffix: {}", canonicalPathPart);
            return null;
        }
        String pathWithoutOgg = canonicalPathPart.substring(0, canonicalPathPart.lastIndexOf('.')); // "music/alpha_day"

        // SoundPackLoader でマップキーとして使用した EventID を再構築
        // 例: "music_player_soundpacks:test/music/alpha_day"
        String eventPathKey = this.packId + "/" + pathWithoutOgg; // "test/music/alpha_day"
        String eventNsKey = Music_Player.MOD_ID + "_soundpacks";
        ResourceLocation mapKeyLocation = null;
        try {
            mapKeyLocation = fromNamespaceAndPath(eventNsKey, eventPathKey);
        } catch (ResourceLocationException e) {
            LOGGER.error("Failed to create map key location in findActualOggPath: ns='{}' path='{}'", eventNsKey, eventPathKey, e);
            return null;
        }


        LOGGER.debug("findActualOggPath looking up in map with key: {}", mapKeyLocation);
        // ★ 再構築したキーでマップを検索 ★
        Path physicalPath = this.soundEventToOggPathMap.get(mapKeyLocation);

        if (physicalPath == null) {
            LOGGER.warn("findActualOggPath could not find path in map for key: {}", mapKeyLocation);
        } else {
            LOGGER.debug("findActualOggPath found physical path: {}", physicalPath);
        }
        return physicalPath;
    }
}