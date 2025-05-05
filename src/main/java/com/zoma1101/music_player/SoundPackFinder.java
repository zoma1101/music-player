package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.config.SoundPackLoader;
import com.zoma1101.music_player.core.DynamicSoundResourcePack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 'soundpacks' ディレクトリ内の外部SoundPackを検出し、
 * Minecraftのリソースパックシステムに Pack として提供するクラス。
 * (Forge 1.20.1 向け調整版)
 */
public class SoundPackFinder implements RepositorySource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path SOUNDPACKS_DIR = SoundPackLoader.SOUNDPACKS_DIR;
    private final PackType packType;

    public SoundPackFinder(PackType packType) {
        this.packType = packType;
        LOGGER.info("SoundPackFinder initialized for PackType: {}", packType);
    }

    /**
     * Minecraft がリソースパックを検索する際に呼び出すメソッド。
     * soundpacks ディレクトリをスキャンし、有効なパックが見つかれば packConsumer に渡す。
     */
    @Override // RepositorySource インターフェースのメソッドをオーバーライド
    public void loadPacks(@NotNull Consumer<Pack> packConsumer) {
        // SoundPackLoader が実行済みか確認 (同期ロードは最終手段)
        // Ideally, ensure SoundPackLoader.loadSoundPacks() is called reliably before this event fires.
        if (SoundPackLoader.loadedDefinitions.isEmpty()) {
            LOGGER.warn("SoundPackFinder running but SoundPack data might not be loaded. Attempting synchronous load...");
            try {
                SoundPackLoader.loadSoundPacks();
                if (SoundPackLoader.loadedDefinitions.isEmpty()) {
                    LOGGER.warn("SoundPackLoader synchronous load yielded no data.");
                }
            } catch (Exception e) {
                LOGGER.error("Error during synchronous SoundPack loading in Finder", e);
            }
        }

        // soundpacks ディレクトリの存在確認
        if (!Files.exists(SOUNDPACKS_DIR) || !Files.isDirectory(SOUNDPACKS_DIR)) {
            LOGGER.debug("Soundpacks directory not found or not a directory: {}", SOUNDPACKS_DIR.toAbsolutePath());
            return;
        }

        LOGGER.info("Scanning for SoundPacks in: {}", SOUNDPACKS_DIR.toAbsolutePath());

        // soundpacks ディレクトリ内のサブディレクトリを反復処理
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(SOUNDPACKS_DIR, Files::isDirectory)) {
            for (Path packPath : stream) {
                String packDirName = packPath.getFileName().toString();
                // ★ Pack ID を生成 (プレフィックスなし、SoundPackLoaderと一貫性を保つ)
                String generatedPackId = packDirName.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");

                LOGGER.debug("Found potential soundpack directory: {}, generated ID: {}", packDirName, generatedPackId);

                // Packインスタンスを格納する変数 (tryブロックの外で宣言)
                Pack pack = null;

                try {
                    // PackResources を提供する Supplier を作成 (open(String id) シグネチャを使用)
                    final String finalPackIdForSupplier = generatedPackId;
                    final Path finalPackPath = packPath;
                    Pack.ResourcesSupplier resourcesSupplier = (id) -> { // id は受け取るが使わない
                        Map<ResourceLocation, Path> soundsForThisPack = SoundPackLoader.filterSoundsForPack(finalPackIdForSupplier);
                        return new DynamicSoundResourcePack(finalPackIdForSupplier, finalPackPath, soundsForThisPack);
                    };

                    // Pack メタデータ (pack.mcmeta) を読み込む (戻り値は Pack.Info と想定)
                    var metadata = Pack.readPackInfo(generatedPackId, resourcesSupplier);

                    if (metadata == null) {
                        LOGGER.warn("Could not read pack.mcmeta for soundpack: {}. Skipping.", generatedPackId);
                        continue; // メタデータが読めなければスキップ
                    }

                    metadata.compatibility(packType);
                    // Pack インスタンスの生成 (Forge 1.20.1 形式)
                    pack = Pack.create(
                            generatedPackId,
                            Component.literal(packDirName),
                            false,
                            resourcesSupplier,
                            metadata,
                            this.packType,
                            Pack.Position.TOP,
                            false,
                            PackSource.create(
                                    (packTitle) -> Component.translatable("pack.source.mod", packTitle).withStyle(ChatFormatting.YELLOW),
                                    true
                            )
                    );

                } catch (Exception e) {
                    // メタデータ読み込みやPack生成中のエラー
                    LOGGER.error("Failed to process pack information for {}: {}", generatedPackId, e.getMessage(), e); // エラー詳細をログに
                    // pack 変数は null のままになる
                }

                // pack が正常に生成された場合のみ Minecraft に登録
                if (pack != null) {
                    packConsumer.accept(pack);
                    LOGGER.info("Registered SoundPack: {}", generatedPackId);
                }
            } // end for loop
        } catch (IOException e) {
            LOGGER.error("Error scanning soundpacks directory: {}", SOUNDPACKS_DIR.toAbsolutePath(), e);
        }
    } // end loadPacks method

}