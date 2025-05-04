package com.zoma1101.music_player.config; // パッケージは適切に設定

import java.util.List;
import com.google.gson.annotations.SerializedName; // Gsonを使用する場合
import net.minecraft.resources.ResourceLocation;

public class SoundDefinition {
    // GsonがJSONキーとフィールド名をマッピングするために @SerializedName を使うか、
    // フィールド名をJSONキー(スネークケース推奨)に合わせる
    @SerializedName("priority")
    public int priority = 0; // ★必須: 優先度 (高いほど優先)

    @SerializedName("music")
    public String musicPath = null; // ★必須: music/hoge.ogg 形式の相対パス

    @SerializedName("biomes")
    public List<String> biomes = null; // オプション: バイオームIDまたはタグ(#始まり)のリスト

    @SerializedName("is_night")
    public Boolean isNight = null; // オプション: nullなら昼夜問わない

    @SerializedName("is_combat")
    public Boolean isCombat = null; // オプション: nullなら戦闘状態を問わない

    @SerializedName("is_village")
    public Boolean isVillage = null; // オプション: nullなら村かどうかを問わない

    @SerializedName("min_y")
    public Integer minY = null; // オプション: nullなら下限なし

    @SerializedName("max_y")
    public Integer maxY = null; // オプション: nullなら上限なし

    @SerializedName("gui_screen")
    public String guiScreen = null; // オプション: nullならGUIを問わない (例: "minecraft:crafting_screen")

    // --- 計算用フィールド (読み込み後設定) ---
    public transient String soundPackId = null; // どのSoundPackに属するか
    public transient ResourceLocation soundEventLocation = null; // 対応するSoundEventのID
    public transient java.nio.file.Path absoluteMusicPath = null; // OGGファイルのフルパス

    // 必要に応じて他の条件フィールドを追加 (例: isRaining, dimension など)

    // デフォルトコンストラクタは Gson のために必要
    public SoundDefinition() {}

    // 簡単なバリデーションメソッド (例)
    public boolean isValid() {
        return priority >= 0 && musicPath != null && !musicPath.isBlank();
    }

    @Override
    public String toString() {
        // デバッグ用に簡単な文字列表現
        return "SoundDef(priority=" + priority + ", music=" + musicPath + ", event=" + soundEventLocation + ")";
    }
}