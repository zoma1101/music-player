package com.zoma1101.music_player.sound; // 新しいパッケージ構造を推奨

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
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

    public String getSoundPackId() { return soundPackId; }
    public String getSoundEventKey() { return soundEventKey; }
    public ResourceLocation getOggResourceLocation() { return oggResourceLocation; }

    // --- Setter (ロード処理中に使用) ---
    public void setSoundPackId(String soundPackId) { this.soundPackId = soundPackId; }
    public void setAbsoluteOggPath(Path absoluteOggPath) { this.absoluteOggPath = absoluteOggPath; }
    public void setSoundEventKey(String soundEventKey) { this.soundEventKey = soundEventKey; }
    public void setOggResourceLocation(ResourceLocation oggResourceLocation) { this.oggResourceLocation = oggResourceLocation; }


    public boolean isValid() {
        return priority >= 0 &&
                musicFileInPack != null && !musicFileInPack.isBlank() &&
                soundPackId != null && !soundPackId.isBlank() &&
                absoluteOggPath != null &&
                soundEventKey != null && !soundEventKey.isBlank() &&
                oggResourceLocation != null;
    }

    @Override
    public String toString() {
        return "MusicDefinition{" +
                "soundPackId='" + soundPackId + '\'' +
                ", musicFileInPack='" + musicFileInPack + '\'' +
                ", priority=" + priority +
                ", soundEventKey='" + soundEventKey + '\'' +
                ", oggResourceLocation=" + oggResourceLocation +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MusicDefinition that = (MusicDefinition) o;
        return Objects.equals(soundEventKey, that.soundEventKey); // soundEventKey で一意性を担保
    }

    @Override
    public int hashCode() {
        return Objects.hash(soundEventKey);
    }
}