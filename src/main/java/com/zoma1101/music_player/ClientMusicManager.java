package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
// Configクラスのパスを確認
import com.zoma1101.music_player.sound.MusicDefinition; // 新しいMusicDefinition
import com.zoma1101.music_player.util.MusicConditionEvaluator; // 評価クラス
import net.minecraft.ResourceLocationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientMusicManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 20; // 1秒ごとにチェック

    @Nullable
    private static SoundInstance currentMusicInstance = null;
    @Nullable
    private static String currentMusicSoundEventKey = null; // 再生されているべき曲の SoundEventKey (String)
    private static boolean isStopping = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) {
                updateMusic();
            }
            // isStopping フラグは stopMusic(true) でセットされ、次の Tick でリセットされる
            // これにより、停止処理中にすぐに新しい音楽が再生されるのを防ぐ
            if (isStopping) {
                isStopping = false;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player logged in. Resetting music state.");
        stopMusic(false); // 停止フラグは立てない
        currentMusicSoundEventKey = null;
        // activeCombatEntityIds.clear(); // これは MusicConditionEvaluator.CurrentContext 内で処理される
        updateMusic(); // ログイン直後に音楽チェック
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player logged out. Stopping music.");
        stopMusic(false); // 停止フラグは立てない
        currentMusicSoundEventKey = null;
        // activeCombatEntityIds.clear();
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance soundBeingPlayed = event.getSound();
        if (soundBeingPlayed == null) return;

        ResourceLocation playingSoundEventLocation = soundBeingPlayed.getLocation();
        SoundSource soundSource = soundBeingPlayed.getSource();

        // 音楽ソースのサウンドか確認
        if (SoundSource.MUSIC.equals(soundSource)) {
            String namespace = playingSoundEventLocation.getNamespace();

            // MODの音楽が再生されるべき状況か (currentMusicSoundEventKey が null でない)
            boolean modMusicShouldBePlaying = currentMusicSoundEventKey != null;

            if (Music_Player.MOD_ID.equals(namespace)) {
                // 再生されようとしているのがMODの音楽の場合
                // MusicDefinition を取得して、それが現在再生されるべき音楽 (currentMusicSoundEventKey) と一致するか確認
                MusicDefinition def = Music_Player.soundPackManager.getMusicDefinitionByEventKey(playingSoundEventLocation.getPath()); // getPath() を使用
                boolean isTheCorrectModMusic = def != null && currentMusicSoundEventKey != null && currentMusicSoundEventKey.equals(def.getSoundEventKey());

                if (isTheCorrectModMusic) {
                    // 正しいMODの音楽が再生されようとしている
                    if (currentMusicInstance != soundBeingPlayed) {
                        LOGGER.trace("[onPlaySound] Correct MOD music [{}] is about to play with a new instance. Current instance was: {}", playingSoundEventLocation, currentMusicInstance);
                        // currentMusicInstance = soundBeingPlayed; // 必要に応じて更新
                    }
                } else if (currentMusicSoundEventKey != null) {
                    LOGGER.warn("[onPlaySound] An incorrect MOD music [{}] was about to play. Expected key: [{}]. Stopping it.", playingSoundEventLocation, currentMusicSoundEventKey);
                    event.setSound(null); // サウンドの再生をキャンセル
                }
                else {
                    LOGGER.warn("[onPlaySound] MOD music [{}] was about to play, but no MOD music should be playing. Stopping it.", playingSoundEventLocation);
                    event.setSound(null);
                }

            } else {
                // 再生されようとしているのがMOD以外の音楽 (例: バニラの音楽) の場合
                if (modMusicShouldBePlaying) {
                    LOGGER.info("[onPlaySound] MOD music should be playing (Key: {}). Stopping other music: {}", currentMusicSoundEventKey, playingSoundEventLocation);
                    event.setSound(null); // サウンドの再生をキャンセル
                }
            }
        }
    }

    private static void updateMusic() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null) {
            if (currentMusicInstance != null) {
                LOGGER.warn("Player or Level became null, stopping music.");
                stopMusic(true);
            }
            currentMusicSoundEventKey = null; // プレイヤー/レベルがない場合はターゲットもクリア
            return;
        }

        if (isStopping) {
            LOGGER.trace("Music stopping is in progress, skipping music update check.");
            return;
        }

        // 1. 現在の状況を取得 (MusicConditionEvaluator を使用)
        MusicConditionEvaluator.CurrentContext context = MusicConditionEvaluator.getCurrentContext(player, mc.level, mc.screen);

        // 2. 最適な MusicDefinition を検索 (SoundPackManager を使用)
        // SoundPackManager が null の可能性を考慮
        List<MusicDefinition> definitions = Music_Player.soundPackManager.getActiveMusicDefinitionsSorted(); // nullの場合は空リスト
        MusicDefinition bestMatch = findBestMatch(definitions, context);

        // 3. 再生すべきターゲットの SoundEventKey を決定
        String targetSoundEventKey = null;
        String reason;

        if (bestMatch != null && bestMatch.isValid()) {
            targetSoundEventKey = bestMatch.getSoundEventKey();
            reason = "SoundPack: " + bestMatch.getSoundPackId() + "/" + bestMatch.getMusicFileInPack() +
                    " (Prio:" + bestMatch.getPriority() + ", Key:" + targetSoundEventKey + ")";
        } else {
            reason = "No matching MOD music definition and no vanilla default configured.";
        }

        // 4. 現在の意図された曲 (currentMusicSoundEventKey) と比較して変更が必要か判断
        if (!Objects.equals(targetSoundEventKey, currentMusicSoundEventKey)) {
            LOGGER.info("Music change detected. Current Target Key: [{}], New Target Key: [{}]. Reason: [{}].",
                    currentMusicSoundEventKey, targetSoundEventKey, reason);

            stopMusic(true); // 既存の音楽を停止 (停止フラグを立てる)
            if (targetSoundEventKey != null) {
                playMusicByKey(targetSoundEventKey); // SoundEventKey から再生
            }
            currentMusicSoundEventKey = targetSoundEventKey; // ターゲットを更新

        } else {
            // ターゲットキーに変更がない場合でも、実際に音楽が再生されているかチェックし、必要なら再開
            SoundManager soundManager = mc.getSoundManager();
            boolean shouldBePlaying = (currentMusicSoundEventKey != null);
            boolean isActuallyPlaying = (currentMusicInstance != null && soundManager.isActive(currentMusicInstance));

            if (shouldBePlaying && !isActuallyPlaying) {
                // ターゲットキーが設定されているのに、音楽インスタンスが存在しないか、アクティブでない場合
                LOGGER.warn("Music for key [{}] should be playing but isn't active. Attempting to restart.", currentMusicSoundEventKey);
                // currentMusicInstance が null の場合も playMusicByKey 内で新しいインスタンスが作成される
                playMusicByKey(currentMusicSoundEventKey);
            } else if (!shouldBePlaying && isActuallyPlaying) {
                // ターゲットキーが設定されていないのに、音楽インスタンスがアクティブな場合
                LOGGER.warn("Music should NOT be playing, but instance for key [{}] is active. Stopping.", currentMusicSoundEventKey);
                stopMusic(true); // 停止フラグを立てる
                currentMusicSoundEventKey = null; // ターゲットもクリア
            }
        }
    }

    private static void playMusicByKey(String soundEventKey) {
        // isStopping フラグが立っている場合は再生しない
        if (isStopping) {
            LOGGER.debug("Skipping playMusicByKey for key [{}] because isStopping is true.", soundEventKey);
            return;
        }
        if (soundEventKey == null) {
            LOGGER.warn("playMusicByKey called with null soundEventKey.");
            return;
        }

        try {
            ResourceLocation soundEventRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);
            currentMusicInstance = new SimpleSoundInstance(
                    soundEventRl, // ★ soundEventRl を直接使用 (または soundEvent.getLocation() でもOK)
                    SoundSource.MUSIC,
                    1.0f, 1.0f, SoundInstance.createUnseededRandom(), // Volume, Pitch, Random
                    true,
                    0,
                    SoundInstance.Attenuation.NONE,
                    0.0D, 0.0D, 0.0D,
                    true
            );
            Minecraft.getInstance().getSoundManager().play(currentMusicInstance);
            LOGGER.info("Playing music with key: [{}], resolved to RL: [{}]", soundEventKey, soundEventRl); // ログのRLも修正後のものを表示

        } catch (ResourceLocationException e) {
            LOGGER.error("Invalid ResourceLocation format for sound event key [{}] with namespace [{}]: {}",
                    soundEventKey, Music_Player.MOD_ID, e.getMessage());
            currentMusicInstance = null; // 無効なキーの場合はインスタンスをクリア
        } catch (Exception e) {
            LOGGER.error("Exception occurred trying to play music with key [{}], resolved RL [{}]: {}",
                    soundEventKey, Music_Player.MOD_ID + ":" + soundEventKey, e.getMessage(), e);
            currentMusicInstance = null; // エラー発生時はインスタンスをクリア
        }
    }

    private static void stopMusic(boolean setStoppingFlag) {
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (currentMusicInstance != null) {
            // ResourceLocation stoppedLocation = currentMusicInstance.getLocation(); // ログ出力などに使える
            LOGGER.debug("Stopping music instance for key: {}", currentMusicSoundEventKey);
            soundManager.stop(currentMusicInstance);
            currentMusicInstance = null; // 停止したらインスタンスをクリア
        }
        // 停止フラグを立てるかどうか
        if (setStoppingFlag) {
            isStopping = true;
        }
    }

    @Nullable
    private static MusicDefinition findBestMatch(List<MusicDefinition> definitions, MusicConditionEvaluator.CurrentContext context) {
        // definitions は SoundPackManager によって既に優先度順にソートされている
        for (MusicDefinition definition : definitions) {
            if (definition.isValid()) { // まず定義自体が有効かチェック
                if (MusicConditionEvaluator.doesDefinitionMatch(definition, context)) {
                    // 条件に一致したら、それが最も優先度の高い一致なので返す
                    return definition;
                }
            } else {
                LOGGER.warn("Skipping invalid music definition during match finding: {}", definition);
            }
        }
        // どの定義にも一致しない場合
        return null;
    }

    // getCurrentContext と doesDefinitionMatch は MusicConditionEvaluator に移譲したので削除
    // 戦闘・村判定ヘルパーも MusicConditionEvaluator (または GameContextHelper) にあるので削除
    // CurrentContext 内部クラスも MusicConditionEvaluator にあるので削除
}