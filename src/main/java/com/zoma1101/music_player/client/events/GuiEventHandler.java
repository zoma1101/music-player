package com.zoma1101.music_player.client.events;

import com.zoma1101.music_player.Music_Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen; // PackSelectionScreen をインポート
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
                        // SoundPackLoaderのインスタンス生成を削除
                        //Minecraft.getInstance().setScreen(new SoundPackSelectionScreen(screen));
                    })
                    .bounds(
                            screen.width / 2 + 4,
                            screen.height - 24,
                            150,
                            20
                    )
                    .build();
            event.addListener(soundPackButton);
        }
    }
}