package com.zoma1101.music_player.sound;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ResourceLocationException; // ResourceLocationException をインポート
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class MusicDefinition {

    // --- JSONから直接ロードされるフィールド ---
    @SerializedName("priority")
    public int priority = 0;

    @SerializedName("music")
    public String musicFileInPack; // 例: "music/bgm_combat.ogg" (パック内の相対パス)

    @SerializedName("biomes")
    @Nullable public List<String> biomes = null;

    @SerializedName("is_night")
    @Nullable public Boolean isNight = null;

    @SerializedName("is_combat")
    @Nullable public Boolean isCombat = null;

    @SerializedName("is_village")
    @Nullable public Boolean isVillage = null;

    @SerializedName("min_y")
    @Nullable public Integer minY = null;

    @SerializedName("max_y")
    @Nullable public Integer maxY = null;

    @SerializedName("gui_screen")
    @Nullable public String guiScreen = null;

    @SerializedName("weather")
    @Nullable public List<String> weather = null;

    @SerializedName("dimensions")
    @Nullable public List<String> dimensions = null;

    // --- エンティティ条件用のトップレベルフィールド ---
    @SerializedName("entity_conditions")
    @Nullable public List<String> entityConditions = null; // エンティティIDまたはタグのリスト (例: ["minecraft:zombie", "#minecraft:raiders", "!minecraft:bat"])

    @SerializedName("radius")
    @Nullable public Double radius = null;

    @SerializedName("min_count")
    @Nullable public Integer minCount = null;

    @SerializedName("max_count")
    @Nullable public Integer maxCount = null; // nullの場合は上限なし

    // --- ロード処理中に設定される内部フィールド ---
    private transient String soundPackId; // この定義が属するSoundPackのID
    private transient Path absoluteOggPath; // OGGファイルの絶対パス
    // sounds.json のトップレベルキー (例: "cool_pack/music/battle1")
    // Minecraft内部ではこのキーでサウンドイベントが認識される
    private transient String soundEventKey;
    // MODがOGGファイルを提供するためのResourceLocation (例: "music_player:cool_pack/music/battle1.ogg")
    // sounds.json の "sounds": [{"name": "..."}] で使われる
    private transient ResourceLocation oggResourceLocation;


    // Gsonのためのデフォルトコンストラクタ
    public MusicDefinition() {}

    // --- Getter ---
    public int getPriority() { return priority; }
    public String getMusicFileInPack() { return musicFileInPack; }
    @Nullable public List<String> getBiomes() { return biomes; }
    @Nullable public Boolean isNight() { return isNight; }
    @Nullable public Boolean isCombat() { return isCombat; }
    @Nullable public Boolean isVillage() { return isVillage; }
    @Nullable public Integer getMinY() { return minY; }
    @Nullable public Integer getMaxY() { return maxY; }
    @Nullable public String getGuiScreen() { return guiScreen; }
    @Nullable public List<String> getWeather() { return weather; }
    @Nullable public List<String> getDimensions() { return dimensions; }
    @Nullable public List<String> getEntityConditions() { return entityConditions; }
    @Nullable public Double getRadius() { return radius; }
    @Nullable public Integer getMinCount() { return minCount; }
    @Nullable public Integer getMaxCount() { return maxCount; }


    public String getSoundPackId() { return soundPackId; }
    public String getSoundEventKey() { return soundEventKey; }
    public ResourceLocation getOggResourceLocation() { return oggResourceLocation; }

    // --- Setter (ロード処理中に使用) ---
    public void setSoundPackId(String soundPackId) { this.soundPackId = soundPackId; }
    public void setAbsoluteOggPath(Path absoluteOggPath) { this.absoluteOggPath = absoluteOggPath; }
    public void setSoundEventKey(String soundEventKey) { this.soundEventKey = soundEventKey; }
    public void setOggResourceLocation(ResourceLocation oggResourceLocation) { this.oggResourceLocation = oggResourceLocation; }


    public boolean isValid() {
        // 基本的なフィールドのチェック
        if (priority < 0 ||
                musicFileInPack == null || musicFileInPack.isBlank() ||
                soundPackId == null || soundPackId.isBlank() ||
                absoluteOggPath == null ||
                soundEventKey == null || soundEventKey.isBlank() ||
                oggResourceLocation == null) {
            return false;
        }

        // エンティティ条件の妥当性チェック
        if (entityConditions != null && !entityConditions.isEmpty()) {
            // entityConditions が指定されている場合、radius は必須
            if (radius == null || radius <= 0) return false;
            // minCount, maxCount の基本的なバリデーション
            if (minCount != null && minCount < 0) return false;
            if (maxCount != null && maxCount < 0) return false;
            if (minCount != null && maxCount != null && minCount > maxCount) return false;

            // entityConditions リスト内の各要素の形式チェック
            for (String entityIdOrTag : entityConditions) {
                if (entityIdOrTag == null || entityIdOrTag.isBlank()) return false; // 空の要素は無効
                String checkString = entityIdOrTag;
                if (checkString.startsWith("!")) {
                    if (checkString.length() == 1) return false; // "!" だけは無効
                    checkString = checkString.substring(1);
                }
                if (checkString.startsWith("#")) {
                    if (checkString.length() == 1) return false; // "#" だけは無効
                    // checkString = checkString.substring(1); // タグの場合、#以降をパース
                }
                try {
                    ResourceLocation.parse(checkString.startsWith("#") ? checkString.substring(1) : checkString);
                } catch (ResourceLocationException e) {
                    return false;
                }
            }
        } else return radius == null && minCount == null && maxCount == null;
        return true;
    }

    @Override
    public String toString() {
        return "MusicDefinition{" +
                "soundPackId='" + soundPackId + '\'' +
                ", musicFileInPack='" + musicFileInPack + '\'' +
                ", priority=" + priority +
                ", soundEventKey='" + soundEventKey + '\'' +
                ", oggResourceLocation=" + oggResourceLocation +
                ", biomes=" + biomes +
                ", isNight=" + isNight +
                ", isCombat=" + isCombat +
                ", isVillage=" + isVillage +
                ", minY=" + minY +
                ", maxY=" + maxY +
                ", guiScreen='" + guiScreen + '\'' +
                ", weather=" + weather +
                ", dimensions=" + dimensions +
                ", entityConditions=" + entityConditions +
                ", radius=" + radius +
                ", minCount=" + minCount +
                ", maxCount=" + maxCount +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MusicDefinition that = (MusicDefinition) o;
        return Objects.equals(soundEventKey, that.soundEventKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(soundEventKey);
    }
}