package com.zoma1101.music_player.soundpack; // パッケージは適宜調整してください

import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;

public class SoundPack {
    private final String id; // サウンドパックのID (例: "dragon_quest")
    private final Component description; // pack.mcmeta からの説明
    private final int packFormat; // pack.mcmeta からのフォーマット
    private final Path rootDirectory; // soundpacks/dragon_quest/
    private final Path assetsDirectory; // soundpacks/dragon_quest/assets/dragon_quest/

    public SoundPack(String id, Component description, int packFormat, Path rootDirectory) {
        this.id = Objects.requireNonNull(id);
        this.description = Objects.requireNonNull(description);
        this.packFormat = packFormat;
        this.rootDirectory = Objects.requireNonNull(rootDirectory);
        this.assetsDirectory = rootDirectory.resolve("assets").resolve(id); // pack_id を使用
    }

    public String getId() {
        return id;
    }

    public Component getDescription() {
        return description;
    }

    public int getPackFormat() {
        return packFormat;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Path getAssetsDirectory() {
        return assetsDirectory;
    }

    // equals と hashCode も実装すると良いでしょう (id基準など)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SoundPack soundPack = (SoundPack) o;
        return id.equals(soundPack.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SoundPack{" +
                "id='" + id + '\'' +
                ", description=" + description.getString() + // 簡略化のためgetString
                ", packFormat=" + packFormat +
                '}';
    }
}