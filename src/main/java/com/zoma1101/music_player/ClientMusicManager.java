package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.config.ClientConfig; // ClientConfig をインポート
import com.zoma1101.music_player.sound.MusicDefinition;
import com.zoma1101.music_player.util.MusicConditionEvaluator;
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

            if (player != null && mc.level != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) { // mc.level != null チェックを追加
                if (isRecordPlaying) {
                    SoundManager soundManager = mc.getSoundManager();
                    // レコードが再生中とマークされている場合、実際にまだ再生されているか確認
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
        // ログイン直後はまだワールド情報が完全にロードされていない可能性があるため、
        // updateMusic() は onClientTick で自然に呼び出されるのを待つ方が安全な場合がある。
        // 必要であればここで呼び出すが、ログを見る限りTickEventで十分そう。
        // updateMusic();
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
        String playingNamespace = playingSoundEventLocation.getNamespace();

        // --- 1. レコード (SoundSource.RECORDS) の処理 ---
        // これには、バニラのジュークボックスと、他のMODがRECORDSソースで再生するBGMが含まれる可能性があります。
        if (SoundSource.RECORDS.equals(soundSource)) {
            // Music Player自身のレコード再生はありえないので、これはバニラか他のMODのレコード
            if (!isRecordPlaying || (lastPlayedRecordInstance != null && !lastPlayedRecordInstance.getLocation().equals(playingSoundEventLocation))) {
                LOGGER.info("Record-source music [{}] started.", playingSoundEventLocation);
                isRecordPlaying = true;
                lastPlayedRecordInstance = soundBeingPlayed; // 再生中のレコードインスタンスを保存
                if (currentMusicInstance != null) {
                    LOGGER.info("Stopping MOD music because a record-source sound started.");
                    stopMusic(true);
                    currentMusicSoundEventKey = null;
                }
            }
            // レコードソースの音は常に再生を許可 (Music PlayerがRECORDSで再生することはないため)
            return;
        }

        // --- 2. Music Player自身のBGM (SoundSource.MUSIC, Music_Player.MOD_IDネームスペース) の処理 ---
        if (SoundSource.MUSIC.equals(soundSource) && Music_Player.MOD_ID.equals(playingNamespace)) {
            // Music PlayerのBGMが再生されようとしている
            if (isRecordPlaying) {
                LOGGER.debug("[onPlaySound] MOD music [{}] tried to play while a record-source sound is active. Cancelling MOD music.", playingSoundEventLocation); // WARN -> DEBUG
                event.setSound(null); // Music PlayerのBGMの再生をキャンセル
                return;
            }

            MusicDefinition def = Music_Player.soundPackManager.getMusicDefinitionByEventKey(playingSoundEventLocation.getPath());
            boolean isTheCorrectModMusic = def != null && currentMusicSoundEventKey != null && currentMusicSoundEventKey.equals(def.getSoundEventKey());

            if (isTheCorrectModMusic) {
                // 正しいMusic PlayerのBGMなので、再生を許可
                LOGGER.debug("[onPlaySound] Allowing correct MOD music to play: {}", playingSoundEventLocation);
            } else {
                // 間違ったMusic PlayerのBGM、または再生されるべきでないMusic PlayerのBGM
                if (currentMusicSoundEventKey != null) {
                    LOGGER.warn("[onPlaySound] An incorrect MOD music [{}] was about to play. Expected key: [{}]. Cancelling it.", playingSoundEventLocation, currentMusicSoundEventKey);
                } else {
                    LOGGER.warn("[onPlaySound] MOD music [{}] was about to play, but no MOD music should be playing. Cancelling it.", playingSoundEventLocation);
                }
                event.setSound(null); // Music PlayerのBGMの再生をキャンセル
            }
            return;
        }

        // --- 3. 他のMODまたはバニラのBGM (SoundSource.MUSIC, Music_Player.MOD_ID以外のネームスペース) の処理 ---
        if (SoundSource.MUSIC.equals(soundSource)) {
            // 他のMODまたはバニラのBGMが再生されようとしている
            if (isRecordPlaying) {
                // レコードソースの音がアクティブな場合、他のMUSICソースの音は基本的に許可しない (レコード優先)
                LOGGER.debug("[onPlaySound] Another MUSIC-source sound [{}] tried to play while a record-source sound is active. Letting it play (or be handled by record logic).", playingSoundEventLocation); // DEBUG のまま
                return; // 通常、レコード再生中は他のMUSICは再生されないはず
            }

            boolean modMusicShouldBePlaying = currentMusicSoundEventKey != null; // isRecordPlayingのチェックは上記で行った

            if (ClientConfig.isOverride_BGM.get()) {
                // オーバーライド設定が有効
                if (modMusicShouldBePlaying) {
                    LOGGER.info("[onPlaySound] Override enabled. MOD music should be playing (Key: {}). Cancelling other MUSIC-source sound: {}", currentMusicSoundEventKey, playingSoundEventLocation);
                    event.setSound(null); // 他のMOD/バニラのBGMをキャンセル
                } else {
                    // Music PlayerのBGMが再生されるべきでないなら、他のMOD/バニラのBGMを許可
                    LOGGER.debug("[onPlaySound] Override enabled, but no MOD music to play. Allowing other MUSIC-source sound: {}", playingSoundEventLocation);
                }
            } else {
                // オーバーライド設定が無効なら、他のMOD/バニラのBGMを常に許可
                LOGGER.debug("[onPlaySound] Override disabled. Allowing other MUSIC-source sound: {}", playingSoundEventLocation);
            }
            return;
        }

        // --- 4. その他のサウンドソース (効果音など) ---
        // これらはMusic PlayerのBGMとは競合しないので、常に再生を許可
        LOGGER.trace("[onPlaySound] Allowing non-MUSIC, non-RECORD sound: {} (Source: {})", playingSoundEventLocation, soundSource);
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
            SoundManager soundManager = Minecraft.getInstance().getSoundManager();
            boolean shouldBePlaying = (currentMusicSoundEventKey != null);
            boolean isActuallyPlaying = (currentMusicInstance != null && soundManager.isActive(currentMusicInstance));

            if (shouldBePlaying && !isActuallyPlaying) {
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