package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.util.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 競技場開場前的方塊快照，結束時照著它把世界還原。
 *
 * <p>兩種模式：
 * <ul>
 *   <li>{@code restoreTerrain = true}：開場先把整個範圍抄一份。打壞的地形、玩家自己蓋的牆
 *       全部會還原——同一塊地可以重複開場。代價是記憶體（48×40×48 ≈ 9 萬格的參考）。</li>
 *   <li>{@code restoreTerrain = false}：只記下「這個 mod 自己放下去的方塊」（圍牆、核心底座），
 *       玩家挖的洞留在世界上。</li>
 * </ul>
 * 兩種模式共用同一份 {@code before} 表，差別只在開場時有沒有先整包抄一次。
 */
public final class ArenaSnapshot {

    private final Map<BlockPos, BlockState> before;
    private final boolean full;

    private ArenaSnapshot(Map<BlockPos, BlockState> before, boolean full) {
        this.before = before;
        this.full = full;
    }

    /** 整個範圍抄一份。 */
    public static ArenaSnapshot full(ServerLevel level, Region region) {
        Map<BlockPos, BlockState> map = new HashMap<>((int) Math.min(region.volume(), Integer.MAX_VALUE));
        for (BlockPos pos : region) {
            BlockState state = level.getBlockState(pos);
            // 空氣佔了絕大多數的格子，不存它可以把記憶體壓到十分之一以下；
            // 還原時「表裡沒有」就代表「本來是空氣」
            if (!state.isAir()) {
                map.put(pos.immutable(), state);
            }
        }
        return new ArenaSnapshot(map, true);
    }

    /** 只記這個 mod 動過的格子。 */
    public static ArenaSnapshot incremental() {
        return new ArenaSnapshot(new HashMap<>(), false);
    }

    /**
     * 在覆寫某一格之前呼叫，把它的原狀記下來。
     * 已經記過的格子不會被覆寫（第一次記到的才是「開場前」的樣子）。
     */
    public void record(ServerLevel level, BlockPos pos) {
        if (full) return; // 整包模式開場已經抄過了
        before.computeIfAbsent(pos.immutable(), level::getBlockState);
    }

    /** 還原。整包模式下沒被記錄的格子代表原本是空氣，一律填回空氣。 */
    public void restore(ServerLevel level, Region region) {
        if (full) {
            for (BlockPos pos : region) {
                BlockState original = before.get(pos);
                BlockState target = original == null ? Blocks.AIR.defaultBlockState() : original;
                if (!level.getBlockState(pos).equals(target)) {
                    // flag 2 ＝ 通知客戶端但不觸發鄰居更新：一次還原幾萬格，
                    // 讓沙子掉下來、水流開來只會爆掉伺服器
                    level.setBlock(pos, target, 2);
                }
            }
        } else {
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), 2);
            }
        }
        before.clear();
    }

    /**
     * 這一格跟開場前一樣嗎。
     *
     * <p>給「只拆人造物」的效果用（見蝕世之影）：跟開場前不同的，就是這場對戰開始之後才
     * 出現的東西——玩家蓋的牆，或這個 mod 自己放的平台與柵欄。天然地形因此不會被動到。
     *
     * <p>{@code restoreTerrain = false} 的增量模式下判斷不準：那時表裡只有 mod 放過的格子，
     * 天然地形查不到就會被當成「本來是空氣」而誤判成人造物。那個模式本來就是「地形隨便挖」，
     * 所以這個誤差跟它的定位一致。
     */
    public boolean isUntouched(ServerLevel level, BlockPos pos) {
        BlockState original = before.get(pos);
        BlockState target = original == null ? Blocks.AIR.defaultBlockState() : original;
        return level.getBlockState(pos).equals(target);
    }

    public int recordedBlocks() {
        return before.size();
    }
}
