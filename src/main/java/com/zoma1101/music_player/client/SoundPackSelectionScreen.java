package com.zoma1101.music_player.client;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import com.zoma1101.music_player.sound.SoundPackInfo;
// SoundPackDataManagerが提供するSoundPackクラス
import com.zoma1101.music_player.sound.SoundPackManager;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;


public class SoundPackSelectionScreen extends Screen {
    private final Screen parentScreen;
    private SoundPackList soundPackList;
    private static final Logger LOGGER = LogUtils.getLogger();
    // 画面を開いた時点でのアクティブなパックIDと、画面上で変更中のアクティブなパックID
    private List<String> initialActivePackIds;
    private List<String> currentWorkingActivePackIds;
    private List<String> initialPackOrder;
    private List<String> currentWorkingPackOrder;

    private MultiLineLabel noPacksLabel = MultiLineLabel.EMPTY;

    public SoundPackSelectionScreen(Screen parentScreen) {
        super(Component.translatable("gui.music_player.soundpack_selection.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        if (this.minecraft == null) {
            LOGGER.error("Minecraft instance is null during SoundPackSelectionScreen init. Aborting initialization.");
            return;
        }

        List<SoundPackInfo> availablePacks = Music_Player.soundPackManager.getLoadedSoundPacks();
        this.initialActivePackIds = new ArrayList<>(Music_Player.soundPackManager.getActiveSoundPackIds());
        if (this.currentWorkingActivePackIds == null) {
            this.currentWorkingActivePackIds = new ArrayList<>(this.initialActivePackIds);
        }

        this.initialPackOrder = new ArrayList<>(Music_Player.soundPackManager.getPackOrder());
        if (this.currentWorkingPackOrder == null) {
            this.currentWorkingPackOrder = new ArrayList<>(this.initialPackOrder);
        }

        // 表示用リストを作成：currentWorkingPackOrder の順序に従って利用可能なパックを並べる
        List<SoundPackInfo> displayPacks = new ArrayList<>();
        for (String id : this.currentWorkingPackOrder) {
            availablePacks.stream().filter(p -> p.getId().equals(id)).findFirst().ifPresent(displayPacks::add);
        }
        // もし新しくロードされたパックなどがあって currentWorkingPackOrder に入っていないものがあれば末尾に追加
        for (SoundPackInfo pack : availablePacks) {
            if (!displayPacks.contains(pack)) {
                displayPacks.add(pack);
            }
        }

        this.soundPackList = new SoundPackList(this.minecraft, this.width, this.height - 64 - 32, 32, this.height - 64, 36, displayPacks, this);
        this.addWidget(this.soundPackList);


        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, (button) -> {
                    applyChanges();
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(this.parentScreen);
                    } else {
                        LOGGER.warn("Minecraft instance was null when trying to close SoundPackSelectionScreen (Done button).");
                    }
                })
                .bounds(this.width / 2 - 154, this.height - 52, 308, 20)
                .build());


        this.addRenderableWidget(Button.builder(Component.translatable("gui.music_player.soundpack_selection.open_folder"), (button) -> Util.getPlatform().openUri(SoundPackManager.SOUNDPACKS_BASE_DIR.toUri()))
                .bounds(this.width / 2 - 154, this.height - 28, 150, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.music_player.soundpack_selection.reload_packs"), (button) -> reloadPacks())
                .bounds(this.width / 2 + 4, this.height - 28, 150, 20)
                .build());

        if (availablePacks.isEmpty()) {
            this.noPacksLabel = MultiLineLabel.create(this.font, Component.translatable("gui.music_player.soundpack_selection.no_packs"), this.width - 50);
        } else {
            this.noPacksLabel = MultiLineLabel.EMPTY;
        }
    }

    private void reloadPacks() {
        // SoundPackManager が null の場合のチェックを追加
        // reloadPacks時に一時的に音楽を停止 (ZipFileSystemのクローズによるエラーを防止)
        com.zoma1101.music_player.ClientMusicManager.stopMusic(false);
        Music_Player.soundPackManager.discoverAndLoadPacks();

        if (this.minecraft != null) {
            this.clearWidgets();
            this.init();
        } else {
            LOGGER.error("Minecraft instance is null. Cannot re-initialize screen after reloading packs.");
        }
    }


    private void applyChanges() {
        boolean changed = false;
        if (!this.initialActivePackIds.equals(this.currentWorkingActivePackIds)) {
            Music_Player.soundPackManager.setActiveSoundPackIds(this.currentWorkingActivePackIds);
            changed = true;
        }
        if (!this.initialPackOrder.equals(this.currentWorkingPackOrder)) {
            Music_Player.soundPackManager.setPackOrder(this.currentWorkingPackOrder);
            changed = true;
        }

        if (changed) {
            if (this.minecraft != null) {
                this.minecraft.reloadResourcePacks();
            }
            LOGGER.info("Applied sound pack changes (activation or order) and triggered resource reload.");
        } else {
            LOGGER.info("No changes in sound packs to apply.");
        }
    }

    public List<String> getCurrentWorkingActivePackIds() {
        return this.currentWorkingActivePackIds;
    }

    public boolean togglePackActivation(String packId) {
        if (this.currentWorkingActivePackIds.contains(packId)) {
            this.currentWorkingActivePackIds.remove(packId);
        } else {
            this.currentWorkingActivePackIds.add(packId);
        }
        if (this.soundPackList != null) {
            this.soundPackList.children().forEach(SoundPackList.SoundPackEntry::updateSelectedStatus);
        }
        return this.currentWorkingActivePackIds.contains(packId);
    }

    public void updateOrderFromList(List<SoundPackList.SoundPackEntry> entries) {
        List<String> newOrder = new ArrayList<>();
        for (SoundPackList.SoundPackEntry entry : entries) {
            newOrder.add(entry.getPackInfo().getId());
        }
        this.currentWorkingPackOrder = newOrder;
    }

    public void rebuildSoundPackList() {
        if (this.minecraft == null) return;
        this.clearWidgets();
        this.init();
    }

    // renderBackgroundメソッドをオーバーライドして、デフォルトの背景描画を制御


    @Override
    public void renderBackground(@NotNull GuiGraphics p_283688_) {
        super.renderBackground(p_283688_);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);


        if (this.soundPackList != null) {
            this.soundPackList.render(guiGraphics, mouseX, mouseY, partialTicks); // リストを描画
        }
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        if (this.noPacksLabel != MultiLineLabel.EMPTY && this.noPacksLabel != null) {
            this.noPacksLabel.renderCentered(guiGraphics, this.width / 2, this.height / 2 - this.noPacksLabel.getLineCount() * this.font.lineHeight / 2);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            if (this.parentScreen != null) {
                this.minecraft.setScreen(this.parentScreen);
            } else {
                LOGGER.warn("parentScreen is null in onClose. Cannot navigate back to parent.");
            }
        } else {
            LOGGER.warn("Minecraft instance was null during onClose of SoundPackSelectionScreen.");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (this.minecraft != null) {
                if (this.parentScreen != null) {
                    this.minecraft.setScreen(this.parentScreen);
                } else {
                    LOGGER.warn("parentScreen is null when trying to close via ESC key.");
                }
            } else {
                LOGGER.warn("Minecraft instance was null when trying to close SoundPackSelectionScreen via ESC key.");
            }
            return true;
        }
        return false;
    }
}