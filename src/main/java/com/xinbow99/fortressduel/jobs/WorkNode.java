package com.xinbow99.fortressduel.jobs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一個已經放到場上的節點：一座礦脈、一塊稻田。
 *
 * <p>不快取「還剩幾格」——每次要用的時候重數。節點會因為兩件完全不同的事而變小：工人採走
 * （我們自己做的），以及對手炸掉（別人做的，而且沒有任何事件會通知我們）。快取一份計數就得
 * 同時攔截這兩條路，而漏掉第二條的症狀是「田被炸平了但工人還站在那裡領錢」。
 * 重數一次的成本是幾十格的 {@code getBlockState}，每 {@code work_ticks} 才一次。
 */
public final class WorkNode {

    private final NodeDef def;
    /** 這個節點是誰的（節點只生在自己的半場，所以擁有者是固定的）。 */
    private final UUID owner;
    /** 藍圖實際寫過的每一格。可採與裝飾都在裡面，過濾交給 {@link #remaining}。 */
    private final List<BlockPos> cells;
    private final Vec3 center;

    public WorkNode(NodeDef def, UUID owner, List<BlockPos> cells, BlockPos origin) {
        this.def = def;
        this.owner = owner;
        this.cells = List.copyOf(cells);
        this.center = Vec3.atCenterOf(origin);
    }

    public NodeDef def() {
        return def;
    }

    public UUID owner() {
        return owner;
    }

    /** 工人要走去哪裡。用藍圖原點而不是重心——原點一定在地面上，重心可能落在方塊裡。 */
    public Vec3 center() {
        return center;
    }

    public List<BlockPos> cells() {
        return cells;
    }

    /** 現在還剩下哪幾格可以採。 */
    public List<BlockPos> remaining(ServerLevel level) {
        List<BlockPos> alive = new ArrayList<>();
        for (BlockPos pos : cells) {
            if (isHarvestable(level, pos)) {
                alive.add(pos);
            }
        }
        return alive;
    }

    /** 還有得採嗎。比 {@code remaining().isEmpty()} 早收工，不用把整份清單建出來。 */
    public boolean alive(ServerLevel level) {
        for (BlockPos pos : cells) {
            if (isHarvestable(level, pos)) return true;
        }
        return false;
    }

    private boolean isHarvestable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        return def.harvestable().contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
