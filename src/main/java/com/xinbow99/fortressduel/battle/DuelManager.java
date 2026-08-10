package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 挑戰與對戰的總管：誰在挑戰誰、哪些對戰正在進行、競技場要蓋在哪裡。
 *
 * <p>所有 Fabric 事件的掛勾都收在 {@link #register()} 一個地方，其他子系統要插手對戰的話
 * 走 {@link com.xinbow99.fortressduel.core.DuelEvents}，不要再各自去掛原版事件。
 */
public final class DuelManager {

    private final ConfigManager config;
    /** 對戰要用到的其他子系統，開場時交給 Duel。 */
    private DuelServices services;

    /** 玩家 → 他正在打的那一場。兩個 UUID 會指向同一個 Duel 物件。 */
    private final Map<UUID, Duel> duelsByPlayer = new HashMap<>();
    private final List<Duel> activeDuels = new ArrayList<>();

    /** 被挑戰者 → 收到的挑戰書（同一個人可能同時被好幾個人挑戰）。 */
    private final Map<UUID, List<Challenge>> challenges = new HashMap<>();

    private long serverTick;

    public DuelManager(ConfigManager config) {
        this.config = config;
    }

    /** 啟動時注入。子系統反過來也需要 DuelManager，所以不能走建構子。 */
    public void attach(DuelServices services) {
        this.services = services;
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                allowBreak(player instanceof ServerPlayer sp ? sp : null, pos));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player instanceof ServerPlayer sp ? onAttackBlock(sp, pos) : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                player instanceof ServerPlayer sp ? onUseBlock(sp, hand) : InteractionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowDamage);
    }

    // ---------- 挑戰 ----------

    /** @return 給發起者看的結果訊息；null ＝ 成功送出 */
    public String challenge(ServerPlayer challenger, ServerPlayer target) {
        DuelSettings settings = config.settings();

        if (challenger == target) return "你不能挑戰自己。";
        if (isInDuel(challenger)) return "你正在對戰中。";
        if (isInDuel(target)) return target.getGameProfile().name() + " 正在對戰中。";
        if (challenger.level() != target.level()) return "對方不在同一個維度。";

        double distance = challenger.position().distanceTo(target.position());
        if (distance > settings.maxChallengeDistance()) {
            return "對方太遠了（上限 " + settings.maxChallengeDistance() + " 格，目前 "
                    + Math.round(distance) + " 格）。";
        }

        List<Challenge> inbox = challenges.computeIfAbsent(target.getUUID(), k -> new ArrayList<>());
        // 重複挑戰同一個人只更新逾時，不會塞出兩張挑戰書
        inbox.removeIf(c -> c.challenger().equals(challenger.getUUID()));
        inbox.add(new Challenge(challenger.getUUID(), challenger.getGameProfile().name(), target.getUUID(),
                serverTick + settings.challengeTimeoutSeconds() * 20L));

        String challengerName = challenger.getGameProfile().name();
        target.sendSystemMessage(Msg.info(challengerName + " 向你發起要塞對戰！輸入 /duel accept "
                + challengerName + " 接受（" + settings.challengeTimeoutSeconds() + " 秒內有效）"));
        challenger.sendSystemMessage(Msg.good("已向 " + target.getGameProfile().name() + " 發出挑戰。"));
        return null;
    }

    /** @return 給接受者看的結果訊息；null ＝ 對戰已開始 */
    public String accept(ServerPlayer target, ServerPlayer challenger) {
        List<Challenge> inbox = challenges.get(target.getUUID());
        if (inbox == null) return "你沒有收到任何挑戰。";

        Challenge challenge = inbox.stream()
                .filter(c -> c.challenger().equals(challenger.getUUID()))
                .findFirst().orElse(null);
        if (challenge == null) return "你沒有收到來自這個人的挑戰。";

        inbox.remove(challenge);
        if (challenge.isExpired(serverTick)) return "這張挑戰書已經逾時了。";
        if (isInDuel(target)) return "你正在對戰中。";
        if (isInDuel(challenger)) return "對方已經在別場對戰中了。";
        if (challenger.level() != target.level()) return "對方不在同一個維度。";

        ServerLevel level = target.level();
        DuelSettings settings = config.settings();
        BlockPos site = findArenaSite(level, target.blockPosition(), settings);
        if (site == null) {
            return "附近找不到夠空曠的地方開場，換個位置再試一次。";
        }

        Duel duel = Duel.start(level.getServer(), level, site, settings, services, challenger, target);
        activeDuels.add(duel);
        duelsByPlayer.put(challenger.getUUID(), duel);
        duelsByPlayer.put(target.getUUID(), duel);

        // 開場了就把雙方其他還掛著的挑戰書清掉，免得打完被一堆逾時訊息洗版
        challenges.remove(target.getUUID());
        challenges.remove(challenger.getUUID());
        return null;
    }

    /** @return 給拒絕者看的結果訊息；null ＝ 已拒絕 */
    public String deny(ServerPlayer target, ServerPlayer challenger) {
        List<Challenge> inbox = challenges.get(target.getUUID());
        if (inbox == null || !inbox.removeIf(c -> c.challenger().equals(challenger.getUUID()))) {
            return "你沒有收到來自這個人的挑戰。";
        }
        challenger.sendSystemMessage(Msg.warn(target.getGameProfile().name() + " 拒絕了你的挑戰。"));
        return null;
    }

    /** @return 給投降者看的結果訊息；null ＝ 已投降 */
    public String forfeit(ServerPlayer player) {
        Duel duel = duelsByPlayer.get(player.getUUID());
        if (duel == null) return "你目前沒有在對戰。";

        Side side = duel.sideOf(player.getUUID());
        duel.finish(Duel.Result.forfeit(duel.opponentOf(side).playerId()));
        return null;
    }

    // ---------- 場地選址 ----------

    /**
     * 在 origin 附近找一塊夠平的地開場。
     *
     * <p>不生成地形，所以「夠平」是唯一的條件：抽幾個候選點，量它們範圍內的地表高低差，
     * 取落差最小的那個。全部都太崎嶇（落差超過競技場高度的一半）就回 null，讓玩家換地方。
     */
    private BlockPos findArenaSite(ServerLevel level, BlockPos origin, DuelSettings settings) {
        int minDistance = settings.arenaSize() / 2 + settings.arenaMinSeparation();
        int maxDistance = Math.max(minDistance + 1, settings.arenaSearchRadius());

        BlockPos best = null;
        int bestSpread = Integer.MAX_VALUE;

        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            int distance = minDistance + level.getRandom().nextInt(maxDistance - minDistance);
            BlockPos candidate = origin.offset(
                    (int) Math.round(Math.cos(angle) * distance), 0,
                    (int) Math.round(Math.sin(angle) * distance));

            if (overlapsExistingArena(level, candidate, settings)) continue;

            int spread = surfaceSpread(level, candidate, settings.arenaSize());
            if (spread < bestSpread) {
                bestSpread = spread;
                best = candidate;
            }
        }

        return bestSpread <= settings.arenaHeight() / 2 ? best : null;
    }

    /** 抽樣量地表落差：只看四角與中心，夠判斷「這裡是不是山壁」而不用掃幾千格。 */
    private int surfaceSpread(ServerLevel level, BlockPos center, int size) {
        int half = size / 2;
        int[][] samples = {
                {0, 0}, {-half, -half}, {half, -half}, {-half, half}, {half, half},
                {0, -half}, {0, half}, {-half, 0}, {half, 0}
        };
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int[] s : samples) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    center.getX() + s[0], center.getZ() + s[1]);
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return max - min;
    }

    private boolean overlapsExistingArena(ServerLevel level, BlockPos candidate, DuelSettings settings) {
        int keepOut = settings.arenaSize() + settings.arenaMinSeparation();
        for (Duel duel : activeDuels) {
            if (duel.arena().level() != level) continue;
            Region other = duel.arena().region();
            if (Math.abs(other.center().getX() - candidate.getX()) < keepOut
                    && Math.abs(other.center().getZ() - candidate.getZ()) < keepOut) {
                return true;
            }
        }
        return false;
    }

    // ---------- 事件 ----------

    private void onServerTick(MinecraftServer server) {
        serverTick++;

        expireChallenges(server);

        // 用索引反著走：tick 裡面可能會結束對戰，反著刪不會跳過元素
        for (int i = activeDuels.size() - 1; i >= 0; i--) {
            Duel duel = activeDuels.get(i);
            duel.tick();
            if (duel.state() == DuelState.ENDED) {
                activeDuels.remove(i);
                duelsByPlayer.values().removeIf(d -> d == duel);
            }
        }
    }

    private void expireChallenges(MinecraftServer server) {
        Iterator<Map.Entry<UUID, List<Challenge>>> it = challenges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Challenge>> entry = it.next();
            entry.getValue().removeIf(challenge -> {
                if (!challenge.isExpired(serverTick)) return false;
                ServerPlayer challenger = server.getPlayerList().getPlayer(challenge.challenger());
                if (challenger != null) {
                    challenger.sendSystemMessage(Msg.warn("你的挑戰逾時了，對方沒有回應。"));
                }
                return true;
            });
            if (entry.getValue().isEmpty()) it.remove();
        }
    }

    private void onDisconnect(ServerPlayer player) {
        challenges.remove(player.getUUID());
        challenges.values().forEach(inbox -> inbox.removeIf(c -> c.challenger().equals(player.getUUID())));

        // 對戰本身不在這裡收尾——下一個 tick 的 Duel.tick() 會發現人不見了並判給對手，
        // 這樣「離線判負」只有一條路徑
    }

    /** 對戰中不准挖競技場外面的方塊，也不准挖核心與框線；場內其他地方隨便挖。 */
    private boolean allowBreak(ServerPlayer player, BlockPos pos) {
        if (player == null) return true;

        Duel duel = duelsByPlayer.get(player.getUUID());
        if (duel == null) {
            // 沒在對戰的人不能動別人的競技場（含核心）
            return activeDuels.stream().noneMatch(d -> d.arena().region().contains(pos));
        }

        Region region = duel.arena().region();
        if (!region.contains(pos)) {
            player.sendSystemMessage(Msg.warn("對戰期間不能挖競技場外面的方塊。"));
            return false;
        }
        if (duel.arena().isCoreBlock(pos)) {
            // 核心不是用挖的，是用打的（見 onAttackBlock）
            return false;
        }
        if (region.isHorizontalEdge(pos.getX(), pos.getZ())) {
            player.sendSystemMessage(Msg.warn("那是競技場的框線，拆不掉。"));
            return false;
        }
        return true;
    }

    /**
     * 建造階段以外不能擺放方塊。
     *
     * <p>只擋「拿著方塊右鍵」——右鍵開箱子、按拉桿、用武器都還是通的，所以攻擊階段照樣能操作場地，
     * 只是不能再長出新的牆。
     */
    private InteractionResult onUseBlock(ServerPlayer player, InteractionHand hand) {
        Duel duel = duelsByPlayer.get(player.getUUID());
        if (duel == null) return InteractionResult.PASS;
        if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) return InteractionResult.PASS;
        if (duel.state().canPlaceBlocks()) return InteractionResult.PASS;

        player.sendSystemMessage(Msg.warn("攻擊階段不能擺放方塊，等下一輪建造階段。"));
        return InteractionResult.FAIL;
    }

    /**
     * 建造階段雙方不能互相傷害。
     *
     * <p>只擋「對手打你」這一種來源——摔傷、溺水、怪物照樣算，否則建造階段會變成無敵時間。
     */
    private boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer victim)) return true;

        Duel duel = duelsByPlayer.get(victim.getUUID());
        if (duel == null || duel.state().canAttack()) return true;

        return !(source.getEntity() instanceof ServerPlayer attacker) || !duel.involves(attacker.getUUID());
    }

    /** 左鍵打敵方核心 → 扣核心血量。打自己的核心沒有效果。 */
    private InteractionResult onAttackBlock(ServerPlayer player, BlockPos pos) {
        Duel duel = duelsByPlayer.get(player.getUUID());
        if (duel == null || !duel.arena().isCoreBlock(pos)) return InteractionResult.PASS;

        Side owner = duel.sideOfCore(pos);
        if (owner == null) return InteractionResult.PASS;

        if (owner.playerId().equals(player.getUUID())) {
            player.sendSystemMessage(Msg.warn("那是你自己的核心。"));
            return InteractionResult.FAIL;
        }
        if (!duel.state().canAttack()) {
            player.sendSystemMessage(Msg.warn("建造階段打不動核心，等攻擊階段。"));
            return InteractionResult.FAIL;
        }

        duel.damageCore(pos, player, duel.settings().coreHitDamage());
        return InteractionResult.SUCCESS;
    }

    // ---------- 查詢 ----------

    public boolean isInDuel(ServerPlayer player) {
        return duelsByPlayer.containsKey(player.getUUID());
    }

    public Duel duelOf(ServerPlayer player) {
        return duelsByPlayer.get(player.getUUID());
    }

    public List<Duel> activeDuels() {
        return List.copyOf(activeDuels);
    }
}
