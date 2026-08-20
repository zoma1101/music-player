package com.zoma1101.music_player.client.events;

import com.zoma1101.music_player.Music_Player;
import com.zoma1101.music_player.client.SoundPackSelectionScreen;
import net.minecraft.client.Minecraft; // Minecraftインスタンス取得のため
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber(modid = Music_Player.MOD_ID, value = Dist.CLIENT)
public class GuiEventHandler {

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        // マインクラフトのメイン設定画面をターゲットにする
        if (screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen) {
            
            // 既にIconButtonが追加されているかチェック (重複追加防止)
            for (net.minecraft.client.gui.components.events.GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof IconButton) {
                    return; // 既に追加済みなら何もしない
                }
            }

            // 画面内のウィジェットを探索して、サウンド設定ボタンを見つける
            for (net.minecraft.client.gui.components.events.GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof Button btn) {
                    // "options.sounds" はサウンド設定ボタンの言語キー
                    if (btn.getMessage().getString().equals(Component.translatable("options.sounds").getString()) ||
                        btn.getMessage().getString().equals(Component.translatable("options.sounds.title").getString())) {
                        
                        int newBtnSize = 20;

                        // カスタムアイコンボタンの作成
                        Button soundPackButton = new IconButton(
                                btn, 
                                newBtnSize, 
                                Component.empty(), 
                                (button) -> {
                                    Minecraft.getInstance().setScreen(new SoundPackSelectionScreen(screen));
                                }
                        );
                        
                        event.addListener(soundPackButton);
                        break; // サウンド設定ボタンが見つかったら終了
                    }
                }
            }
        }
    }

    // アイコンを描画するための専用ボタンクラス
    private static class IconButton extends Button {
        private final net.minecraft.resources.ResourceLocation ICON = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, "textures/gui/soundpack_icon.png");
        private final Button targetButton;
        private final int gap = 4;
        private final int originalTargetWidth;

        protected IconButton(Button targetButton, int width, Component message, OnPress onPress) {
            super(targetButton.getX() + targetButton.getWidth() + 4, targetButton.getY(), width, targetButton.getHeight(), message, onPress, Button.DEFAULT_NARRATION);
            this.targetButton = targetButton;
            this.originalTargetWidth = targetButton.getWidth();
        }

        @Override
        public void renderWidget(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
            // レイアウトマネージャ(GridLayout等)によって対象ボタンがリセットされるのに対抗し、
            // 描画の直前に毎回対象ボタンを縮め、自分の位置を対象ボタンの横に追従させる
            if (this.targetButton != null) {
                int shrunkWidth = this.originalTargetWidth - this.getWidth() - gap;
                if (this.targetButton.getWidth() != shrunkWidth) {
                    this.targetButton.setWidth(shrunkWidth);
                }
                this.setX(this.targetButton.getX() + this.targetButton.getWidth() + gap);
                this.setY(this.targetButton.getY());
            }

            // 通常のボタンの背景を描画
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
            
            // アイコンを中央に描画 (16x16)
            int iconX = this.getX() + (this.getWidth() - 16) / 2;
            int iconY = this.getY() + (this.getHeight() - 16) / 2;
            guiGraphics.blit(ICON, iconX, iconY, 0, 0, 16, 16, 16, 16);
        }
    }
}