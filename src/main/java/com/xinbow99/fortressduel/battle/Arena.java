package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.building.BuildingDef;
import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.npc.NpcDef;
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

    /** A 的圈 → B 的圈的水平單位向量。圈放好才有。 */
    private Vec3 axisDir;
    /** 兩圈的水平中點，也就是中場的正中央。 */
    private Vec3 axisMid;
    /** 中場的半寬（格）。 */
    private double neutralHalf;

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
     * <p>柵欄只是**起始**位置：熊貓可以拿竹子引走，藏到哪裡是玩家的決定。
     */
    public void placePens(BlockPos playerA, BlockPos playerB, DuelSettings settings,
                          BuildingPlacer buildings) {
        int offset = settings.coreOffset();
        penRadiusInner = Math.max(0, settings.penRadius() - 1);
        penA = penSpot(playerA, playerB, offset);
        penB = penSpot(playerB, playerA, offset);

        // 平台要先鋪：placePen 與軍火商都靠 surfaceY 找地面，鋪完之後那個地面才是平台
        placePlatform(penA, settings);
        placePlatform(penB, settings);

        placePen(penA, settings);
        placePen(penB, settings);

        placeDealer(penA, penB, settings, buildings);
        placeDealer(penB, penA, settings, buildings);

        placeBuildings(settings, buildings, penA, penB);
        placeBuildings(settings, buildings, penB, penA);

        setUpZones(settings);
    }

    /**
     * 在熊貓圈周圍鋪一片平的木製平台。
     *
     * <p>開場刻意極簡：一片平台、上面站著玩家、軍火商、和柵欄圍住的熊貓，沒有別的。理由是
     * 這個遊戲的內容應該由玩家在建造階段長出來，開場先擺一棟蓋好的房子只會佔掉那個空間，
     * 而且地形起伏會讓「誰站得比較高」變成開場就決定的隨機優勢。
     *
     * <p>{@code platform_radius: 0} 就完全不鋪，沿用原本的地形。
     */
    private void placePlatform(BlockPos center, DuelSettings settings) {
        int r = settings.platformRadius();
        if (r <= 0) return;

        BlockState planks = blockState(settings.platformBlock(), Blocks.OAK_PLANKS);
        // 站的那一格的下面一格 ＝ 玩家腳下踩著的方塊。木板是**取代**它（草地變木板），
        // 不是鋪在它上面——鋪上面的話所有人都會被墊高一格
        int surface = center.getY() - 1;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, surface, center.getZ() + dz);
                snapshot.record(level, floor);
                level.setBlock(floor, planks, 2);

                // 平台上方淨空：地形可能是山坡，不清的話玩家會被埋在土裡
                for (int y = surface + 1; y <= Math.min(region.maxY(), surface + 4); y++) {
                    BlockPos pos = new BlockPos(floor.getX(), y, floor.getZ());
                    if (level.getBlockState(pos).isAir()) continue;
                    snapshot.record(level, pos);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * 把商人放在平台上、熊貓圈的後方（遠離對手那一側）。
     *
     * <p>不再包在一棟建築裡——極簡開場沒有建築，但商人仍然是這一側的資產：他被打死那一方
     * 就補不到子彈，所以「先做掉對方的商人」還是一條有效的戰術（見 npcs.yml）。
     */
    private void placeDealer(BlockPos pen, BlockPos enemyPen, DuelSettings settings,
                             BuildingPlacer buildings) {
        if (settings.dealerNpc().isBlank()) return;

        NpcDef def = buildings.npcs().npc(settings.dealerNpc());
        if (def == null) {
            FortressDuel.LOGGER.warn("arena.dealer_npc '{}' is not defined in npcs.yml, no shop this duel",
                    settings.dealerNpc());
            return;
        }

        // 站在圈後方一格半徑處，臉朝中場——玩家從自己這側走過來就直接面對他
        int back = settings.penRadius() + 2;
        int dx = pen.getX() - enemyPen.getX();
        int dz = pen.getZ() - enemyPen.getZ();
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        int x = pen.getX() + (alongX ? Integer.signum(dx) * back : 0);
        int z = pen.getZ() + (alongX ? 0 : Integer.signum(dz) * back);

        int y = Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 3);
        float yaw = alongX
                ? (dx > 0 ? 90f : 270f)
                : (dz > 0 ? 0f : 180f);
        buildings.npcs().spawn(level, def, new BlockPos(x, y, z), yaw);
    }

    /**
     * 算出三個區塊的分界。
     *
     * <p>切法是沿著「A 的圈 → B 的圈」這條軸，不是沿著世界的 X 或 Z——場地是就地框在雙方
     * 之間的，兩個人站成東西向、南北向、或任何斜角都可能，照座標軸切一定會有一邊是錯的。
     *
     * <p>中場寬度取兩圈距離的一個比例而不是固定格數：玩家可能站得很近（場地有最小邊長，
     * 但兩座圈不會因此被推開），固定 16 格的中場在那種局面會把整個場地吃掉，兩邊都無處可站。
     */
    private void setUpZones(DuelSettings settings) {
        Vec3 a = new Vec3(penA.getX() + 0.5, 0, penA.getZ() + 0.5);
        Vec3 b = new Vec3(penB.getX() + 0.5, 0, penB.getZ() + 0.5);

        Vec3 axis = b.subtract(a);
        double length = axis.length();
        axisMid = a.add(axis.scale(0.5));
        // 兩座圈幾乎重疊（理論上不會，但不想留一個會 NaN 的除法）就退化成「沒有分界」
        axisDir = length < 1.0E-3 ? null : axis.scale(1.0 / length);
        neutralHalf = length * Math.clamp(settings.neutralFraction(), 0.0, 0.9) / 2.0;
    }

    /** 從 self 往「遠離 enemy」的方向退 offset 格，再貼回地面。 */
    private BlockPos penSpot(BlockPos self, BlockPos enemy, int offset) {
        int dx = self.getX() - enemy.getX();
        int dz = self.getZ() - enemy.getZ();

        // 只退主要那一軸：兩個人幾乎不會剛好斜 45 度，退兩軸反而會讓兩座核心看起來歪掉
        int x = self.getX() + (Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) * offset : 0);
        int z = self.getZ() + (Math.abs(dz) > Math.abs(dx) ? Integer.signum(dz) * offset : 0);

        // 退開之後可能踩空或撞進山壁，夾回競技場的垂直範圍內
        //
        // 回傳的是**站的那一格**（腳下的地面在它下面一格）。surfaceY 走的是 getHeight，
        // 它回的已經是地表上方第一格空氣——再 +1 的話整座圈連同平台都會浮高一層，
        // 玩家原本站的草地不會被木板取代，而是被木板頂到頭上
        int y = Math.clamp(surfaceY(level, x, z), region.minY() + 1, region.maxY() - 4);
        return new BlockPos(x, y, z);
    }

    /**
     * 這一欄的「地面上方第一格」——也就是站上去的那一格。
     *
     * <p><b>必須無視盒子的外殼。</b>封頂之後天花板是這一欄最高的方塊，原版的 heightmap
     * 會直接回報盒頂——熊貓圈、平台、商人、熊貓的生成點全部用這個函式找地面，所以那時候
     * 整套東西會被蓋在天花板底下而不是玩家腳邊。
     *
     * <p>做法是先問 heightmap，答案落在盒子裡就直接用（絕大多數情況，也最便宜）；
     * 答案跑到盒頂以上才往下掃，跳過那一層殼去找真正的地面。
     */
    private int surfaceY(ServerLevel level, int x, int z) {
        int fromHeightmap = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (fromHeightmap <= region.maxY()) return fromHeightmap;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = region.maxY() - 1; y > region.minY(); y--) {
            if (!level.getBlockState(cursor.set(x, y, z)).isAir()) return y + 1;
        }
        return region.minY() + 1;
    }

    // ---------- 建造 ----------

    /**
     * 沿著水平邊界砌一圈牆，把 n×n 的範圍框出來。
     *
     * <p><b>這圈牆是標示，不是圍欄。</b>真正把人跟東西關在場內的全部在程式裡：
     * {@link Duel#keepInside}（玩家）、{@link #confineToArena}（生物）、
     * {@code WeaponSystem.step}（彈丸飛出範圍就消失），以及三個否決「挖／打／在場外放方塊」
     * 的事件處理。所以牆破了一個洞也沒有人跑得出去——它唯一的工作是讓玩家看得到邊界在哪。
     *
     * <p>正因為如此，材質該選看得見的（預設紅色玻璃）而不是屏障。屏障看不見，那個唯一的
     * 工作它做不到，反而製造出「這裡有一道打不穿的空氣牆」這種無法理解的體驗。
     */
    private void placeBorder(DuelSettings settings) {
        BlockState wall = blockState(settings.borderBlock(), Blocks.BARRIER);
        boolean sealed = !settings.borderCapBlock().isBlank();
        BlockState cap = sealed ? blockState(settings.borderCapBlock(), Blocks.GLASS) : null;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (region.isHorizontalEdge(x, z)) {
                    // 封起來的話牆從盒底長到盒頂，玩家往上爬或往下挖都看得到同一面牆。
                    // 沒封的話沿用舊行為：從該欄地表往下扎一格（免得地形起伏時牆底浮空）、
                    // 往上長 border_height
                    int base = sealed ? region.minY()
                            : Math.clamp(surfaceY(level, x, z) - 1, region.minY(), region.maxY());
                    int top = sealed ? region.maxY()
                            : Math.min(region.maxY(), base + settings.borderHeight());
                    for (int y = base; y <= top; y++) {
                        place(cursor.set(x, y, z), wall);
                    }
                } else if (sealed) {
                    // 內部的欄位只鋪頂和底兩層——四面牆上面那圈已經在前一個分支蓋掉了
                    place(cursor.set(x, region.maxY(), z), cap);
                    place(cursor.set(x, region.minY(), z), cap);
                }
            }
        }
    }

    private void place(BlockPos pos, BlockState state) {
        snapshot.record(level, pos);
        // 旗標 2 ＝ 通知客戶端但不觸發鄰居更新：一次放幾萬格，讓沙子掉下來、水流開來會爆掉
        level.setBlock(pos, state, 2);
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

    /**
     * 這一格能不能被「拆人造物」的效果拆掉。
     *
     * <p>三個條件：在場內、不是框線（框線是場地的一部分，誰都拆不掉）、而且跟開場前不一樣
     * ——最後那條就是「人造」的定義，天然地形不動。
     */
    public boolean isBuilt(BlockPos pos) {
        if (!region.contains(pos)) return false;
        if (region.isShell(pos)) return false;

        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.liquid()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false; // 基岩之類

        return !snapshot.isUntouched(level, pos);
    }

    /** 拆掉一格並記進快照的還原路徑。不掉落物品——理由同玩家自己挖（見 DuelManager）。 */
    public void breakBuilt(BlockPos pos) {
        snapshot.record(level, pos);
        level.destroyBlock(pos, false, null, 512);
    }

    // ---------- 區塊 ----------

    /** 場上的三個區塊，沿著兩座熊貓圈的連線切開。 */
    public enum Zone {
        /** A 的半場（penA 那一側）。 */
        A,
        /** 中場：突發事件的怪待的地方，雙方都進不去。 */
        NEUTRAL,
        /** B 的半場（penB 那一側）。 */
        B
    }

    /** 圈放好了沒。沒放好之前沒有分界，也就不做任何範圍限制。 */
    public boolean zonesReady() {
        return axisDir != null;
    }

    /** 沿著軸的有號距離：0 在中場正中央，負的靠 A、正的靠 B。 */
    private double offsetAlongAxis(double x, double z) {
        return new Vec3(x, 0, z).subtract(axisMid).dot(axisDir);
    }

    /**
     * 把一個位置夾回指定的區塊，同時夾進競技場的垂直範圍。
     *
     * <p>水平方向只沿著軸推——保留橫向位置，體感上是撞到一道看不見的牆，而不是被抓走。
     * 已經在範圍內就回傳 null，呼叫端據此決定要不要動它（每 tick 硬夾會讓站在邊界上的東西抖個不停）。
     *
     * @param buffer 推回去之後要離邊界多遠，避免下一 tick 又剛好壓在線上
     */
    public Vec3 confine(Vec3 pos, Zone zone, double buffer) {
        if (!zonesReady()) return null;

        double s = offsetAlongAxis(pos.x, pos.z);
        double target = s;
        switch (zone) {
            case A -> { if (s >= -neutralHalf) target = -neutralHalf - buffer; }
            case B -> { if (s <= neutralHalf) target = neutralHalf + buffer; }
            case NEUTRAL -> {
                if (s < -neutralHalf) target = -neutralHalf + buffer;
                else if (s > neutralHalf) target = neutralHalf - buffer;
            }
        }

        // 天空給得比地底寬，是 arena.height 與 arena.depth 兩個設定拉開的，這裡只負責執行
        double y = Math.clamp(pos.y, region.minY() + 1, region.maxY() - 1);

        if (target == s && y == pos.y) return null;

        Vec3 shifted = pos.add(axisDir.scale(target - s));
        return new Vec3(shifted.x, y, shifted.z);
    }

    /**
     * 把一個位置夾回**整座競技場**（不分區塊），同時夾進垂直範圍。
     *
     * <p>給「可以到處跑、但不能離場」的生物用。跟 {@link #confine} 的差別是沒有沿軸的那道
     * 分界——牠們可以走進任何一方的陣地，只是出不去框線。
     *
     * <p>水平方向夾進框線**內側**：框線那一圈是實心的牆，夾到牆上的話下一 tick 又會被推出來。
     */
    public Vec3 confineToArena(Vec3 pos, double buffer) {
        double x = Math.clamp(pos.x, region.minX() + 1 + buffer, region.maxX() - buffer);
        double z = Math.clamp(pos.z, region.minZ() + 1 + buffer, region.maxZ() - buffer);
        double y = Math.clamp(pos.y, region.minY() + 1, region.maxY() - 1);

        if (x == pos.x && y == pos.y && z == pos.z) return null;
        return new Vec3(x, y, z);
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
