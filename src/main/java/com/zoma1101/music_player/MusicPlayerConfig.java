package com.zoma1101.music_player;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * クライアントサイドのグローバルフェード設定を管理するクラス。
 * NeoForge標準の music_player-client.toml に保存されます。
 */
public class MusicPlayerConfig {

    public static final ModConfigSpec SPEC;
    public static final ClientConfig CLIENT;

    static {
        final Pair<ClientConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        SPEC = specPair.getRight();
        CLIENT = specPair.getLeft();
    }

    public static class ClientConfig {
        public final ModConfigSpec.IntValue defaultFadeInTicks;
        public final ModConfigSpec.IntValue defaultFadeOutTicks;

        public ClientConfig(ModConfigSpec.Builder builder) {
            builder.comment("Client-side music player settings").push("General");

            defaultFadeInTicks = builder
                    .comment("Default fade-in duration in ticks (20 ticks = 1 second)")
                    .defineInRange("default_fade_in_ticks", 40, 0, Integer.MAX_VALUE);

            defaultFadeOutTicks = builder
                    .comment("Default fade-out duration in ticks (20 ticks = 1 second)")
                    .defineInRange("default_fade_out_ticks", 40, 0, Integer.MAX_VALUE); // 仕様に合わせてデフォルト40に

            builder.pop();
        }
    }

    // 既存のコードとの互換性のため
    private static final MusicPlayerConfig instance = new MusicPlayerConfig();

    private MusicPlayerConfig() {
    }

    public static MusicPlayerConfig getInstance() {
        return instance;
    }

    public int getDefaultFadeInTicks() {
        return CLIENT.defaultFadeInTicks.get();
    }

    public int getDefaultFadeOutTicks() {
        return CLIENT.defaultFadeOutTicks.get();
    }
}
