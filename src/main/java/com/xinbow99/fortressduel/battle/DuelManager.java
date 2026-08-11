package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                player instanceof ServerPlayer sp
                        ? onUseBlock(sp, hand, hit.getBlockPos().relative(hit.getDirection()))
                        : InteractionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowDamage);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, dealt, taken, blocked) -> onGuardianDamaged(entity, source));
        // 致命一擊不會走 AFTER_DAMAGE 的存活路徑，全滅判斷得靠這個。
        // 兩條路徑都不傳傷害量——扣了多少由 Duel 從血量差自己算
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onGuardianDamaged);
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

        // 就地開場：場地框在雙方目前站的位置之間，所以只要確認這裡沒有跟別場重疊
        if (overlapsExistingArena(level, challenger.blockPosition(), target.blockPosition())) {
            return "這裡跟另一場正在進行的對戰重疊了，走遠一點再試。";
        }

        Duel duel = Duel.start(level.getServer(), level, settings, services, challenger, target);
        activeDuels.add(duel);
        duelsByPlayer.put(challenger.getUUID(), duel);
        duelsByPlayer.put(target.getUUID(), duel);

        // 開場了就把雙方其他還掛著的挑戰書清掉，免得打完被一堆逾時訊息洗版
        challenges.remove(target.getUUID());
        challenges.remove(challenger.getUUID());
        return null;
    }

    /**
     * 開一場單人練習：自己一個人對上不會還手的靶子。
     *
     * <p>不需要挑戰書——這是測試工具，不是玩法的一部分。開場一樣不傳送人：場地就地框在
     * 玩家與靶子之間，靶子則插在他正面對著的方向，這樣「往前打」就是打向對手。
     *
     * @return 給發起者看的結果訊息；null ＝ 已開場
     */
    public String solo(ServerPlayer player) {
        if (isInDuel(player)) return "你正在對戰中。";

        ServerLevel level = player.level();
        DuelSettings settings = config.settings();
        BlockPos dummyPos = player.blockPosition()
                .relative(player.getDirection(), settings.arenaSize() / 2);

        if (overlapsExistingArena(level, player.blockPosition(), dummyPos)) {
            return "這裡跟另一場正在進行的對戰重疊了，走遠一點再試。";
        }

        Duel duel = Duel.startSolo(level.getServer(), level, dummyPos, settings, services, player);
        activeDuels.add(duel);
        duelsByPlayer.put(player.getUUID(), duel);

        challenges.remove(player.getUUID());
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

        // 單人練習沒有對手可以判給，直接中止——不然會跳出「你輸給了訓練假人」
        if (duel.isSolo()) {
            duel.finish(Duel.Result.aborted());
            return null;
        }

        Side side = duel.sideOf(player.getUUID());
        duel.finish(Duel.Result.forfeit(duel.opponentOf(side).playerId()));
        return null;
    }

    // ---------- 場地 ----------

    /**
     * 這兩個人站的位置，會不會跟某一場正在進行的對戰重疊。
     *
     * <p>就地開場之後沒有「選址」這回事了——場地是玩家自己站出來的，系統只需要否決
     * 「站在別人的競技場裡開新的一場」。
     */
    private boolean overlapsExistingArena(ServerLevel level, BlockPos a, BlockPos b) {
        int keepOut = config.settings().arenaMinSeparation();
        for (Duel duel : activeDuels) {
            if (duel.arena().level() != level) continue;

            Region other = duel.arena().region().expand(keepOut);
            if (other.contains(a) || other.contains(b)) {
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
        if (region.isHorizontalEdge(pos.getX(), pos.getZ())) {
            player.sendSystemMessage(Msg.warn("那是競技場的框線，拆不掉。"));
            return false;
        }
        return true;
    }

    /**
     * 建造階段以外、或競技場範圍外，不能擺放方塊。
     *
     * <p>只擋「拿著方塊右鍵」——右鍵開箱子、按拉桿、用武器都還是通的，所以攻擊階段照樣能操作場地，
     * 只是不能再長出新的牆。
     *
     * <p>範圍檢查不能省，而且要跟 {@link #allowBreak} 對稱：{@link ArenaSnapshot} 只記錄
     * 競技場範圍內的格子，蓋在外面的方塊打完不會被還原——那會在世界上留下永久痕跡。
     * 玩家站在邊緣往外搆得到五格左右，所以這不是理論問題。
     *
     * @param target 方塊會被放到哪一格（命中面往外一格），不是被點到的那一格
     */
    private InteractionResult onUseBlock(ServerPlayer player, InteractionHand hand, BlockPos target) {
        Duel duel = duelsByPlayer.get(player.getUUID());
        if (duel == null) return InteractionResult.PASS;
        if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) return InteractionResult.PASS;

        if (!duel.state().canPlaceBlocks()) {
            player.sendSystemMessage(Msg.warn("攻擊階段不能擺放方塊，等下一輪建造階段。"));
            return InteractionResult.FAIL;
        }
        if (!duel.arena().region().contains(target)) {
            player.sendSystemMessage(Msg.warn("那已經在競技場外面了，蓋不了。"));
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /**
     * 建造階段雙方不能互相傷害。
     *
     * <p>只擋「對手打你」這一種來源——摔傷、溺水、怪物照樣算，否則建造階段會變成無敵時間。
     */
    private boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        Boolean guardian = filterGuardianDamage(entity, source);
        if (guardian != null) return guardian;

        if (!(entity instanceof ServerPlayer victim)) return true;

        Duel duel = duelsByPlayer.get(victim.getUUID());
        if (duel == null || duel.state().canAttack()) return true;

        return !(source.getEntity() instanceof ServerPlayer attacker) || !duel.involves(attacker.getUUID());
    }

    /**
     * 熊貓身上的傷害要不要放行。
     *
     * <p>目標從方塊改成實體之後，「打得到／打不到」不再是挖掘與左鍵的問題，而是傷害來源的問題：
     * 只有對手的攻擊算數，摔落、怪物、自己人的濺射一律免疫（見 {@code Duel.allowGuardianDamage}）。
     *
     * @return null ＝ 這不是任何一場對戰的熊貓，交給其他規則處理
     */
    private Boolean filterGuardianDamage(LivingEntity entity, DamageSource source) {
        for (Duel duel : activeDuels) {
            Side owner = duel.sideOfGuardian(entity.getUUID());
            if (owner != null) {
                return duel.allowGuardianDamage(owner, source);
            }
        }
        return null;
    }

    /** 熊貓掉血／死掉之後，把它接回對戰的血條與勝負判斷。 */
    private void onGuardianDamaged(LivingEntity entity, DamageSource source) {
        for (Duel duel : activeDuels) {
            Side owner = duel.sideOfGuardian(entity.getUUID());
            if (owner == null) continue;

            ServerPlayer attacker = source.getEntity() instanceof ServerPlayer p ? p : null;
            duel.onGuardianChanged(owner, attacker);
            return;
        }
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
