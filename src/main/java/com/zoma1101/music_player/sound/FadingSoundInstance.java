package com.zoma1101.music_player.sound;

import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

/**
 * フェードイン・フェードアウト機能を持つサウンドインスタンス。
 * SimpleSoundInstance を継承し、TickableSoundInstance を実装することで、
 * バニラの安定したサウンドロードロジックを利用しつつ、毎ティック音量を調整する。
 */
public class FadingSoundInstance extends SimpleSoundInstance implements TickableSoundInstance {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** フェードフェーズの列挙型 */
    public enum FadePhase {
        FADE_IN, // フェードイン中
        PLAYING, // フル音量で再生中
        FADE_OUT, // フェードアウト中
        DONE // フェードアウト完了（停止すべき状態）
    }

    private FadePhase phase;
    private final int fadeInTicks;
    private final int fadeOutTicks;
    private int fadeTick = 0; // 現在のフェードフェーズ内でのtick数
    private boolean stopped = false;

    /**
     * @param location     サウンドイベントのResourceLocation
     * @param fadeInTicks  フェードインにかけるtick数（0でフェードなし）
     * @param fadeOutTicks フェードアウトにかけるtick数（0でフェードなし）
     */
    public FadingSoundInstance(ResourceLocation location, int fadeInTicks, int fadeOutTicks) {
        super(
                location,
                SoundSource.MUSIC,
                1.0f, // 初期音量を1.0fとして初期化(SoundEngineの初期検証と安全なリロードをパスするため)
                1.0f, // pitch
                SoundInstance.createUnseededRandom(),
                true, // looping
                0, // delay
                SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0,
                true // relative
        );

        this.fadeInTicks = Math.max(0, fadeInTicks);
        this.fadeOutTicks = Math.max(0, fadeOutTicks);

        // フェードイン開始
        if (this.fadeInTicks > 0) {
            this.volume = 0.01f; // 0.0fだとSoundEngineに即座に破棄されるため、ごく低音量(0.01f)から開始する
            this.phase = FadePhase.FADE_IN;
        } else {
            this.volume = 1.0f;
            this.phase = FadePhase.PLAYING;
        }
    }

    @Override
    public void tick() {
        switch (this.phase) {
            case FADE_IN -> {
                fadeTick++;
                this.volume = Math.min(1.0f, (float) fadeTick / fadeInTicks);
                if (fadeTick >= fadeInTicks) {
                    this.volume = 1.0f;
                    this.phase = FadePhase.PLAYING;
                    fadeTick = 0;
                    LOGGER.debug("[FadingSoundInstance] FadeIn complete: {}", this.getLocation());
                }
            }
            case PLAYING -> {
                // フル音量で再生中。何もしない。
            }
            case FADE_OUT -> {
                fadeTick++;
                this.volume = Math.max(0.0f, 1.0f - (float) fadeTick / fadeOutTicks);
                if (fadeTick >= fadeOutTicks || this.volume <= 0.0f) {
                    this.volume = 0.0f;
                    this.phase = FadePhase.DONE;
                    this.stopSound();
                    LOGGER.debug("[FadingSoundInstance] FadeOut complete: {}", this.getLocation());
                }
            }
            case DONE -> {
                this.stopSound();
            }
        }
    }

    /**
     * フェードアウトを開始する。
     * ClientMusicManager から曲を切り替えるタイミングで呼ばれる。
     */
    public void startFadeOut() {
        if (this.phase == FadePhase.DONE)
            return; // 既に完了している場合は無視
        if (this.fadeOutTicks > 0) {
            if (this.phase != FadePhase.FADE_OUT) {
                LOGGER.debug("[FadingSoundInstance] Starting FadeOut: {}", this.getLocation());
                this.phase = FadePhase.FADE_OUT;
                this.fadeTick = 0;
            }
        } else {
            // フェードアウト時間が0なら即座に停止
            this.volume = 0.0f;
            this.phase = FadePhase.DONE;
            this.stopSound();
        }
    }

    private void stopSound() {
        this.stopped = true;
    }

    @Override
    public boolean isStopped() {
        return this.stopped;
    }

    /**
     * フェードアウトが完了しているかどうかを返す。
     * ClientMusicManager が次の曲の再生タイミングを判断するために使う。
     */
    public boolean isFadeOutComplete() {
        return this.phase == FadePhase.DONE;
    }

    /** 現在のフェードフェーズを返す。 */
    public FadePhase getPhase() {
        return this.phase;
    }
}
