package com.zoma1101.music_player.client; // または適切なパッケージ

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class SoundPack {
    private final String id;
    private final Component displayName;
    private final Component description;
    private final Path packPath; // サウンドパックのルートディレクトリ or ZIPファイルパス
    private final String iconFileName; // 例: "pack.png"

    // 必要に応じてバージョンなどの情報も追加
    // private final String version;

    public SoundPack(String id, Component displayName, Component description, Path packPath, String iconFileName) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.description = Objects.requireNonNull(description);
        this.packPath = Objects.requireNonNull(packPath);
        this.iconFileName = iconFileName != null ? iconFileName : "pack.png"; // デフォルトアイコンファイル名
    }

    public String getId() {
        return id;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public Component getDescription() {
        return description;
    }

    public Path getPackPath() {
        return packPath;
    }

    @Nullable
    public InputStream getIconInputStream() {
        if (Files.isDirectory(packPath)) {
            Path iconPath = packPath.resolve(this.iconFileName);
            if (Files.exists(iconPath) && Files.isRegularFile(iconPath)) {
                try {
                    return Files.newInputStream(iconPath);
                } catch (Exception e) {
                    // SoundPackSelectionScreen.LOGGER.warn("Could not open icon stream for pack {}: {}", id, e.getMessage());
                    System.err.println("Could not open icon stream for pack " + id + ": " + e.getMessage()); // 簡易エラー出力
                    return null;
                }
            }
        } else if (Files.isRegularFile(packPath) && packPath.toString().toLowerCase().endsWith(".zip")) {
            // ZIPファイル内のアイコンを読み込む処理 (java.util.zip.ZipFile を使用)
            // この部分は SoundPackLoader 側でZIPを展開して一時ディレクトリに配置するか、
            // ZipFileSystem を使って直接読み込むなどの工夫が必要になります。
            // ここでは簡略化のため、ディレクトリの場合のみを想定します。
            // SoundPackSelectionScreen.LOGGER.warn("Icon loading from ZIP not yet implemented for pack {}", id);
            System.err.println("Icon loading from ZIP not yet implemented for pack " + id);
            return null;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundPack soundPack = (SoundPack) o;
        return id.equals(soundPack.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "SoundPack{" +
                "id='" + id + '\'' +
                ", displayName=" + displayName.getString() +
                '}';
    }
}