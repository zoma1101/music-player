package com.zoma1101.music_player.sound;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;

public class SoundPackInfo {
    private final String internalId; // ディレクトリ名またはZIPファイル名から生成されるID
    private final Component displayName; // 表示名 (ディレクトリ名またはZIPファイル名)
    private final String assetId;      // assetsフォルダ直下のディレクトリ名 (自動検出)
    private final Component description;
    private final int packFormat;
    private final Path packRootPath; // ディレクトリのパス、またはZipFileSystem内のルートパス
    @Nullable
    private ResourceLocation iconLocation; // music_player:<internalId>/pack.png
    @Nullable
    private Path iconFileSystemPath; // pack.pngへの実際のファイルシステムパス (ZipFS内も含む)

    public SoundPackInfo(String internalId, Component displayName, String assetId, Component description, int packFormat, Path packRootPath) {
        this.internalId = Objects.requireNonNull(internalId, "Internal ID cannot be null");
        this.displayName = Objects.requireNonNull(displayName, "Display name cannot be null");
        this.assetId = Objects.requireNonNull(assetId, "Asset ID cannot be null");
        if (assetId.isEmpty()) {
            throw new IllegalArgumentException("Asset ID (from assets sub-directory) cannot be empty");
        }
        this.description = Objects.requireNonNull(description, "Description cannot be null");
        this.packFormat = packFormat;
        this.packRootPath = Objects.requireNonNull(packRootPath, "Pack root path cannot be null");
    }

    public String getId() {
        return internalId;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public String getAssetId() {
        return assetId;
    }

    public Component getDescription() {
        return description;
    }

    public int getPackFormat() {
        return packFormat;
    }

    /**
     * サウンドパックのルートパスを返します。
     * ディレクトリベースの場合はそのディレクトリのパス、ZIPベースの場合はZipFileSystem内のルートパスになります。
     */
    public Path getPackRootPath() {
        return packRootPath;
    }

    public Path getAssetsDirectory() {
        return packRootPath.resolve("assets").resolve(this.assetId);
    }

    @Nullable
    public ResourceLocation getIconLocation() {
        return iconLocation;
    }

    public void setIconLocation(@Nullable ResourceLocation iconLocation) {
        this.iconLocation = iconLocation;
    }

    @Nullable
    public Path getIconFileSystemPath() {
        return iconFileSystemPath;
    }

    public void setIconFileSystemPath(@Nullable Path iconFileSystemPath) {
        this.iconFileSystemPath = iconFileSystemPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundPackInfo that = (SoundPackInfo) o;
        return internalId.equals(that.internalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(internalId);
    }

    @Override
    public String toString() {
        return "SoundPackInfo{internalId='" + internalId + "', displayName='" + displayName.getString() + "', assetId='" + assetId + "'}";
    }
}