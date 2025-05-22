package com.zoma1101.music_player.client.events;

import com.zoma1101.music_player.Music_Player;
import com.zoma1101.music_player.client.SoundPackSelectionScreen;
import net.minecraft.client.Minecraft; // Minecraftインスタンス取得のため
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class GuiEventHandler {

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof PackSelectionScreen) { // Minecraftのバニラのリソースパック選択画面
            Component buttonText = Component.translatable("gui.music_player.open_soundpack_screen");
            Button soundPackButton = Button.builder(buttonText, (button) -> {
                        // 新しいサウンドパック選択画面を開く
                        Minecraft.getInstance().setScreen(new SoundPackSelectionScreen(screen));
                    })
                    .bounds(
                            screen.width / 2 + 4, // 位置を調整 (バニラのボタンと重ならないように)
                            screen.height - 24, // Y座標を少し上に
                            150,
                            20
                    )
                    .build();
            event.addListener(soundPackButton);
        }
    }
}