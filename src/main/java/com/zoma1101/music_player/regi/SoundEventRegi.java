package com.zoma1101.music_player.regi;

import com.zoma1101.music_player.Music_Player;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;

public class SoundEventRegi {
    // サウンドイベントを登録するためのDeferredRegisterを作成
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Music_Player.MOD_ID); // MODIDを使用

    // --- BGM Sound Events ---
    // ★デフォルト/汎用 BGM
    public static final RegistryObject<SoundEvent> BGM_DEFAULT = registerSoundEvent("bgm_default");
    // ★デフォルト/汎用 BGM
    public static final RegistryObject<SoundEvent> BGM_PLAINS = registerSoundEvent("bgm_plains");
    // ★森バイオーム用 BGM
    public static final RegistryObject<SoundEvent> BGM_FOREST = registerSoundEvent("bgm_forest");
    // ★砂漠バイオーム用 BGM
    public static final RegistryObject<SoundEvent> BGM_DESERT = registerSoundEvent("bgm_desert");
    // ★海バイオーム用 BGM (例)
    public static final RegistryObject<SoundEvent> BGM_OCEAN = registerSoundEvent("bgm_ocean");

    // ★追加: 高高度用 BGM
    public static final RegistryObject<SoundEvent> BGM_HIGH_ALTITUDE = registerSoundEvent("bgm_high_altitude");
    // ★追加: 低高度用 BGM
    public static final RegistryObject<SoundEvent> BGM_LOW_ALTITUDE = registerSoundEvent("bgm_low_altitude");
    // ★追加: 作業台用 BGM
    public static final RegistryObject<SoundEvent> BGM_CRAFTING = registerSoundEvent("bgm_crafting");
    // ★追加: 戦闘用 BGM
    public static final RegistryObject<SoundEvent> BGM_COMBAT = registerSoundEvent("bgm_combat");
    // ★追加: 夜(汎用) BGM
    public static final RegistryObject<SoundEvent> BGM_NIGHT = registerSoundEvent("bgm_night");

    // --- Village Specific BGM ---
    // ★デフォルトの村BGM (マッピング外のバイオーム用)
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_DEFAULT = registerSoundEvent("bgm_village_default");
    // ★平原(Plains)の村用
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_PLAINS = registerSoundEvent("bgm_village_plains");
    // ★砂漠(Desert)の村用
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_DESERT = registerSoundEvent("bgm_village_desert");
    // ★雪原(Snowy Plains)の村用
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_SNOWY = registerSoundEvent("bgm_village_snowy");
    // ★タイガ(Taiga)の村用
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_TAIGA = registerSoundEvent("bgm_village_taiga");
    // ★沼地(Taiga)の村用
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_SWAMP = registerSoundEvent("bgm_village_swamp");
    public static final RegistryObject<SoundEvent> BGM_VILLAGE_NIGHT = registerSoundEvent("bgm_village_night");



    // ヘルパーメソッド: SoundEventを簡単に登録できるようにします
    private static RegistryObject<SoundEvent> registerSoundEvent(String name) {
        ResourceLocation id = fromNamespaceAndPath(Music_Player.MOD_ID, name);
        // SoundEventインスタンスを生成して登録します
        // SoundEvent.createVariableRangeEvent が推奨される方法です (1.19.3以降)
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    // このメソッドをメインクラスのコンストラクタから呼び出して、イベントバスに登録します
    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}