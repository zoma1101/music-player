package com.zoma1101.music_player.sound;

import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;

public class SoundPackInfo {
    private final String id; // サウンドパックのID (ディレクトリ名から)
    private final Component description;
    private final int packFormat;
    private final Path rootDirectory; // soundpacks/pack_id/
    private final Path assetsDirectory; // soundpacks/pack_id/assets/pack_id/ (MOD ID ではなく Pack ID を使う)

    public SoundPackInfo(String id, Component description, int packFormat, Path rootDirectory) {
        this.id = Objects.requireNonNull(id);
        this.description = Objects.requireNonNull(description);
        this.packFormat = packFormat;
        this.rootDirectory = Objects.requireNonNull(rootDirectory);
        // assets ディレクトリは pack_id を名前空間として使用
        this.assetsDirectory = rootDirectory.resolve("assets").resolve(id);
    }

    public String getId() { return id; }
    public Component getDescription() { return description; }
    public int getPackFormat() { return packFormat; }
    public Path getRootDirectory() { return rootDirectory; }
    public Path getAssetsDirectory() { return assetsDirectory; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundPackInfo that = (SoundPackInfo) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SoundPackInfo{id='" + id + "', description='" + description.getString() + "'}";
    }
}