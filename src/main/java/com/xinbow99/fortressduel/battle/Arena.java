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
    private final BlockPos coreNorth;
    private final BlockPos coreSouth;
    private final ArenaSnapshot snapshot;

    private Arena(ServerLevel level, Region region, BlockPos coreNorth, BlockPos coreSouth, ArenaSnapshot snapshot) {
        this.level = level;
        this.region = region;
        this.coreNorth = coreNorth;
        this.coreSouth = coreSouth;
        this.snapshot = snapshot;
    }

    /**
     * 以 center 為中心蓋出一座競技場。
     *
     * @param center 中心點（X/Z 有意義，Y 會依地表重算）
     */
    public static Arena build(ServerLevel level, BlockPos center, DuelSettings settings,
                              BuildingPlacer buildings) {
        int groundY = surfaceY(level, center.getX(), center.getZ());
        BlockPos ground = new BlockPos(center.getX(), groundY, center.getZ());
        Region region = Region.square(ground, settings.arenaSize(), -settings.arenaDepth(), settings.arenaHeight());

        ArenaSnapshot snapshot = settings.restoreTerrain()
                ? ArenaSnapshot.full(level, region)
                : ArenaSnapshot.incremental();

        // 兩座核心各站在自己那半場的正中央
        BlockPos coreNorth = coreSpot(level, region.halfNorth().center(), region);
        BlockPos coreSouth = coreSpot(level, region.halfSouth().center(), region);

        Arena arena = new Arena(level, region, coreNorth, coreSouth, snapshot);
        arena.placeBorder(settings);
        arena.placeCore(coreNorth, settings);
        arena.placeCore(coreSouth, settings);
        arena.placeBuildings(settings, buildings);

        FortressDuel.LOGGER.info("Arena built at {} ({}x{}, {} blocks in snapshot)",
                ground, settings.arenaSize(), settings.arenaSize(), snapshot.recordedBlocks());
        return arena;
    }

    /** 核心要放的那一格（烽火台本體的位置）：該欄地表再往上一格。 */
    private static BlockPos coreSpot(ServerLevel level, BlockPos horizontal, Region region) {
        int y = surfaceY(level, horizontal.getX(), horizontal.getZ());
        // 地表可能高過競技場頂或低過底（例如中心點在山壁上），夾回範圍內才不會把核心蓋到牆外
        y = Math.clamp(y, region.minY() + 1, region.maxY() - 4);
        return new BlockPos(horizontal.getX(), y + 1, horizontal.getZ());
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
     * 兩側各蓋一份設定裡列出的建築（武器商店之類）。
     *
     * <p>南半場整個沿 Z 鏡射，所以兩邊的店都開口朝中場，不會一邊面向對手、一邊背對。
     */
    private void placeBuildings(DuelSettings settings, BuildingPlacer buildings) {
        for (String id : settings.arenaBuildings()) {
            BuildingDef def = buildings.byId(id);
            if (def == null) {
                FortressDuel.LOGGER.warn("Building '{}' from duel.yml is not defined in buildings.yml", id);
                continue;
            }
            buildings.place(level, def, coreNorth, false, pos -> snapshot.record(level, pos));
            buildings.place(level, def, coreSouth, true, pos -> snapshot.record(level, pos));
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

    public BlockPos coreNorth() {
        return coreNorth;
    }

    public BlockPos coreSouth() {
        return coreSouth;
    }

    /** 出生點：站在自己核心與場中央之間，面向對手。 */
    public Vec3 spawnFor(BlockPos core) {
        int towardCenter = core.getZ() < region.center().getZ() ? 4 : -4;
        int z = core.getZ() + towardCenter;
        return new Vec3(core.getX() + 0.5, safeSpawnY(core.getX(), z, core.getY()), z + 0.5);
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

    /** 站在出生點時要朝哪邊看（yaw）。北半場朝南 ＝ 0 度，南半場朝北 ＝ 180 度。 */
    public float spawnYawFor(BlockPos core) {
        return core.getZ() < region.center().getZ() ? 0f : 180f;
    }

    /** 這一格是不是某一座核心的烽火台本體。 */
    public boolean isCoreBlock(BlockPos pos) {
        return pos.equals(coreNorth) || pos.equals(coreSouth);
    }
}
