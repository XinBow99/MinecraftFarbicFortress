package com.xinbow99.fortressduel.jobs;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Arena;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.battle.DuelState;
import com.xinbow99.fortressduel.battle.Side;
import com.xinbow99.fortressduel.building.BuildingDef;
import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.npc.NpcDef;
import com.xinbow99.fortressduel.npc.NpcManager;
import com.xinbow99.fortressduel.util.Ground;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工人經濟：礦工與農夫。
 *
 * <p>這是繼怪物賞金之後第二條**要冒風險**的收入。差別在成本的形式：
 * <ul>
 *   <li><b>賞金</b>——付子彈、要瞄準、當下就兌現，在中場跟對手搶</li>
 *   <li><b>工人</b>——付一筆本金、不用操作、要好幾輪才回本，代價是它會被打掉</li>
 * </ul>
 *
 * <p>所以它服務的是不同的玩家與不同的局面：不擅長開槍的人也有一條經濟路線可以走，
 * 而領先的一方則多了一個「打勝負還是打經濟」的選擇——在這之前全場只有熊貓一個目標，
 * 高爆彈與隕石雨除了拆牆之外沒有別的用途。
 *
 * <h2>為什麼工人是 NPC 而不是新的實體</h2>
 *
 * <p>{@link NpcManager} 已經有工人需要的每一件事：拿原版實體當殼、保留原版 AI（所以導航
 * 直接可用）、設得了血量、**打得死**、被 {@code NpcBounds} 關在自己的半場、對戰結束時
 * 自動收掉、{@code /duel cleanup} 也涵蓋。工人就是一個沒有商店的 NPC，這個類別只補上
 * 「替誰工作、做哪一行、採到哪裡了」。
 *
 * <h2>節點是建築藍圖</h2>
 *
 * <p>礦脈與稻田走的是 {@link BuildingPlacer}——跟武器商店同一個擺放器。所以稻田可以是
 * 一整塊帶水源的水田而不是一格小麥（被炸掉時才看得出損失），而且改形狀、改材質、
 * 加一種新的節點都不用寫 Java。
 */
public final class JobManager {

    /**
     * 幾 tick 推進一次工人。
     *
     * <p>導航本來就是漸進的，每 tick 重算一次純粹是浪費；而收成的節奏由 {@code work_ticks}
     * 決定，跟這個間隔無關（累加的是間隔本身，不是次數）。
     */
    private static final int TICK_INTERVAL = 10;

    /** 走到節點多近才算「站定了」，開始累積工時。 */
    private static final double WORK_RANGE = 2.5;

    /** 工人走路的速度倍率。 */
    private static final double WALK_SPEED = 1.0;

    /** 找不到落點就放棄，不要無限重試。 */
    private static final int PLACEMENT_ATTEMPTS = 40;

    private final ConfigManager config;
    private final DuelManager duels;
    private final NpcManager npcs;
    private final BuildingPlacer buildings;
    private final EconomyManager economy;
    private final WeaponSystem weapons;

    /** 實體 UUID → 工人。 */
    private final Map<UUID, Worker> workers = new HashMap<>();
    /** 玩家 UUID → 他那一側場上的節點。 */
    private final Map<UUID, List<WorkNode>> nodes = new HashMap<>();
    /** 玩家 UUID → 這一輪工人賺了多少，下一次停火階段開始時報一次然後歸零。 */
    private final Map<UUID, Integer> earned = new HashMap<>();

    private int tickCounter;

    public JobManager(ConfigManager config, DuelManager duels, NpcManager npcs,
                      BuildingPlacer buildings, EconomyManager economy, WeaponSystem weapons) {
        this.config = config;
        this.duels = duels;
        this.npcs = npcs;
        this.buildings = buildings;
        this.economy = economy;
        this.weapons = weapons;
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> onDeath(entity));
        DuelEvents.END.register((duel, result) -> forget(duel));
    }

    // ---------- 雇用 ----------

    /**
     * 雇一名工人，放在玩家腳邊。
     *
     * @return 給玩家看的錯誤訊息；{@code null} ＝ 成功（跟 {@code DuelManager} 的指令一致）
     */
    public String hire(ServerPlayer player, String jobId) {
        JobDef job = config.jobs().job(jobId);
        if (job == null) {
            return "沒有這種職業（" + jobId + "）。";
        }

        Duel duel = duels.duelOf(player);
        if (duel == null) {
            return "你不在對戰中，沒辦法雇人。";
        }

        int max = config.jobs().maxWorkers();
        if (max > 0 && countWorkers(player.getUUID()) >= max) {
            // 沒有上限的話最優解固定是「把每一塊錢都滾成工人」，其他所有購買都不用考慮了
            return "工人已經滿了（上限 " + max + " 個）。";
        }

        NpcDef npcDef = npcs.npc(job.npc());
        if (npcDef == null) {
            FortressDuel.LOGGER.warn("Job {} references NPC '{}' which is not defined in npcs.yml",
                    job.id(), job.npc());
            return "這個職業設定錯誤（找不到 NPC " + job.npc() + "）。";
        }

        Entity entity = npcs.spawn(duel.arena().level(), npcDef,
                player.blockPosition(), player.getYRot());
        if (entity == null) {
            return "工人生成失敗，請看伺服器 log。";
        }

        workers.put(entity.getUUID(), new Worker(entity.getUUID(), player.getUUID(), job));
        return null;
    }

    public int countWorkers(UUID owner) {
        return (int) workers.values().stream().filter(w -> w.ownerId.equals(owner)).count();
    }

    // ---------- 查詢（商店的說明文字要用） ----------

    public JobDef job(String id) {
        return config.jobs().job(id);
    }

    public NodeDef node(String id) {
        return config.jobs().node(id);
    }

    public int maxWorkers() {
        return config.jobs().maxWorkers();
    }

    // ---------- 每一輪 ----------

    /**
     * 停火階段開始：報告上一輪的產出，然後在雙方陣地各補幾個節點。
     *
     * <p>由 {@code Duel.enterBuild} 呼叫，跟固定收入同一個時點——工人的錢是一筆一筆即時入帳的，
     * 但**訊息**要湊在一起發。每收成一格就講一句的話，一場下來會有上百行洗掉所有其他提示。
     */
    public void onRoundStart(Duel duel, ServerPlayer[] players) {
        for (ServerPlayer player : players) {
            report(player);

            Side side = duel.sideOf(player.getUUID());
            if (side != null) {
                replenish(duel, side, player.getUUID());
            }
        }
    }

    private void report(ServerPlayer player) {
        Integer amount = earned.remove(player.getUUID());
        if (amount == null || amount <= 0) return;

        player.sendSystemMessage(Msg.good("工人上一輪替你賺了 $" + amount
                + "（餘額 $" + economy.balanceOf(player) + "）"));
    }

    /** 把這一側的節點補到設定的數量。 */
    private void replenish(Duel duel, Side side, UUID owner) {
        ServerLevel level = duel.arena().level();
        List<WorkNode> mine = nodes.computeIfAbsent(owner, id -> new ArrayList<>());
        // 先把死掉的清掉（被採光的、被對手炸掉的），否則上限會被空殼佔住
        mine.removeIf(node -> !node.alive(level));

        for (NodeDef def : config.jobs().allNodes()) {
            long alive = mine.stream().filter(node -> node.def().id().equals(def.id())).count();
            int room = def.max() - (int) alive;
            int toPlace = Math.min(def.perRound(), room);

            for (int i = 0; i < toPlace; i++) {
                WorkNode node = place(duel, side, owner, def);
                if (node == null) break; // 這一輪找不到位置就算了，下一輪再試
                mine.add(node);
            }
        }
    }

    /**
     * 在這一側的陣地裡挑一塊地方擺一個節點。
     *
     * <p>落點是從熊貓圈往外的一個環形（{@code min_distance} ~ {@code max_distance}）裡隨機抽的，
     * 所以節點會**慢慢往外散**——縮在家裡蓋一圈厚牆的打法，代價是工人得走到離牆越來越遠的地方。
     *
     * @return 擺好的節點；找不到落點時是 null
     */
    private WorkNode place(Duel duel, Side side, UUID owner, NodeDef def) {
        BuildingDef blueprint = buildings.byId(def.building());
        if (blueprint == null) {
            FortressDuel.LOGGER.warn("Node {} references building '{}' which is not defined in buildings.yml",
                    def.id(), def.building());
            return null;
        }

        BlockPos pen = side.pen();
        if (pen == null) return null; // 熊貓圈還沒圍起來（準備階段），還沒有「陣地」可言

        Arena arena = duel.arena();
        ServerLevel level = arena.level();
        RandomSource random = level.getRandom();

        int width = footprintWidth(blueprint);
        int depth = footprintDepth(blueprint);
        int height = blueprint.layers().size();
        if (width == 0 || depth == 0 || height == 0) return null;

        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = def.minDistance()
                    + random.nextDouble() * (def.maxDistance() - def.minDistance());

            int x = pen.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = pen.getZ() + (int) Math.round(Math.sin(angle) * distance);

            // 地面一律問 Ground，不要碰 heightmap：競技場是一個封閉的玻璃盒，heightmap 在場內
            // 回報的是天花板。同一個根因已經害過熊貓圈蓋在天花板下、玩家在盒頂無限摔死、
            // 怪整群生在天花板上三次（見 Ground 的類別註解）
            BlockPos base = Ground.onSurface(level, x, z).below();

            if (!fits(arena, duel, side, base, width, depth, height)) continue;

            List<BlockPos> cells = new ArrayList<>();
            // anchor 要抵消藍圖自己的 offset：節點是隨機定位的，不像商店那樣相對核心擺，
            // 所以不管設定檔寫了什麼 offset，原點都要正好落在我們挑好的那一格
            BlockPos anchor = base.offset(
                    -blueprint.offsetX(), -blueprint.offsetY(), -blueprint.offsetZ());

            buildings.place(level, blueprint, anchor, false, pos -> {
                arena.recordBefore(pos);
                cells.add(pos.immutable());
            });

            if (cells.isEmpty()) return null;
            return new WorkNode(def, owner, cells, base);
        }
        return null;
    }

    /**
     * 這塊地放得下嗎。
     *
     * <p>四個條件，每一個都對應一種實際會發生的難看結果：
     * <ul>
     *   <li>整份佔地都在場內、都不是框線 —— 蓋到外面的方塊不會被還原，會在世界上留永久痕跡</li>
     *   <li>整份佔地都在**自己的半場** —— 節點是自己的資產，長到中場或對面都說不通</li>
     *   <li>地面是平的 —— 斜坡上的稻田會有一半埋在土裡</li>
     *   <li>上方淨空 —— 否則會直接吃掉玩家剛蓋好的牆，或蓋在別的節點上面</li>
     * </ul>
     */
    private boolean fits(Arena arena, Duel duel, Side side, BlockPos base,
                         int width, int depth, int height) {
        int groundY = base.getY();

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int x = base.getX() + dx;
                int z = base.getZ() + dz;

                BlockPos ground = new BlockPos(x, groundY, z);
                if (!arena.region().contains(ground) || arena.region().isShell(ground)) return false;
                if (arena.zoneAt(Vec3.atCenterOf(ground)) != duel.zoneOf(side)) return false;
                // 平地才蓋。挑剔一點沒關係——找不到就換一個點，而環形上有幾百個候選
                if (Ground.surfaceY(arena.level(), x, z) != groundY + 1) return false;

                for (int dy = 1; dy < height; dy++) {
                    BlockPos above = new BlockPos(x, groundY + dy, z);
                    if (!arena.region().contains(above) || arena.region().isShell(above)) return false;
                    if (!arena.level().getBlockState(above).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static int footprintWidth(BuildingDef blueprint) {
        return blueprint.layers().stream()
                .flatMap(List::stream)
                .mapToInt(String::length)
                .max().orElse(0);
    }

    private static int footprintDepth(BuildingDef blueprint) {
        return blueprint.layers().stream().mapToInt(List::size).max().orElse(0);
    }

    // ---------- 每 tick ----------

    private void onServerTick(MinecraftServer server) {
        if (workers.isEmpty()) return;
        if (++tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;

        for (Worker worker : List.copyOf(workers.values())) {
            tickWorker(server, worker);
        }
    }

    private void tickWorker(MinecraftServer server, Worker worker) {
        ServerPlayer owner = server.getPlayerList().getPlayer(worker.ownerId);
        // 雇主離線時整場對戰本來就暫停了（等重連），工人跟著停——不然「等對手回來」
        // 就變成單方面的免費產能
        if (owner == null) return;

        Duel duel = duels.duelOf(owner);
        if (duel == null || duel.isPaused()) return;

        DuelState state = duel.state();
        if (state != DuelState.BUILD && state != DuelState.COMBAT) return;

        ServerLevel level = duel.arena().level();
        if (!(level.getEntity(worker.entityId) instanceof Mob mob) || !mob.isAlive()) return;

        if (worker.target == null || !worker.target.alive(level)) {
            worker.target = nearestNode(worker, mob.position(), level);
            worker.progress = 0;
        }
        if (worker.target == null) return; // 場上沒有他做得了的活，下一輪補了節點就有

        Vec3 spot = worker.target.center();
        if (mob.position().distanceTo(spot) > WORK_RANGE) {
            walkTo(mob, spot);
            worker.progress = 0;
            return;
        }

        stopWalking(mob);
        mob.getLookControl().setLookAt(spot);

        worker.progress += TICK_INTERVAL;
        if (worker.progress < worker.job.workTicks()) return;
        worker.progress = 0;

        harvest(worker, mob, level, owner);
    }

    /**
     * 叫工人走到某個位置。
     *
     * <p>**兩條路都要下**，因為原版有兩套互相不知道對方存在的移動系統：
     * <ul>
     *   <li>目標式 AI（羊駝、大部分動物）——聽 {@code getNavigation().moveTo}</li>
     *   <li>Brain（村民、蜜蜂、斧頭幫那些新的）——只聽 {@code WALK_TARGET} 記憶，
     *       而且牠的 {@code MoveToTargetSink} 會在沒有那個記憶時**主動把導航停掉**</li>
     * </ul>
     *
     * <p>只下導航的話，用村民當殼的工人會原地不動——牠的 brain 每 tick 把我們的路徑清掉。
     * 只下記憶的話，沒有 brain 的殼完全收不到指令。而 npcs.yml 的 {@code entity} 是設定值，
     * 換成什麼都應該要能動，所以這裡不去猜，兩邊都下。
     *
     * <p>每個間隔重下一次：殼身上還有牠自己的閒晃與看人的行為在搶導航，重下的成本很低，
     * 而少了它工人會走到一半就忘記自己要去哪。
     */
    private static void walkTo(Mob mob, Vec3 spot) {
        mob.getNavigation().moveTo(spot.x, spot.y, spot.z, WALK_SPEED);

        if (mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(spot, (float) WALK_SPEED, (int) WORK_RANGE));
        }
    }

    /** 到了，停下來開工。兩套系統都要收手，理由同 {@link #walkTo}。 */
    private static void stopWalking(Mob mob) {
        mob.getNavigation().stop();

        if (mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /** 找這名工人做得了的、離他最近的活。 */
    private WorkNode nearestNode(Worker worker, Vec3 from, ServerLevel level) {
        return nodes.getOrDefault(worker.ownerId, List.of()).stream()
                .filter(node -> node.def().id().equals(worker.job.node()))
                .filter(node -> node.alive(level))
                .min(java.util.Comparator.comparingDouble(node -> node.center().distanceToSqr(from)))
                .orElse(null);
    }

    /** 收成一格，錢進雇主的口袋。 */
    private void harvest(Worker worker, Mob mob, ServerLevel level, ServerPlayer owner) {
        List<BlockPos> remaining = worker.target.remaining(level);
        if (remaining.isEmpty()) {
            worker.target = null;
            return;
        }

        BlockPos pos = remaining.get(level.getRandom().nextInt(remaining.size()));

        // 不掉落。這是全專案同一條規矩（見 DuelManager.onBlockBreak）：挖得到東西的話，
        // 商店那整套「每元買到多少血量」的建材定價就失效了——工人產出的是**錢**，不是資源。
        // destroyBlock 自己會放破壞粒子與音效，所以視覺回饋不用另外寫
        level.destroyBlock(pos, false, null);
        // 武器系統對這一格累積的傷害要清掉，否則玩家在原地補一塊新方塊會繼承舊傷害，
        // 一面剛補好的牆一發就碎（跟 DuelManager.onBlockBreak 同一個理由）
        weapons.forgetBlock(pos);

        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, mob.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.NEUTRAL, 0.4f, 1.2f);

        economy.pay(owner, worker.job.income());
        earned.merge(owner.getUUID(), worker.job.income(), Integer::sum);
    }

    // ---------- 收尾 ----------

    /**
     * 工人被打死。
     *
     * <p>不重生——跟軍火商同一條規則（見 {@code NpcManager.onNpcDeath}）。那正是他能當成
     * 戰術目標的原因：打掉對方的礦工，他接下來每一輪都少一份收入，而且要再花一次本金才補得回來。
     *
     * <p>實體的清理由 {@link NpcManager} 那邊做，這裡只要把帳銷掉。
     */
    private void onDeath(LivingEntity entity) {
        Worker worker = workers.remove(entity.getUUID());
        if (worker == null) return;

        if (!(entity.level() instanceof ServerLevel level)) return;

        // 只講給看得到的人聽，跟軍火商的死訊同一個範圍：這是場上的事件，不該洗到整個伺服器
        net.minecraft.network.chat.Component text = Msg.plain(
                worker.job.displayName() + " 被擊殺了——那一側少了一份收入。", ChatFormatting.RED);
        level.getPlayers(player -> player.distanceToSqr(entity) < 96 * 96)
                .forEach(player -> player.sendSystemMessage(text));
    }

    /**
     * 對戰結束：只清記憶體。
     *
     * <p>節點的方塊由 {@code Arena.restore()} 跟著整座競技場一起還原（放的時候已經記進快照），
     * 工人的實體由 {@code NpcManager.removeIn()} 收掉——兩條路都已經存在，這裡再做一次
     * 只會變成第二個要維護的清理路徑。
     */
    private void forget(Duel duel) {
        UUID north = duel.north().playerId();
        UUID south = duel.south().playerId();

        workers.values().removeIf(w -> w.ownerId.equals(north) || w.ownerId.equals(south));
        nodes.remove(north);
        nodes.remove(south);
        earned.remove(north);
        earned.remove(south);
    }
}
