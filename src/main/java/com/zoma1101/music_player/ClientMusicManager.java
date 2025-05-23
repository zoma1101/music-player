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
    private static boolean isRecordPlaying = false; // レコードが再生中かどうかのフラグ
    @Nullable // 最後に再生されたレコードのインスタンスを保持
    private static SoundInstance lastPlayedRecordInstance = null;


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;

            if (player != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) {
                if (isRecordPlaying) {
                    // レコードが再生中とマークされている場合、実際にまだ再生されているか確認
                    SoundManager soundManager = mc.getSoundManager();
                    if (lastPlayedRecordInstance != null && !soundManager.isActive(lastPlayedRecordInstance)) {
                        LOGGER.info("Record music [{}] seems to have stopped. Resuming MOD music checks.", lastPlayedRecordInstance.getLocation());
                        isRecordPlaying = false;
                        lastPlayedRecordInstance = null; // リセット
                        // MODの音楽ターゲットをクリアして、updateMusicで再評価させる
                        currentMusicSoundEventKey = null;
                        stopMusic(false); // 念のため既存のMOD音楽を停止
                        updateMusic(); // MOD音楽の更新を試みる
                    } else if (lastPlayedRecordInstance == null) {
                        // lastPlayedRecordInstance が何らかの理由でnullだがisRecordPlayingがtrueの場合
                        // (例えばログイン直後など、安全策としてフラグをリセット)
                        LOGGER.warn("isRecordPlaying is true, but lastPlayedRecordInstance is null. Resetting record state.");
                        isRecordPlaying = false;
                        updateMusic();
                    } else {
                        // レコードはまだアクティブなので、MODの音楽が再生されていれば停止する
                        if (currentMusicInstance != null) {
                            LOGGER.debug("Record is still playing. Ensuring MOD music is stopped.");
                            stopMusic(true);
                            currentMusicSoundEventKey = null;
                        }
                    }
                } else {
                    // レコードが再生中でない場合のみ音楽を更新
                    updateMusic();
                }
            }
            if (isStopping) {
                isStopping = false;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player logged in. Resetting music state.");
        stopMusic(false);
        currentMusicSoundEventKey = null;
        isRecordPlaying = false;
        lastPlayedRecordInstance = null;
        updateMusic();
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player logged out. Stopping music.");
        stopMusic(false);
        currentMusicSoundEventKey = null;
        isRecordPlaying = false;
        lastPlayedRecordInstance = null;
    }


    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance soundBeingPlayed = event.getSound();
        if (soundBeingPlayed == null) return;

        ResourceLocation playingSoundEventLocation = soundBeingPlayed.getLocation();
        SoundSource soundSource = soundBeingPlayed.getSource();

        // --- レコード再生の処理 ---
        if (SoundSource.RECORDS.equals(soundSource)) {
            if (!isRecordPlaying || (lastPlayedRecordInstance != null && !lastPlayedRecordInstance.getLocation().equals(playingSoundEventLocation))) {
                // 新しいレコードが再生開始、または別のレコードに切り替わった
                LOGGER.info("Record music [{}] started. Stopping MOD music.", playingSoundEventLocation);
                isRecordPlaying = true;
                lastPlayedRecordInstance = soundBeingPlayed; // 再生中のレコードインスタンスを保存
                // MODの音楽が再生中であれば停止する
                if (currentMusicInstance != null) {
                    stopMusic(true);
                    currentMusicSoundEventKey = null;
                }
            }
            // レコードの再生イベントはそのまま許可
            return;
        }

        // --- MODの音楽と他のMUSICソースの音楽の競合処理 ---
        if (SoundSource.MUSIC.equals(soundSource)) {
            String namespace = playingSoundEventLocation.getNamespace();
            // レコード再生中はMOD音楽は再生されるべきではない
            boolean modMusicShouldBePlaying = currentMusicSoundEventKey != null && !isRecordPlaying;

            if (Music_Player.MOD_ID.equals(namespace)) {
                // 再生されようとしているのがMODの音楽の場合
                MusicDefinition def = Music_Player.soundPackManager.getMusicDefinitionByEventKey(playingSoundEventLocation.getPath());
                boolean isTheCorrectModMusic = def != null && currentMusicSoundEventKey != null && currentMusicSoundEventKey.equals(def.getSoundEventKey());

                if (isRecordPlaying) {
                    LOGGER.warn("[onPlaySound] MOD music [{}] tried to play while a record is playing. Cancelling it.", playingSoundEventLocation);
                    event.setSound(null); // MODの音楽の再生をキャンセル
                    return;
                }

                if (isTheCorrectModMusic) {
                    // 正しいMODの音楽
                } else if (currentMusicSoundEventKey != null) {
                    LOGGER.warn("[onPlaySound] An incorrect MOD music [{}] was about to play. Expected key: [{}]. Cancelling it.", playingSoundEventLocation, currentMusicSoundEventKey);
                    event.setSound(null);
                } else {
                    LOGGER.warn("[onPlaySound] MOD music [{}] was about to play, but no MOD music should be playing. Cancelling it.", playingSoundEventLocation);
                    event.setSound(null);
                }

            } else {
                // 再生されようとしているのがMOD以外のMUSICソースの音楽 (例: バニラの音楽) の場合
                if (modMusicShouldBePlaying) {
                    LOGGER.info("[onPlaySound] MOD music should be playing (Key: {}). Cancelling other music: {}", currentMusicSoundEventKey, playingSoundEventLocation);
                    event.setSound(null);
                }
                // バニラの音楽が再生されようとしていて、MODの音楽が再生されるべきでない場合は、そのまま再生を許可
            }
        }
        // 他のSoundSource (効果音など) は影響を受けない
    }

    private static void updateMusic() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null) {
            if (currentMusicInstance != null) {
                LOGGER.warn("Player or Level became null, stopping music.");
                stopMusic(true);
            }
            currentMusicSoundEventKey = null;
            return;
        }

        if (isStopping) {
            LOGGER.trace("Music stopping is in progress, skipping music update check.");
            return;
        }

        // レコードが再生中なら、MODの音楽は更新しない
        if (isRecordPlaying) {
            // もしMODの音楽が誤って再生されていたら停止する
            if (currentMusicInstance != null) {
                LOGGER.debug("Record is playing, ensuring MOD music is stopped during updateMusic.");
                stopMusic(true);
                currentMusicSoundEventKey = null;
            }
            return;
        }

        MusicConditionEvaluator.CurrentContext context = MusicConditionEvaluator.getCurrentContext(player, mc.level, mc.screen);
        List<MusicDefinition> definitions = Music_Player.soundPackManager.getActiveMusicDefinitionsSorted();
        MusicDefinition bestMatch = findBestMatch(definitions, context);

        String targetSoundEventKey = null;
        String reason;

        if (bestMatch != null && bestMatch.isValid()) {
            targetSoundEventKey = bestMatch.getSoundEventKey();
            reason = "SoundPack: " + bestMatch.getSoundPackId() + "/" + bestMatch.getMusicFileInPack() +
                    " (Prio:" + bestMatch.getPriority() + ", Key:" + targetSoundEventKey + ")";
        } else {
            reason = "No matching MOD music definition.";
        }

        if (!Objects.equals(targetSoundEventKey, currentMusicSoundEventKey)) {
            LOGGER.info("Music change detected. Current Target Key: [{}], New Target Key: [{}]. Reason: [{}].",
                    currentMusicSoundEventKey, targetSoundEventKey, reason);

            stopMusic(true);
            if (targetSoundEventKey != null) {
                playMusicByKey(targetSoundEventKey);
            }
            currentMusicSoundEventKey = targetSoundEventKey;

        } else {
            SoundManager soundManager = mc.getSoundManager();
            boolean shouldBePlaying = (currentMusicSoundEventKey != null);
            // currentMusicInstance が null の場合でも soundManager.isActive は false を返すので安全
            boolean isActuallyPlaying = (currentMusicInstance != null && soundManager.isActive(currentMusicInstance));


            if (shouldBePlaying && !isActuallyPlaying) {
                LOGGER.warn("Music for key [{}] should be playing but isn't active. Attempting to restart.", currentMusicSoundEventKey);
                playMusicByKey(currentMusicSoundEventKey); // playMusicByKey内でisRecordPlayingチェックあり
            } else if (!shouldBePlaying && isActuallyPlaying) {
                LOGGER.warn("Music should NOT be playing, but instance for key [{}] is active. Stopping.", currentMusicSoundEventKey);
                stopMusic(true);
                currentMusicSoundEventKey = null;
            }
        }
    }

    private static void playMusicByKey(String soundEventKey) {
        if (isStopping) {
            LOGGER.debug("Skipping playMusicByKey for key [{}] because isStopping is true.", soundEventKey);
            return;
        }
        // レコード再生中は何もしない
        if (isRecordPlaying) {
            LOGGER.debug("Skipping playMusicByKey for key [{}] because a record is playing.", soundEventKey);
            return;
        }
        if (soundEventKey == null) {
            LOGGER.warn("playMusicByKey called with null soundEventKey.");
            return;
        }

        try {
            ResourceLocation soundEventRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);
            // 既存のインスタンスがあれば停止してから新しいインスタンスを作成
            if (currentMusicInstance != null) {
                Minecraft.getInstance().getSoundManager().stop(currentMusicInstance);
            }
            currentMusicInstance = new SimpleSoundInstance(
                    soundEventRl,
                    SoundSource.MUSIC,
                    1.0f, 1.0f, SoundInstance.createUnseededRandom(),
                    true, // ループ再生
                    0,    // 遅延なし
                    SoundInstance.Attenuation.NONE,
                    0.0D, 0.0D, 0.0D, // 相対位置ではないので絶対座標 (通常MUSICでは無視される)
                    true  // グローバルサウンド (true にすることで Attenuation.NONE と合わせてどこでも聞こえる)
            );
            Minecraft.getInstance().getSoundManager().play(currentMusicInstance);
            LOGGER.info("Playing music with key: [{}], resolved to RL: [{}]", soundEventKey, soundEventRl);

        } catch (ResourceLocationException e) {
            LOGGER.error("Invalid ResourceLocation format for sound event key [{}] with namespace [{}]: {}",
                    soundEventKey, Music_Player.MOD_ID, e.getMessage());
            currentMusicInstance = null;
        } catch (Exception e) {
            LOGGER.error("Exception occurred trying to play music with key [{}], resolved RL [{}]: {}",
                    soundEventKey, Music_Player.MOD_ID + ":" + soundEventKey, e.getMessage(), e);
            currentMusicInstance = null;
        }
    }

    private static void stopMusic(boolean setStoppingFlag) {
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (currentMusicInstance != null) {
            LOGGER.debug("Stopping music instance for key: {}", currentMusicSoundEventKey);
            soundManager.stop(currentMusicInstance); // SoundManagerに停止を指示
            currentMusicInstance = null; // インスタンスの参照をクリア
        }
        // currentMusicSoundEventKey はここではクリアしない。
        // 音楽が「再生されるべきではない」と判断されたときにクリアされるべき。
        if (setStoppingFlag) {
            isStopping = true;
        }
    }

    @Nullable
    private static MusicDefinition findBestMatch(List<MusicDefinition> definitions, MusicConditionEvaluator.CurrentContext context) {
        for (MusicDefinition definition : definitions) {
            if (definition.isValid()) {
                if (MusicConditionEvaluator.doesDefinitionMatch(definition, context)) {
                    return definition;
                }
            } else {
                LOGGER.warn("Skipping invalid music definition during match finding: {}", definition);
            }
        }
        return null;
    }
}