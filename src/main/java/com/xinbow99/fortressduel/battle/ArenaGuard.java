package com.xinbow99.fortressduel.battle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 「這一格是某座競技場的外殼嗎」——給 Mixin 問的靜態橋。
 *
 * <p>跟 {@code BowHooks} 同一個模式：Mixin 被織進原版類別，沒有建構子可以注入子系統，
 * 只能走靜態欄位。範圍刻意壓到最小，只有一個問題。
 *
 * <p>為什麼需要它：外殼的保護有三條路是走我們自己的程式碼的（挖、武器打、在場外放方塊），
 * 但**原版的爆炸完全不經過那些地方**。玩家可以把高爆彈的彈藥（就是 TNT）放在牆邊點掉，
 * 或者隕石雨剛好落在邊上——那時候只有從 {@code ServerExplosion} 攔截才擋得住。
 *
 * <p>單一實例、只在啟動時寫一次、之後只讀，所以不需要同步。
 */
public final class ArenaGuard {

    private static DuelManager duels;

    private ArenaGuard() {
    }

    public static void install(DuelManager manager) {
        duels = manager;
    }

    /**
     * 這一格動不得嗎——它是某座進行中競技場的外殼（四面牆、天花板、地板）。
     *
     * <p>沒有任何對戰進行中時直接回 false，所以平常的爆炸完全不受影響：這個判斷會被掛在
     * 每一次爆炸上，不能讓它在沒有對戰的伺服器上也付出代價。
     */
    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        if (duels == null) return false;

        Duel duel = duels.duelAt(level, pos);
        return duel != null && duel.arena().region().isShell(pos);
    }

    /** 現在有沒有任何對戰在進行。爆炸的熱路徑先問這個，省下逐格判斷。 */
    public static boolean anyActiveDuel() {
        return duels != null && duels.hasActiveDuels();
    }
}
