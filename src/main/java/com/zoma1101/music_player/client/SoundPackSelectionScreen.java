package com.zoma1101.music_player.client;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.Music_Player;
import com.zoma1101.music_player.sound.SoundPackInfo;
// SoundPackDataManagerが提供するSoundPackクラス
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

    private MultiLineLabel noPacksLabel = MultiLineLabel.EMPTY;

    public SoundPackSelectionScreen(Screen parentScreen) {
        super(Component.translatable("gui.music_player.soundpack_selection.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        if (this.minecraft == null || Music_Player.soundPackManager == null) {
            // MinecraftインスタンスやSoundPackManagerがnullの場合は初期化を中断
            this.minecraft.setScreen(this.parentScreen);
            return;
        }

        // SoundPackManagerから利用可能なサウンドパックとアクティブなサウンドパックIDを取得
        List<SoundPackInfo> availablePacks = Music_Player.soundPackManager.getLoadedSoundPacks();
        this.initialActivePackIds = new ArrayList<>(Music_Player.soundPackManager.getActiveSoundPackIds());
        this.currentWorkingActivePackIds = new ArrayList<>(this.initialActivePackIds);

        // サウンドパックリストウィジェットの作成
        this.soundPackList = new SoundPackList(this.minecraft, this.width, this.height - 64 - 32, 32, this.height - 64, 36, availablePacks, this);
        this.addWidget(this.soundPackList); // Screenにウィジェットとして追加

        // ボタンの追加
        // 完了ボタン
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, (button) -> {
                    applyChanges();
                    this.minecraft.setScreen(this.parentScreen);
                })
                .bounds(this.width / 2 - 154, this.height - 52, 150, 20)
                .build());

        // キャンセルボタン
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, (button) -> {
                    // 変更を破棄して親画面に戻る
                    this.minecraft.setScreen(this.parentScreen);
                })
                .bounds(this.width / 2 + 4, this.height - 52, 150, 20)
                .build());

        // サウンドパックフォルダを開くボタン
        this.addRenderableWidget(Button.builder(Component.translatable("gui.music_player.soundpack_selection.open_folder"), (button) -> {
                    Util.getPlatform().openUri(Music_Player.soundPackManager.SOUNDPACKS_BASE_DIR.toUri());
                })
                .bounds(this.width / 2 - 154, this.height - 28, 150, 20)
                .build());

        // リロードボタン (オプション)
        this.addRenderableWidget(Button.builder(Component.translatable("gui.music_player.soundpack_selection.reload_packs"), (button) -> {
                    reloadPacks();
                })
                .bounds(this.width / 2 + 4, this.height - 28, 150, 20)
                .build());


        if (availablePacks.isEmpty()) {
            this.noPacksLabel = MultiLineLabel.create(this.font, Component.translatable("gui.music_player.soundpack_selection.no_packs"), this.width - 50);
        } else {
            this.noPacksLabel = MultiLineLabel.EMPTY;
        }
    }

    private void reloadPacks() {
        if (Music_Player.soundPackManager != null) {
            Music_Player.soundPackManager.discoverAndLoadPacks();
            // アクティブなパックIDもリセットされるので、再取得
            this.initialActivePackIds = new ArrayList<>(Music_Player.soundPackManager.getActiveSoundPackIds());
            this.currentWorkingActivePackIds = new ArrayList<>(this.initialActivePackIds);
            // リストを再構築
            List<SoundPackInfo> availablePacks = Music_Player.soundPackManager.getLoadedSoundPacks();
            this.soundPackList = new SoundPackList(this.minecraft, this.width, this.height - 64 - 32, 32, this.height - 64, 36, availablePacks, this);
            // 古いリストを削除して新しいリストを追加 (より良い方法は clearWidgets と addWidget)
            this.clearWidgets(); // まず既存のウィジェットをクリア
            this.init(); // 再度initを呼び出してウィジェットを再構築
        }
    }


    private void applyChanges() {
        if (Music_Player.soundPackManager != null) {
            // 変更があった場合のみ適用
            if (!this.initialActivePackIds.equals(this.currentWorkingActivePackIds)) {
                Music_Player.soundPackManager.setActiveSoundPackIds(this.currentWorkingActivePackIds);
                // サウンドシステムに変更を反映させるためにリソースパックのリロードをトリガー
                if (this.minecraft != null) {
                    this.minecraft.reloadResourcePacks();
                }
                LOGGER.info("Applied sound pack changes and triggered resource reload.");
            } else {
                LOGGER.info("No changes in active sound packs to apply.");
            }
        }
    }

    public List<String> getCurrentWorkingActivePackIds() {
        return this.currentWorkingActivePackIds;
    }

    public void togglePackActivation(String packId) {
        if (this.currentWorkingActivePackIds.contains(packId)) {
            this.currentWorkingActivePackIds.remove(packId);
        } else {
            this.currentWorkingActivePackIds.add(packId);
        }
        // リストの再描画を促す (実際にはリストエントリの見た目が変わる)
        if (this.soundPackList != null) {
            this.soundPackList.children().forEach(entry -> entry.updateSelectedStatus());
        }
    }


    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks); // 背景などを描画
        this.soundPackList.render(guiGraphics, mouseX, mouseY, partialTicks); // リストを描画
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF); // タイトルを描画

        if (this.noPacksLabel != MultiLineLabel.EMPTY) {
            this.noPacksLabel.renderCentered(guiGraphics, this.width / 2, this.height / 2 - this.noPacksLabel.getLineCount() * this.font.lineHeight / 2);
        }
    }

    @Override
    public void onClose() {
        // 画面が閉じられるときに変更を適用するかどうか (キャンセルボタンとの兼ね合い)
        // ここではキャンセルボタンで明示的に破棄するので、完了ボタンで適用する
        this.minecraft.setScreen(this.parentScreen);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESCキーでキャンセルと同様の動作
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.minecraft.setScreen(this.parentScreen);
            return true;
        }
        return false;
    }
}