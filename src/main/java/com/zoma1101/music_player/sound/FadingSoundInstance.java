package com.zoma1101.music_player.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import net.minecraft.sounds.SoundEvent;

public class FadingSoundInstance extends AbstractTickableSoundInstance {
    private final int fadeInTicks;
    private final int fadeOutTicks;
    private int tickCount = 0;
    private boolean isFadingOut = false;
    private int fadeOutTickCount = 0;
    private float maxVolume = 1.0F;

    public FadingSoundInstance(ResourceLocation soundLocation, int fadeInTicks, int fadeOutTicks) {
        super(SoundEvent.createVariableRangeEvent(soundLocation), SoundSource.MUSIC,
                SoundInstance.createUnseededRandom());
        this.fadeInTicks = fadeInTicks;
        this.fadeOutTicks = fadeOutTicks;
        this.looping = true;
        this.delay = 0;
        this.volume = fadeInTicks > 0 ? 0.001F : maxVolume;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
    }

    public void fadeOut() {
        if (!this.isFadingOut) {
            this.isFadingOut = true;
            this.fadeOutTickCount = 0;
            if (this.fadeOutTicks <= 0) {
                this.volume = 0.0F;
                this.stop();
            }
        }
    }

    @Override
    public void tick() {
        if (!this.isFadingOut) {
            this.tickCount++;
            if (this.fadeInTicks > 0 && this.tickCount <= this.fadeInTicks) {
                this.volume = maxVolume * ((float) this.tickCount / (float) this.fadeInTicks);
            } else {
                this.volume = maxVolume;
            }
        } else {
            this.fadeOutTickCount++;
            if (this.fadeOutTicks > 0 && this.fadeOutTickCount <= this.fadeOutTicks) {
                float progress = (float) this.fadeOutTickCount / (float) this.fadeOutTicks;
                this.volume = Math.max(0.001F, maxVolume * (1.0F - progress));
            } else {
                this.volume = 0.0F;
                this.stop();
            }
        }
    }
}
