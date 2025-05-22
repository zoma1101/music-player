package com.zoma1101.music_player.sound;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;

public class SoundPackInfo {
    private final String id;
    private final Component description;
    private final Path packDirectory;
    @Nullable // アイコンが存在しない場合もあるため
    private ResourceLocation iconLocation; // アイコンのResourceLocationを保持するフィールド

    public SoundPackInfo(String id, Component description, Path packDirectory) {
        this.id = id;
        this.description = description;
        this.packDirectory = packDirectory;
        // アイコンのResourceLocationはSoundPackManagerで設定される
    }

    public String getId() {
        return id;
    }

    public Component getDescription() {
        return description;
    }


    public Path getAssetsDirectory() {
        // assets フォルダのパスを返す
        // 修正前: return packDirectory.resolve("assets").resolve(Music_Player.MOD_ID).resolve(id);
        // 実際のサウンドパックの構造に合わせて、MOD_ID (music_player) の部分を削除します。
        // これにより、例えば packDirectory が "soundpacks/dq_bgm" で id が "dq_bgm" の場合、
        // "soundpacks/dq_bgm/assets/dq_bgm" というパスが返されるようになります。
        return packDirectory.resolve("assets").resolve(id);
    }

    @Nullable
    public ResourceLocation getIconLocation() { // アイコンのゲッター
        return iconLocation;
    }

    public void setIconLocation(@Nullable ResourceLocation iconLocation) { // アイコンのセッター
        this.iconLocation = iconLocation;
    }
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