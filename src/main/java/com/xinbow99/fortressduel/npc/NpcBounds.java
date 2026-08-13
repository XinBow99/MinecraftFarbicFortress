package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.battle.Arena;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/**
 * 把 NPC 關在他該待的地方。
 *
 * <p>商人身上是**原版 AI**：他會自己走動、會被推、可以被拴繩牽走，這些全部交給原版處理——
 * 移動不是我們該重寫的東西。我們只加一條原版不知道的規則：**他不能離開這場對戰**，
 * 也不能跨過中線跑到對面去。
 *
 * <p>「他屬於哪一側」看的是**他被放下去的位置**而不是他現在在哪。不然一旦他飄過中線，
 * 下一 tick 的判斷就會認定他本來就屬於對面，然後把他往更遠的地方推——邊界會自己漂走。
 *
 * <p>夾回去而不是硬釘在原地，是因為玩家有正當理由移動他（牽到牆後面躲砲火）。
 * 這裡只畫界線，界線之內他要去哪都可以。
 */
final class NpcBounds {

    /** 推回去之後離邊界留幾格，避免下一 tick 又剛好壓在線上來回抖。 */
    private static final double BUFFER = 1.0;
    /** 每幾 tick 檢查一次。NPC 走得慢，每 tick 檢查是白花的。 */
    private static final int INTERVAL = 5;

    private final DuelManager duels;
    private int counter;

    NpcBounds(DuelManager duels) {
        this.duels = duels;
    }

    /**
     * @param homes NPC → 他被放下去的位置。由 {@link NpcManager} 維護
     */
    void tick(MinecraftServer server, Map<UUID, BlockPos> homes) {
        if (homes.isEmpty() || ++counter % INTERVAL != 0) return;

        for (Map.Entry<UUID, BlockPos> entry : homes.entrySet()) {
            Entity npc = server.overworld().getEntityInAnyDimension(entry.getKey());
            if (npc == null || !npc.isAlive() || !(npc.level() instanceof ServerLevel level)) continue;

            Duel duel = duels.duelAt(level, entry.getValue());
            if (duel == null) continue;   // 場地放好了但對戰還沒開始，這時沒有分界

            Arena arena = duel.arena();
            Arena.Zone zone = arena.zoneAt(Vec3.atCenterOf(entry.getValue()));
            if (zone == null) continue;

            // confine 在「已經在範圍內」時回傳 null，也就是**沒有越界的正常情況**回的是 null
            Vec3 corrected = arena.confine(npc.position(), zone, BUFFER);
            if (corrected == null) continue;

            npc.snapTo(corrected.x, corrected.y, corrected.z);
            // 動量要歸零，不然被爆炸推出去的那一下會在下一 tick 立刻再飛出去一次
            npc.setDeltaMovement(Vec3.ZERO);
        }
    }
}
