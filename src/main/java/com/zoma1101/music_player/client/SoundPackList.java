package com.zoma1101.music_player.client;



import com.zoma1101.music_player.sound.SoundPackInfo; // ★変更点1: SoundPackInfo をインポート
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarratedElementType; // 追加
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;



public class SoundPackList extends AbstractSelectionList<SoundPackList.Entry> {
    private final SoundPackSelectionScreen parentScreen;
    private static final ResourceLocation CHECKBOX_SELECTED_SPRITE = fromNamespaceAndPath("minecraft", "widget/checkbox_selected");
    private static final ResourceLocation CHECKBOX_UNSELECTED_SPRITE = fromNamespaceAndPath("minecraft", "widget/checkbox");


    // ★変更点3: コンストラクタの引数の型を List<SoundPack> から List<SoundPackInfo> に変更
    public SoundPackList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight, List<SoundPackInfo> packs, SoundPackSelectionScreen parent) {
        super(mc, width, height, y0, y1, itemHeight);
        this.parentScreen = parent;
        this.centerListVertically = false; // 上から順に表示

        // 利用可能なサウンドパックをリストエントリとして追加
        // ★変更点4: addEntry に渡す Entry のコンストラクタ引数も SoundPackInfo に変更
        for (SoundPackInfo pack : packs) {
            this.addEntry(new Entry(mc, this, pack));
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 20; // スクロールバーの幅を考慮
    }

    @Override
    protected int getScrollbarPosition() {
        return this.width - 6; // 右端にスクロールバー
    }

    @Override
    public void setSelected(@org.jetbrains.annotations.Nullable Entry entry) {
        super.setSelected(entry);
        // 選択時のアクション (ここでは特に不要)
    }

    @Override
    public void updateNarration(NarrationElementOutput narrationElementOutput) {
        // 親画面のタイトルをリストのタイトルとしてナレーションに追加
        narrationElementOutput.add(NarratedElementType.TITLE, this.parentScreen.getTitle());

        // 現在選択されているエントリのナレーションを追加
        if (this.getSelected() != null) {
            // Entry クラスで getNarration() メソッドが実装されていることを利用
            narrationElementOutput.add(NarratedElementType.POSITION, Component.translatable("narrator.list.select", this.getSelected().getNarration()));
        }
    }


    public class Entry extends AbstractSelectionList.Entry<SoundPackList.Entry> {
        private final Minecraft minecraft;
        private final SoundPackList list;
        // ★変更点5: packInfo フィールドの型を SoundPack から SoundPackInfo に変更
        private final SoundPackInfo packInfo;
        private boolean isCurrentlyActive;

        // ★変更点6: コンストラクタの引数の型を SoundPack から SoundPackInfo に変更
        public Entry(Minecraft mc, SoundPackList list, SoundPackInfo pack) {
            this.minecraft = mc;
            this.list = list;
            this.packInfo = pack;
            updateSelectedStatus();
        }

        public void updateSelectedStatus() {
            // SoundPackList.this.parentScreen.getCurrentWorkingActivePackIds() が正しいパスとメソッド名であることを確認
            // packInfo.getId() は SoundPackInfo にも存在するので変更なし
            this.isCurrentlyActive = SoundPackList.this.parentScreen.getCurrentWorkingActivePackIds().contains(this.packInfo.getId());
        }



        @Override
        public void render(@NotNull GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTicks) {
            ResourceLocation iconToRender = this.packInfo.getIconLocation();
            int iconSize = 32;

            if (iconToRender != null) {
                // アイコンを描画
                guiGraphics.blit(iconToRender, left + 2, top + (height - iconSize) / 2, iconSize, iconSize, 0, 0, iconSize, iconSize, iconSize, iconSize);

                // アクティブ状態を示すために枠線を描画する例
                if (this.isCurrentlyActive) {
                    // 内側の枠
                    guiGraphics.renderOutline(left + 1, top + (height - iconSize) / 2 -1, iconSize + 2, iconSize + 2, 0xFFFFFFFF); // 白色
                }

            } else {
                // アイコンがない場合の代替表示 (例: 元のチェックボックス)
                ResourceLocation checkboxSprite = this.isCurrentlyActive ? CHECKBOX_SELECTED_SPRITE : CHECKBOX_UNSELECTED_SPRITE;
                guiGraphics.blit(checkboxSprite, left + 2, top + (height - 16) / 2, 16, 16, 0, 0, 16, 16, 16, 16);
            }

            // サウンドパック名の描画位置をアイコンの幅に合わせて調整
            int textLeftOffset = left + 2 + iconSize + 4; // アイコンの右側に少しスペースを空ける

            Component packName = this.packInfo.getDisplayName(); // ★修正後: getDisplayName() を使用★
            int textColor = this.isCurrentlyActive ? 0xFFFF00 : 0xFFFFFF; // アクティブなら黄色(FFFF00)、非アクティブなら白(FFFFFF)
            guiGraphics.drawString(this.minecraft.font, packName, textLeftOffset, top + 2, textColor);

            Component description = this.packInfo.getDescription();
            if (description != null && !description.getString().isEmpty()) {
                guiGraphics.drawString(this.minecraft.font, description, textLeftOffset, top + 2 + this.minecraft.font.lineHeight + 2, 0xAAAAAA);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) { // 左クリック
                SoundPackList.this.parentScreen.togglePackActivation(this.packInfo.getId());
                this.updateSelectedStatus(); // 自身の表示を更新
                return true;
            }
            return false;
        }

        // ★変更点7: @Override アノテーションを削除
        public @NotNull Component getNarration() {
            // エントリのナレーション (スクリーンリーダー用)
            // "narrator.select" は Minecraft の標準的な翻訳キー
            // packInfo.getId() は SoundPackInfo にも存在するので変更なし
            return Component.translatable("narrator.select", this.packInfo.getId());
        }

        public SoundPackList getList() {
            return list;
        }
    }
}