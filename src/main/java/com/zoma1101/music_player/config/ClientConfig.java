package com.zoma1101.music_player.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ClientConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue isOverride_BGM;

    static {
        BUILDER.push("BGM Settings");
        isOverride_BGM = BUILDER.comment("Set to true to make this mod's music override background music from other mods.")
                .comment("When enabled, background music played by other mods will be stopped")
                .comment("when this mod's music starts playing based on the defined conditions.")
                .define("Override OtherMods Bgm", false);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}