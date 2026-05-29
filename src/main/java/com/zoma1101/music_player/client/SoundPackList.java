package com.zoma1101.music_player.client;

import com.zoma1101.music_player.sound.SoundPackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;

public class SoundPackList extends AbstractSelectionList<SoundPackList.SoundPackEntry> {
    public final SoundPackSelectionScreen parentScreen;

    private SoundPackEntry draggedEntry = null;
    private double dragOffsetY = 0;
    private double mouseXForDragged = 0;
    private double mouseYForDragged = 0;

    public SoundPackList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight, List<SoundPackInfo> packs, SoundPackSelectionScreen parent) {
        super(mc, width, height, y0, y1, itemHeight);
        this.parentScreen = parent;
        this.centerListVertically = false;

        for (SoundPackInfo pack : packs) {
            this.addEntry(new SoundPackEntry(mc, this, pack));
        }
    }

    public SoundPackEntry getDraggedEntry() {
        return this.draggedEntry;
    }

    @Override
    public int getRowWidth() {
        return this.width - 20;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.width - 6;
    }

    @Override
    public void setSelected(@org.jetbrains.annotations.Nullable SoundPackEntry entry) {
        super.setSelected(entry);
    }

    @Override
    public void updateNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, this.parentScreen.getTitle());
        SoundPackEntry selected = this.getSelected();
        if (selected != null) {
            narrationElementOutput.add(NarratedElementType.POSITION, Component.translatable("narrator.list.select", selected.getNarration()));
        }
    }

    public int getRowLeft() {
        return this.width / 2 - this.getRowWidth() / 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 親クラス of getEntryAtPosition
            SoundPackEntry entry = this.getEntryAtPosition(mouseX, mouseY);
            if (entry != null) {
                int rowLeft = this.getRowLeft();
                // チェックボックスまたはアイコン（左端から36px）のクリックならアクティブ状態のON/OFFのみ行う
                if (mouseX >= rowLeft && mouseX < rowLeft + 36) {
                    this.parentScreen.togglePackActivation(entry.packInfo.getId());
                    // 状態が変わったのでリストを再構築して並び順を即時反映
                    this.parentScreen.rebuildSoundPackList();
                    return true;
                } else {
                    // テキスト領域などのクリックならドラッグ開始（すべてのパック）
                    this.draggedEntry = entry;
                    int index = this.children().indexOf(entry);
                    // 親クラスの getRowTop を使用してエントリの上端Y座標を取得
                    int rowTop = this.getRowTop(index);
                    this.dragOffsetY = mouseY - rowTop;
                    this.mouseXForDragged = mouseX;
                    this.mouseYForDragged = mouseY;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggedEntry != null && button == 0) {
            this.mouseXForDragged = mouseX;
            this.mouseYForDragged = mouseY;

            // X座標をリストの中心に固定して、Y座標に対応するエントリを取得する
            SoundPackEntry hoveredEntry = this.getEntryAtPosition(this.width / 2.0, mouseY);
            // すべてのエントリ同士で入れ替え可能とする
            if (hoveredEntry != null) {
                int hoveredIndex = this.children().indexOf(hoveredEntry);
                int currentIndex = this.children().indexOf(this.draggedEntry);
                if (hoveredIndex >= 0 && hoveredIndex != currentIndex) {
                    // リスト内で位置を入れ替える
                    Collections.swap(this.children(), currentIndex, hoveredIndex);
                    // 親スクリーンの一時的な順序を更新
                    this.parentScreen.updateOrderFromList(this.children());
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggedEntry != null) {
            this.draggedEntry = null;
            // 順序変更を確定して親画面の設定に反映し、再描画
            this.parentScreen.updateOrderFromList(this.children());
            this.parentScreen.rebuildSoundPackList();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // 通常のリスト描画
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        // ドラッグ中のエントリを最前面（浮かんでいる状態）で描画
        if (this.draggedEntry != null) {
            int top = (int) (mouseY - this.dragOffsetY);
            int left = this.getRowLeft();
            int width = this.getRowWidth();

            guiGraphics.pose().pushPose();
            
            // 浮かんでいる演出
            // 1. 影を描く (少し右下にずらして半透明の黒)
            guiGraphics.fill(left + 3, top + 3, left + width + 3, top + this.itemHeight + 3, 0xAA000000);
            
            // 2. 少し手前に移動させ、少し拡大する
            guiGraphics.pose().translate(left + width / 2.0f, top + this.itemHeight / 2.0f, 100.0f);
            guiGraphics.pose().scale(1.04f, 1.04f, 1.0f);
            guiGraphics.pose().translate(-(left + width / 2.0f), -(top + this.itemHeight / 2.0f), 0.0f);

            // 3. 背景を少し明るいグレーで塗りつぶす (浮かんでいる設定項目)
            guiGraphics.fill(left, top, left + width, top + this.itemHeight, 0xFF3C3C3C);
            // 4. 黄色い枠線を描く
            guiGraphics.renderOutline(left, top, width, this.itemHeight, 0xFFFFFF00);

            // 5. 中身を描画
            this.draggedEntry.renderDragged(guiGraphics, top, left, width, this.itemHeight, mouseX, mouseY, partialTicks);

            guiGraphics.pose().popPose();
        }
    }

    public static class SoundPackEntry extends AbstractSelectionList.Entry<SoundPackEntry> {
        private final Minecraft minecraft;
        private final SoundPackList list;
        public final SoundPackInfo packInfo;
        public boolean isCurrentlyActive;

        private static final ResourceLocation CHECKBOX_SELECTED_SPRITE = fromNamespaceAndPath("minecraft", "widget/checkbox_selected");
        private static final ResourceLocation CHECKBOX_UNSELECTED_SPRITE = fromNamespaceAndPath("minecraft", "widget/checkbox");

        public SoundPackEntry(Minecraft mc, SoundPackList list, SoundPackInfo pack) {
            this.minecraft = mc;
            this.list = list;
            this.packInfo = pack;
            updateSelectedStatus();
        }

        public void updateSelectedStatus() {
            this.isCurrentlyActive = this.list.parentScreen.getCurrentWorkingActivePackIds().contains(this.packInfo.getId());
        }

        public SoundPackInfo getPackInfo() {
            return this.packInfo;
        }

        public boolean isCurrentlyActive() {
            return this.isCurrentlyActive;
        }

        @Override
        public void render(@NotNull GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTicks) {
            if (this.list.getDraggedEntry() == this) {
                // ドラッグ中の元位置は半透明のプレースホルダーにする
                guiGraphics.fill(left, top, left + width, top + height, 0x44000000);
                guiGraphics.renderOutline(left, top, width, height, 0x44FFFFFF);
                return;
            }

            renderContent(guiGraphics, top, left, width, height, mouseX, mouseY, isHovering, partialTicks);
        }

        public void renderDragged(@NotNull GuiGraphics guiGraphics, int top, int left, int width, int height, int mouseX, int mouseY, float partialTicks) {
            renderContent(guiGraphics, top, left, width, height, mouseX, mouseY, true, partialTicks);
        }

        private void renderContent(@NotNull GuiGraphics guiGraphics, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovering, float partialTicks) {
            ResourceLocation iconToRender = this.packInfo.getIconLocation();
            int iconSize = 32;

            if (iconToRender != null) {
                guiGraphics.blit(iconToRender, left + 2, top + (height - iconSize) / 2, iconSize, iconSize, 0, 0, iconSize, iconSize, iconSize, iconSize);
                if (this.isCurrentlyActive) {
                    guiGraphics.renderOutline(left + 1, top + (height - iconSize) / 2 - 1, iconSize + 2, iconSize + 2, 0xFFFFFFFF);
                }
            } else {
                ResourceLocation checkboxSprite = this.isCurrentlyActive ? CHECKBOX_SELECTED_SPRITE : CHECKBOX_UNSELECTED_SPRITE;
                guiGraphics.blit(checkboxSprite, left + 2, top + (height - 16) / 2, 16, 16, 0, 0, 16, 16, 16, 16);
            }

            int textLeftOffset = left + 2 + iconSize + 4;
            Component packName = this.packInfo.getDisplayName();
            int textColor = this.isCurrentlyActive ? 0xFFFF00 : 0xFFFFFF;
            guiGraphics.drawString(this.minecraft.font, packName, textLeftOffset, top + 2, textColor);

            Component description = this.packInfo.getDescription();
            if (description != null && !description.getString().isEmpty()) {
                guiGraphics.drawString(this.minecraft.font, description, textLeftOffset, top + 2 + this.minecraft.font.lineHeight + 2, 0xAAAAAA);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // 親リスト側で一元管理するため、エントリのクリック処理は無効化する
            return false;
        }

        public @NotNull Component getNarration() {
            return Component.translatable("narrator.select", this.packInfo.getId());
        }
    }
}