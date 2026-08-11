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
 * 一場對戰的場地：框出來的 n×n 範圍、兩座熊貓圈、兩個出生點，以及還原用的快照。
 *
 * <p>不生成地形——沿用世界原本長什麼樣子，只做兩件事：沿著水平邊界砌一圈牆把範圍框出來、
 * 在雙方各自那半場圍一圈柵欄當熊貓的起始位置。
 */
public final class Arena {

    private final ServerLevel level;
    private final Region region;
    private final ArenaSnapshot snapshot;

    /** 兩座熊貓圈的中心。開場時還沒有——準備階段結束才會在雙方腳邊圍起來。 */
    private BlockPos penA;
    private BlockPos penB;
    /** 圈內可以用來散開熊貓的半徑（柵欄往內縮一格）。 */
    private int penRadiusInner;

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
     * 準備階段結束：在雙方腳邊圍起熊貓圈，並蓋起各自的建築。
     *
     * <p>圈不是圍在玩家站的那一格，而是往**遠離對手**的方向退幾格——圍在腳下會把人卡住，
     * 而且熊貓貼著自己的臉也不好守。退開的方向由「對手在哪邊」決定，所以兩座圈天生就是
     * 一個背對背的佈局。
     *
     * <p>柵欄只是**起始**位置：熊貓可以被牽繩帶走，藏到哪裡是玩家的決定。
     */
    public void placePens(BlockPos playerA, BlockPos playerB, DuelSettings settings,
                          BuildingPlacer buildings) {
        int offset = settings.coreOffset();
        penRadiusInner = Math.max(0, settings.penRadius() - 1);
        penA = penSpot(playerA, playerB, offset);
        penB = penSpot(playerB, playerA, offset);

        placePen(penA, settings);
        placePen(penB, settings);
        placeBuildings(settings, buildings, penA, penB);
        placeBuildings(settings, buildings, penB, penA);
    }

    /** 從 self 往「遠離 enemy」的方向退 offset 格，再貼回地面。 */
    private BlockPos penSpot(BlockPos self, BlockPos enemy, int offset) {
        int dx = self.getX() - enemy.getX();
        int dz = self.getZ() - enemy.getZ();

        // 只退主要那一軸：兩個人幾乎不會剛好斜 45 度，退兩軸反而會讓兩座核心看起來歪掉
        int x = self.getX() + (Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) * offset : 0);
        int z = self.getZ() + (Math.abs(dz) > Math.abs(dx) ? Integer.signum(dz) * offset : 0);

        // 退開之後可能踩空或撞進山壁，夾回競技場的垂直範圍內
        int y = Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 4);
        return new BlockPos(x, y + 1, z);
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

    /**
     * 圍一圈柵欄當熊貓的起始圈，並把圈內清空到站得下熊貓。
     *
     * <p>柵欄逐欄貼著地表放，不是拉一條水平線——場地是就地框在世界原本的地形上的，
     * 地面本來就會起伏，拉水平線的話一半浮空、一半埋在土裡。
     */
    private void placePen(BlockPos center, DuelSettings settings) {
        BlockState fence = blockState(settings.penBlock(), Blocks.OAK_FENCE);
        int r = settings.penRadius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                int ground = Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 3);

                if (edge) {
                    // 柵欄疊兩格：一格高的話熊貓被推一下就跳出去了
                    for (int y = ground; y <= ground + 1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        snapshot.record(level, pos);
                        level.setBlock(pos, fence, 2);
                    }
                } else {
                    // 圈內淨空兩格：熊貓一生出來就卡在方塊裡的話會直接吃到窒息傷害
                    for (int y = ground; y <= ground + 1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.getBlockState(pos).isAir()) continue;
                        snapshot.record(level, pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
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

    public BlockPos penA() {
        return penA;
    }

    public BlockPos penB() {
        return penB;
    }

    /**
     * 熊貓的生成點：圈內散開，但不貼著柵欄。
     *
     * @param index 第幾隻，用來把牠們錯開；超過圈內格數就繞回去疊在一起
     */
    public BlockPos guardianSpot(BlockPos center, int index) {
        int inner = Math.max(0, penRadiusInner);
        int span = inner * 2 + 1;
        int dx = index % span - inner;
        int dz = (index / span) % span - inner;
        int x = center.getX() + dx, z = center.getZ() + dz;
        return new BlockPos(x, Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 3), z);
    }

    /**
     * 被拉回場內時要站的位置：自己的熊貓圈旁邊。
     *
     * <p>圈還沒圍起來（準備階段）就退回場地中央——那時本來也還沒有「自己這一側」。
     */
    public Vec3 spawnFor(BlockPos pen) {
        if (pen == null) {
            BlockPos center = region.center();
            return new Vec3(center.getX() + 0.5,
                    safeSpawnY(center.getX(), center.getZ(), center.getY()), center.getZ() + 0.5);
        }
        int x = pen.getX(), z = pen.getZ() + 3;
        return new Vec3(x + 0.5, safeSpawnY(x, z, pen.getY()), z + 0.5);
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
    public float spawnYawFor(BlockPos pen) {
        if (pen == null) return 0f;
        return pen.getZ() < region.center().getZ() ? 0f : 180f;
    }
}
