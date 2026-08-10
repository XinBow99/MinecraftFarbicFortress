package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.building.BuildingDef;
import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.util.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 一場對戰的場地：框出來的 n×n 範圍、兩座核心（烽火台）、兩個出生點，以及還原用的快照。
 *
 * <p>不生成地形——沿用世界原本長什麼樣子，只做三件事：沿著水平邊界砌一圈牆把範圍框出來、
 * 在雙方各自那半場的正中央放一座烽火台、把核心上方清空。
 */
public final class Arena {

    private final ServerLevel level;
    private final Region region;
    private final ArenaSnapshot snapshot;

    /** 兩座核心的位置。開場時還沒有——準備階段結束才會在雙方腳邊長出來。 */
    private BlockPos coreA;
    private BlockPos coreB;

    private Arena(ServerLevel level, Region region, ArenaSnapshot snapshot) {
        this.level = level;
        this.region = region;
        this.snapshot = snapshot;
    }

    /**
     * 就地框出一座競技場：範圍涵蓋雙方目前站的位置。
     *
     * <p>不傳送玩家，所以場地是「長在他們身上」而不是「找一塊空地再把人丟過去」——
     * 邊長取決於兩人站得多遠（見 {@link Region#around}）。
     *
     * <p>這一步**只砌邊界**。核心與建築要等準備階段結束才放，因為它們的位置是相對玩家的，
     * 而玩家在準備階段還可能走動。
     */
    public static Arena build(ServerLevel level, BlockPos posA, BlockPos posB, DuelSettings settings,
                              BuildingPlacer buildings) {
        Region region = Region.around(posA, posB,
                settings.arenaSize(), settings.arenaMargin(),
                -settings.arenaDepth(), settings.arenaHeight());

        ArenaSnapshot snapshot = settings.restoreTerrain()
                ? ArenaSnapshot.full(level, region)
                : ArenaSnapshot.incremental();

        Arena arena = new Arena(level, region, snapshot);
        arena.placeBorder(settings);

        FortressDuel.LOGGER.info("Arena framed at {} ({}x{}, {} blocks in snapshot)",
                region.center(), region.sizeX(), region.sizeZ(), snapshot.recordedBlocks());
        return arena;
    }

    /**
     * 準備階段結束：在雙方腳邊長出核心，並蓋起各自的建築。
     *
     * <p>核心不是放在玩家站的那一格，而是往**遠離對手**的方向退幾格——放在腳下會把人頂起來，
     * 而且核心貼著自己的臉也不好守。退開的方向由「對手在哪邊」決定，所以兩座核心天生就是
     * 一個背對背的佈局。
     */
    public void placeCores(BlockPos playerA, BlockPos playerB, DuelSettings settings,
                           BuildingPlacer buildings) {
        int offset = settings.coreOffset();
        coreA = coreSpot(playerA, playerB, offset);
        coreB = coreSpot(playerB, playerA, offset);

        placeCore(coreA, settings);
        placeCore(coreB, settings);
        placeBuildings(settings, buildings, coreA, coreB);
        placeBuildings(settings, buildings, coreB, coreA);
    }

    /** 從 self 往「遠離 enemy」的方向退 offset 格，再貼回地面。 */
    private BlockPos coreSpot(BlockPos self, BlockPos enemy, int offset) {
        int dx = self.getX() - enemy.getX();
        int dz = self.getZ() - enemy.getZ();

        // 只退主要那一軸：兩個人幾乎不會剛好斜 45 度，退兩軸反而會讓兩座核心看起來歪掉
        int x = self.getX() + (Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) * offset : 0);
        int z = self.getZ() + (Math.abs(dz) > Math.abs(dx) ? Integer.signum(dz) * offset : 0);

        // 退開之後可能踩空或撞進山壁，夾回競技場的垂直範圍內
        int y = Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 4);
        return new BlockPos(x, y + 1, z);
    }

    public boolean coresPlaced() {
        return coreA != null && coreB != null;
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // ---------- 建造 ----------

    /** 沿著水平邊界砌一圈牆，把 n×n 的範圍框出來。 */
    private void placeBorder(DuelSettings settings) {
        BlockState wall = blockState(settings.borderBlock(), Blocks.BARRIER);

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (!region.isHorizontalEdge(x, z)) continue;

                // 牆從該欄地表往下扎一格（免得地形起伏時牆底浮空）、往上長 borderHeight
                int base = Math.clamp(surfaceY(level, x, z) - 1, region.minY(), region.maxY());
                int top = Math.min(region.maxY(), base + settings.borderHeight());
                for (int y = base; y <= top; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    snapshot.record(level, pos);
                    level.setBlock(pos, wall, 2);
                }
            }
        }
    }

    /** 放一座核心：3×3 底座 + 上面一顆烽火台，並把上方淨空好讓光柱看得見。 */
    private void placeCore(BlockPos core, DuelSettings settings) {
        BlockState base = blockState(settings.coreBaseBlock(), Blocks.IRON_BLOCK);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = core.offset(dx, -1, dz);
                snapshot.record(level, pos);
                level.setBlock(pos, base, 2);
            }
        }

        snapshot.record(level, core);
        level.setBlock(core, Blocks.BEACON.defaultBlockState(), 2);

        // 烽火台上面擋著就不會發光；順手清出一個看得到的目標
        for (int y = core.getY() + 1; y <= Math.min(region.maxY(), core.getY() + 6); y++) {
            BlockPos pos = new BlockPos(core.getX(), y, core.getZ());
            if (level.getBlockState(pos).isAir()) continue;
            snapshot.record(level, pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /**
     * 在某一側蓋設定裡列出的建築（武器商店之類）。
     *
     * <p>{@code mirror} 由「對手在自己的哪一邊」決定：藍圖是照「對手在 +Z」的方向畫的，
     * 對手在 −Z 的那一側就整份鏡射，兩邊的店才會都開口朝中場而不是一邊背對。
     */
    private void placeBuildings(DuelSettings settings, BuildingPlacer buildings,
                               BlockPos core, BlockPos enemyCore) {
        boolean mirror = enemyCore.getZ() < core.getZ();

        for (String id : settings.arenaBuildings()) {
            BuildingDef def = buildings.byId(id);
            if (def == null) {
                FortressDuel.LOGGER.warn("Building '{}' from duel.yml is not defined in buildings.yml", id);
                continue;
            }
            buildings.place(level, def, core, mirror, pos -> snapshot.record(level, pos));
        }
    }

    private static BlockState blockState(String id, Block fallback) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
        if (block == null) {
            FortressDuel.LOGGER.warn("Block '{}' from the config does not exist, falling back to {}", id, fallback.getName().getString());
            return fallback.defaultBlockState();
        }
        return block.defaultBlockState();
    }

    // ---------- 收尾 ----------

    public void restore() {
        snapshot.restore(level, region);
    }

    // ---------- 查詢 ----------

    public ServerLevel level() {
        return level;
    }

    public Region region() {
        return region;
    }

    public BlockPos coreA() {
        return coreA;
    }

    public BlockPos coreB() {
        return coreB;
    }

    /**
     * 被拉回場內時要站的位置：自己的核心旁邊。
     *
     * <p>核心還沒生成（準備階段）就退回場地中央——那時本來也還沒有「自己這一側」。
     */
    public Vec3 spawnFor(BlockPos core) {
        if (core == null) {
            BlockPos center = region.center();
            return new Vec3(center.getX() + 0.5,
                    safeSpawnY(center.getX(), center.getZ(), center.getY()), center.getZ() + 0.5);
        }
        int x = core.getX(), z = core.getZ() + 2;
        return new Vec3(x + 0.5, safeSpawnY(x, z, core.getY()), z + 0.5);
    }

    /**
     * 找一個站得住人的高度。
     *
     * <p>不能直接用地表高度圖：那一欄被挖穿（或本來就是洞穴口）時，{@code getHeight} 會回傳
     * 世界的最低建築高度，玩家會被傳到虛空裡。所以先看高度圖，值落在競技場範圍外就改成
     * 從場地頂端往下找第一塊實心方塊，再找不到就退回核心的高度——核心一定站在地上。
     */
    private int safeSpawnY(int x, int z, int fallback) {
        int surface = surfaceY(level, x, z);
        if (surface > region.minY() && surface <= region.maxY()) {
            return surface;
        }

        for (int y = region.maxY(); y > region.minY(); y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y + 1;
            }
        }
        return fallback;
    }

    /** 站在出生點時要朝哪邊看（yaw）：面向場地中央。 */
    public float spawnYawFor(BlockPos core) {
        if (core == null) return 0f;
        return core.getZ() < region.center().getZ() ? 0f : 180f;
    }

    /** 這一格是不是某一座核心的烽火台本體。 */
    public boolean isCoreBlock(BlockPos pos) {
        return pos.equals(coreA) || pos.equals(coreB);
    }
}
