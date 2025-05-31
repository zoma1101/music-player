package com.zoma1101.music_player.sound;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;

public class SoundPackInfo {
    private final String internalId; // ディレクトリ名から生成されるID (例: dq_music_v1.00)
    private final Component displayName; // ディレクトリ名そのもの (例: DQ Music v1.00)
    private final String assetId;      // pack.mcmeta の "asset_id" から読み込むID (例: dq_bgm)
    private final Component description;
    private final Path packDirectory;
    @Nullable
    private ResourceLocation iconLocation;

    // コンストラクタを修正して assetId と displayName, packFormat を受け取る
    public SoundPackInfo(String internalId, Component displayName, String assetId, Component description, Path packDirectory) {
        this.internalId = Objects.requireNonNull(internalId, "Internal ID cannot be null");
        this.displayName = Objects.requireNonNull(displayName, "Display name cannot be null");
        this.assetId = Objects.requireNonNull(assetId, "Asset ID cannot be null");
        if (assetId.isEmpty()) {
            throw new IllegalArgumentException("Asset ID (from pack.mcmeta) cannot be empty");
        }
        this.description = Objects.requireNonNull(description, "Description cannot be null");
        this.packDirectory = Objects.requireNonNull(packDirectory, "Pack directory cannot be null");
    }

    /**
     * サウンドパックの内部的な識別ID（ディレクトリ名から生成）。
     * UIでの選択やアクティブパックの管理に使用。
     */
    public String getId() {
        return internalId;
    }

    /**
     * ユーザーに表示される名前（通常はサウンドパックのルートディレクトリ名）。
     */
    public Component getDisplayName() {
        return displayName;
    }

    /**
     * pack.mcmeta の "asset_id" から読み込まれた、
     * assets ディレクトリ以下の実際のフォルダ名として使用されるID。
     */
    public String getAssetId() {
        return assetId;
    }

    public Component getDescription() {
        return description;
    }


    public Path getPackDirectory() {
        return packDirectory;
    }

    /**
     * このサウンドパックのアセット (conditions, sounds など) が格納されている
     * assets ディレクトリのパスを返します。
     * パス構造は "soundpacks/表示名/assets/アセットID/" となります。
     * 例: soundpacks/DQ Music v1.00/assets/dq_bgm/
     */
    public Path getAssetsDirectory() {
        // assets フォルダの次のディレクトリ名に assetId (pack.mcmeta の asset_id) を使用します。
        return packDirectory.resolve("assets").resolve(this.assetId);
    }

    @Nullable
    public ResourceLocation getIconLocation() {
        return iconLocation;
    }

    public void setIconLocation(@Nullable ResourceLocation iconLocation) {
        this.iconLocation = iconLocation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundPackInfo that = (SoundPackInfo) o;
        return internalId.equals(that.internalId); // 比較は internalId で行う
    }

    @Override
    public int hashCode() {
        return Objects.hash(internalId); // ハッシュコードも internalId で生成
    }

    @Override
    public String toString() {
        return "SoundPackInfo{internalId='" + internalId + "', displayName='" + displayName.getString() + "', assetId='" + assetId + "', description='" + description.getString() + "'}";
    }
}