package com.xinbow99.fortressduel.util;

import com.xinbow99.fortressduel.battle.ArenaGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 「這一欄的地面在哪」——全專案唯一的答案。
 *
 * <h2>為什麼不能直接用原版的 heightmap</h2>
 *
 * <p>競技場現在是一個**封閉的玻璃盒**，而 {@code getHeight} 回報的是「這一欄最高的方塊
 * 上面那一格」——盒子裡的每一欄，最高的方塊都是天花板。所以場內任何地方直接問 heightmap，
 * 得到的都是盒頂，不是地面。
 *
 * <p>這件事踩過三次，而且每次症狀看起來都不一樣：熊貓圈與木製平台被蓋在天花板底下、
 * 玩家死後在盒頂重生然後無限摔死、事件的怪物整群生在天花板上。共同的根因只有一個，
 * 所以答案也該只有一個地方——不要再各自呼叫 {@code getHeight}。
 *
 * <p>做法是從 heightmap 的答案往下掃，**把競技場的殼當成透明的**，找第一塊真正的方塊。
 * 沒有任何對戰進行中時就完全不掃，直接回 heightmap 的值（那也是絕大多數的情況）。
 */
public final class Ground {

    private Ground() {
    }

    /**
     * 這一欄站得上去的那一格（地面上方第一格）。
     *
     * @return 找不到地面時回傳 heightmap 原本的答案——那時候本來也沒有更好的猜測
     */
    public static int surfaceY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 沒有對戰就沒有盒子，heightmap 直接就是對的。這條讓平常的路徑一格都不用掃
        if (!ArenaGuard.anyActiveDuel()) return top;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top - 1; y > level.getMinY(); y--) {
            cursor.set(x, y, z);
            // 殼當成不存在：天花板、地板、四面牆都不是地面
            if (ArenaGuard.isProtected(level, cursor)) continue;
            if (!level.getBlockState(cursor).isAir()) return y + 1;
        }
        return top;
    }

    /** 同上，但直接給一個座標。 */
    public static BlockPos onSurface(ServerLevel level, int x, int z) {
        return new BlockPos(x, surfaceY(level, x, z), z);
    }
}
