package com.xinbow99.fortressduel.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Mixin 進得去、卻拿不到子系統實例——這裡是那道橋。
 *
 * <p>{@code BowItemMixin} 是被 Mixin 織進原版類別的，沒有建構子可以注入東西，所以只能走
 * 靜態欄位。範圍刻意壓到最小：只有「這支弓是不是我們的蓄力武器」與「放開了，開火」兩個問題，
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
     * 這支弓是不是某把蓄力武器。
     *
     * <p>Mixin 靠它決定要不要放行「沒有箭也能拉弓」——原版 {@code BowItem.use} 會先檢查
     * {@code getProjectile}，沒箭就根本不進入拉弓狀態。
     */
    public static boolean isChargedWeapon(ItemStack stack) {
        if (weapons == null) return false;
        WeaponDef weapon = weapons.byStack(stack);
        return weapon != null && weapon.bowLaunched();
    }

    /**
     * 放開右鍵。
     *
     * @param usedTicks 拉了幾 tick
     * @return true ＝ 我們處理掉了，原版不要再射出箭
     */
    public static boolean release(ServerPlayer player, ItemStack stack, int usedTicks) {
        if (weapons == null) return false;
        return weapons.releaseCharged(player, stack, usedTicks);
    }
}
