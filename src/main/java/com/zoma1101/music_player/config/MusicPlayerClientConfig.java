package com.zoma1101.music_player.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class MusicPlayerClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final MusicPlayerClientConfig INSTANCE;

    public final ForgeConfigSpec.IntValue fadeInTicks;
    public final ForgeConfigSpec.IntValue fadeOutTicks;

    static {
        Pair<MusicPlayerClientConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(MusicPlayerClientConfig::new);
        SPEC = specPair.getRight();
        INSTANCE = specPair.getLeft();
    }

    public MusicPlayerClientConfig(ForgeConfigSpec.Builder builder) {
        builder.push("FadeSettings");
        
        fadeInTicks = builder.comment("Default fade-in ticks for music. (20 ticks = 1 second)")
                .defineInRange("fade_in_ticks", 40, 0, 1000);
                
        fadeOutTicks = builder.comment("Default fade-out ticks for music.")
                .defineInRange("fade_out_ticks", 40, 0, 1000);
                
        builder.pop();
    }
}
