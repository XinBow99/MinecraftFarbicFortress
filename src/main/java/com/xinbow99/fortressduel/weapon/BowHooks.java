package com.xinbow99.fortressduel.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Mixin 進得去、卻拿不到子系統實例——這裡是那道橋。
 *
 * <p>{@code BowItemMixin} 是被 Mixin 織進原版類別的，沒有建構子可以注入東西，所以只能走
 * 靜態欄位。範圍刻意壓到最小：只有「這個人現在拉弓算不算開火」與「放開了，開火」兩個問題，
 * 其餘判斷全部留在 {@link WeaponSystem} 裡。
 *
 * <p>單一實例、只在啟動時寫一次、之後只讀，所以不需要同步。
 */
public final class BowHooks {

    private static WeaponSystem weapons;

    private BowHooks() {
    }

    public static void install(WeaponSystem system) {
        weapons = system;
    }

    /**
     * 這個人現在拉的弓算不算我們的武器：他在對戰中，而且副手放著某種彈藥。
     *
     * <p>Mixin 靠它決定要不要放行「沒有箭也能拉弓」。判斷條件必須包含「在對戰中」——
     * 這個 mixin 織的是**原版的弓**，不設限的話整個伺服器的弓都會被接管。
     */
    public static boolean isArmedBow(LivingEntity entity) {
        return weapons != null
                && entity instanceof ServerPlayer player
                && weapons.armedWeaponOf(player) != null;
    }

    /**
     * 這個人在不在對戰中。
     *
     * <p>給 {@code InventoryDropMixin} 用——死亡不掉落只適用於對戰中的玩家。放在這裡是因為
     * 這道橋本來就是「mixin 拿子系統」的唯一入口，不必為了一個布林值再開一條。
     */
    public static boolean isInDuel(Player player) {
        return weapons != null
                && player instanceof ServerPlayer sp
                && weapons.isInDuel(sp);
    }

    /**
     * 放開右鍵。
     *
     * @param usedTicks 拉了幾 tick
     * @return true ＝ 我們處理掉了，原版不要再射出箭
     */
    public static boolean release(ServerPlayer player, int usedTicks) {
        if (weapons == null) return false;
        return weapons.releaseCharged(player, usedTicks);
    }
}
