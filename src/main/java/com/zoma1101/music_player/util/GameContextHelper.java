package com.zoma1101.music_player.util; // パッケージは適切に設定

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.Boat; // Boatをインポート
import net.minecraft.world.entity.vehicle.Minecart; // Minecartをインポート
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * ゲーム内の特定のコンテキスト（戦闘状態、村など）を判断するためのヘルパークラス。
 */
public class GameContextHelper {

    // 戦闘状態追跡用のフィールド
    private static final Set<Integer> activeCombatEntityIds = new HashSet<>();

    // 戦闘・村判定で使用する定数
    private static final double COMBAT_CHECK_RADIUS = 24.0; // 戦闘判定の半径
    private static final double VILLAGE_CHECK_RADIUS = 48.0; // 村判定の半径
    private static final int VILLAGE_CHECK_HEIGHT = 10; // 村判定の高さ方向範囲
    private static final int VILLAGER_THRESHOLD = 2; // 村と判断するのに必要な村人の数

    private GameContextHelper() {}

    /**
     * プレイヤーの周囲の敵対Mobをチェックし、戦闘状態を更新して現在の戦闘状態を返します。
     * 名札付きモブ、またはボートやトロッコに乗っているモブは戦闘状態のトリガーとしないように変更。
     * @param player 判定対象のプレイヤー
     * @param level プレイヤーがいるレベル
     * @return プレイヤーが戦闘状態にあるかどうか
     */
    public static boolean updateCombatStateAndCheck(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            activeCombatEntityIds.clear(); // プレイヤーやレベルが無効なら戦闘状態クリア
            return false;
        }

        // 戦闘状態にある敵対MobのIDを収集
        Set<Integer> currentlyAggressiveIds = new HashSet<>();
        // 指定範囲内のMobを取得し、生存しているもののみを対象とする
        List<Mob> nearbyMobs = level.getEntitiesOfClass(
                Mob.class,
                player.getBoundingBox().inflate(COMBAT_CHECK_RADIUS),
                LivingEntity::isAlive // エンティティが生存しているかをチェックするフィルタ
        );

        for (Mob mob : nearbyMobs) {
            // Mobがボートまたはトロッコに乗っているか確認
            if (mob.isPassenger()) {
                Entity vehicle = mob.getVehicle();
                if (vehicle instanceof Boat || vehicle instanceof Minecart) {
                    continue; // ボートまたはトロッコに乗っているMobは無視
                }
            }

            // Mobが敵対的であり、かつ名札で名前が付けられていない場合
            if (mob.isAggressive() && !mob.hasCustomName()) {
                currentlyAggressiveIds.add(mob.getId()); // IDをリストに追加
            }
        }

        // 新たに戦闘状態に入ったMobのIDを追跡リストに追加
        if (!currentlyAggressiveIds.isEmpty()) {
            activeCombatEntityIds.addAll(currentlyAggressiveIds);
        }

        Iterator<Integer> iterator = activeCombatEntityIds.iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            Entity entity = level.getEntity(entityId); // IDからエンティティを取得

            boolean shouldRemove = false;
            if (entity == null || !entity.isAlive() || entity.distanceToSqr(player) > COMBAT_CHECK_RADIUS * COMBAT_CHECK_RADIUS) {
                shouldRemove = true;
            } else if (entity instanceof Mob mobEntity) {
                if (mobEntity.hasCustomName() ||
                        (mobEntity.isPassenger() && (mobEntity.getVehicle() instanceof Boat || mobEntity.getVehicle() instanceof Minecart))) {
                    shouldRemove = true;
                }
            }

            if (shouldRemove) {
                iterator.remove(); // リストから削除
            }
        }
        return !activeCombatEntityIds.isEmpty();
    }

    /**
     * プレイヤーが村にいる可能性をヒューリスティックに判定します。
     * 周囲に鐘があるか、一定数以上の村人がいるかをチェックします。
     * @param player 判定対象のプレイヤー
     * @param level プレイヤーがいるレベル
     * @return プレイヤーが村にいる可能性が高いかどうか
     */
    public static boolean isInVillageHeuristic(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return false; // プレイヤーやレベルが無効なら村ではない
        }

        BlockPos playerPos = player.blockPosition();

        // 一定範囲内のブロックをチェックし、鐘（BELLS）があるか探す
        boolean bellFound = BlockPos.betweenClosedStream(
                playerPos.offset(-(int) VILLAGE_CHECK_RADIUS, -VILLAGE_CHECK_HEIGHT, -(int) VILLAGE_CHECK_RADIUS),
                playerPos.offset((int) VILLAGE_CHECK_RADIUS, VILLAGE_CHECK_HEIGHT, (int) VILLAGE_CHECK_RADIUS)
        ).anyMatch(pos -> level.getBlockState(pos.immutable()).is(Blocks.BELL));

        if (bellFound) {
            return true; // 鐘があれば村の一部と見なす
        }

        // 一定範囲内に、設定されたしきい値以上の村人がいるかチェック
        List<Villager> nearbyVillagers = level.getEntitiesOfClass(
                Villager.class,
                player.getBoundingBox().inflate(VILLAGE_CHECK_RADIUS),
                LivingEntity::isAlive
        );

        return nearbyVillagers.size() >= VILLAGER_THRESHOLD;
    }
}