package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * 一場進行中的對戰。
 *
 * <p>開場不傳送玩家：競技場就地框在雙方站的位置之間，準備倒數結束才在各自腳邊長出水晶。
 * 之後建造與攻擊階段輪替，勝負條件只有一條：把對方的水晶打到 0。
 *
 * <p>所有狀態變更都只在伺服器主執行緒（tick 或指令）發生，所以這裡沒有任何同步處理。
 */
public final class Duel {

    /** 對戰結束的原因與贏家。{@code winner} 為 null 代表沒有贏家（雙方都離開之類）。 */
    public record Result(UUID winner, String reason) {
        public static Result coreDestroyed(UUID winner) {
            return new Result(winner, "核心被摧毀");
        }

        public static Result forfeit(UUID winner) {
            return new Result(winner, "對手投降");
        }

        public static Result disconnected(UUID winner) {
            return new Result(winner, "對手離線");
        }

        public static Result aborted() {
            return new Result(null, "對戰中止");
        }
    }

    /** 單人練習模式裡那個靶子的名字，會出現在血條上。 */
    public static final String DUMMY_NAME = "訓練假人";

    private final MinecraftServer server;
    private final Arena arena;
    private final DuelSettings settings;
    private final DuelServices services;
    private final Side north;
    private final Side south;
    /**
     * 靶子站的位置；null ＝ 這是正常的兩人對戰。
     *
     * <p>單人練習模式下南半場沒有真人，但競技場是就地框在「雙方位置」之間的、水晶也是長在
     * 各自腳邊——所以得先替靶子釘一個座標下去，才有第二個點可以用。
     */
    private final BlockPos dummyPos;
    /** 南半場是不是靶子。單人練習模式下沒有第二名玩家，很多「對雙方做某件事」的路徑要跳過。 */
    private final boolean solo;

    private DuelState state = DuelState.PREPARE;
    /** 目前這個階段還剩幾 tick。倒數、建造、攻擊三個階段共用同一個計時器。 */
    private int phaseTicks;
    /** 打到第幾輪（一輪 ＝ 一次建造 + 一次攻擊）。 */
    private int round;
    private long ticksElapsed;
    private Result result;

    private Duel(MinecraftServer server, Arena arena, DuelSettings settings, DuelServices services,
                 Side north, Side south, BlockPos dummyPos) {
        this.server = server;
        this.arena = arena;
        this.settings = settings;
        this.services = services;
        this.north = north;
        this.south = south;
        this.dummyPos = dummyPos;
        this.solo = dummyPos != null;
        this.phaseTicks = settings.countdownSeconds() * 20;
    }

    /**
     * 就地框出場地、開始準備倒數。**不傳送任何人。**
     *
     * @param challenger 發起挑戰的人
     * @param target     接受挑戰的人
     */
    public static Duel start(MinecraftServer server, ServerLevel level,
                             DuelSettings settings, DuelServices services,
                             ServerPlayer challenger, ServerPlayer target) {
        // 不傳送任何人：競技場就地框在雙方目前站的位置之間
        Arena arena = Arena.build(level, challenger.blockPosition(), target.blockPosition(),
                settings, services.buildings());

        Side north = new Side(challenger, settings.coreHp(),
                ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE);
        Side south = new Side(target, settings.coreHp(),
                ChatFormatting.RED, BossEvent.BossBarColor.RED);

        Duel duel = new Duel(server, arena, settings, services, north, south, null);

        // 兩條血條雙方都要看得到——你必須知道自己還剩多少，也必須知道還要打幾下才贏
        for (ServerPlayer player : new ServerPlayer[]{challenger, target}) {
            duel.admit(player);
            player.sendSystemMessage(Msg.good("對戰開始！站好別亂跑——"
                    + settings.countdownSeconds() + " 秒後水晶會在你腳邊生成，那就是你要守的東西。"));
        }

        DuelEvents.START.invoker().onDuelStart(duel);
        return duel;
    }

    /**
     * 單人練習模式：一名玩家對上一座不會還手的靶子。
     *
     * <p>存在的理由是測試——武器、怪物、技能、商店這些子系統全都要求「玩家正在對戰中」才會生效
     * （見 {@code WeaponSystem.onUseItem}），所以沒有這個模式的話，改一個彈道參數都得開兩個
     * 客戶端連線才看得到效果。
     *
     * <p>靶子那一方完全是靜態的：核心站在南半場、血條照常顯示，但沒有對應的線上玩家，
     * 所以它不會移動、不會開火、不會拿收入。玩家照樣要熬過建造／攻擊的階段輪替，
     * 把靶子的核心打到 0 就結束。
     *
     * <p>跟兩人對戰一樣不傳送人：場地就地框在玩家與 {@code dummyPos} 之間。
     */
    public static Duel startSolo(MinecraftServer server, ServerLevel level, BlockPos dummyPos,
                                 DuelSettings settings, DuelServices services, ServerPlayer player) {
        Arena arena = Arena.build(level, player.blockPosition(), dummyPos,
                settings, services.buildings());

        Side north = new Side(player, settings.coreHp(),
                ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE);
        Side south = Side.dummy(DUMMY_NAME, settings.coreHp(),
                ChatFormatting.RED, BossEvent.BossBarColor.RED);

        Duel duel = new Duel(server, arena, settings, services, north, south, dummyPos);
        duel.admit(player);
        player.sendSystemMessage(Msg.good("單人練習開始！對手是不會還手的「" + DUMMY_NAME
                + "」，站好別亂跑——" + settings.countdownSeconds()
                + " 秒後雙方的水晶會生成，打掉它的核心就結束。想提前收場用 /duel forfeit。"));

        DuelEvents.START.invoker().onDuelStart(duel);
        return duel;
    }

    /** 進場手續：兩條血條都給他看、發開場物資與彈藥。 */
    private void admit(ServerPlayer player) {
        north.showTo(player);
        south.showTo(player);
        giveStartingItems(player);
        services.weapons().giveStartingAmmo(player);
    }

    /**
     * 發開場物資。設定寫成 {@code "minecraft:dirt 20"}，數量省略就當 1。
     *
     * <p>建材要自己挖是規則的一部分，但完全空手開場的話第一個建造階段只能站著挖土，
     * 所以先給一份基本量讓人有得蓋。
     */
    private void giveStartingItems(ServerPlayer player) {
        for (String entry : settings.startingItems()) {
            String[] parts = entry.trim().split("\\s+");
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0])).orElse(null);
            if (item == null) {
                FortressDuel.LOGGER.warn("Item '{}' in starting_items does not exist, skipping it", parts[0]);
                continue;
            }

            int count = 1;
            if (parts.length > 1) {
                try {
                    count = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    FortressDuel.LOGGER.warn("Invalid amount in starting_items entry '{}', treating it as 1", entry);
                }
            }
            player.getInventory().placeItemBackInInventory(new ItemStack(item, count));
        }
    }


    // ---------- 每 tick ----------

    public void tick() {
        if (state == DuelState.ENDED) return;
        ticksElapsed++;

        ServerPlayer a = playerOf(north);

        // 單人練習：南半場是靶子，本來就沒有對應的線上玩家，不能套用離線判負
        if (solo) {
            if (a == null) {
                finish(Result.aborted());
                return;
            }
            tickPhase(new ServerPlayer[]{a});
            keepInside(a, north);
            DuelEvents.TICK.invoker().onDuelTick(this);
            return;
        }

        ServerPlayer b = playerOf(south);

        // 有人離線就直接判給還在的那一方；兩個都不在就中止
        if (a == null || b == null) {
            if (a == null && b == null) {
                finish(Result.aborted());
            } else {
                finish(Result.disconnected(a == null ? south.playerId() : north.playerId()));
            }
            return;
        }

        tickPhase(new ServerPlayer[]{a, b});

        keepInside(a, north);
        keepInside(b, south);

        DuelEvents.TICK.invoker().onDuelTick(this);
    }

    /**
     * 階段計時。倒數結束進建造，建造結束進攻擊，攻擊結束再回到建造——一直輪替到分出勝負。
     *
     * <p>剩餘時間每一 tick 都寫進動作列，因為「還剩幾秒可以蓋」是玩家每一秒都要知道的事，
     * 塞在聊天欄會洗版。
     */
    private void tickPhase(ServerPlayer[] players) {
        phaseTicks--;

        if (phaseTicks > 0) {
            for (ServerPlayer player : players) {
                player.sendSystemMessage(hud(player), true);
            }
            // 最後五秒每秒響一聲，讓人來得及放下手上的方塊
            if (phaseTicks <= 100 && phaseTicks % 20 == 0) {
                for (ServerPlayer player : players) {
                    beep(player, SoundEvents.NOTE_BLOCK_HAT.value(), 1f);
                }
            }
            return;
        }

        switch (state) {
            case PREPARE -> {
                spawnCores(players);
                enterBuild(players);
            }
            case COMBAT -> enterBuild(players);
            case BUILD -> enterCombat(players);
            default -> { /* ENDED：不再換階段 */ }
        }
    }

    /**
     * 準備階段結束：在雙方**當下站的位置**旁邊長出水晶。
     *
     * <p>位置是這一刻才決定的，不是開場那一刻——所以準備階段的十秒是玩家的：想把水晶擺在
     * 高地上、擺進山洞裡，就走過去站好。這也是為什麼開場不傳送人。
     *
     * @param players 場上的真人，依 north、south 的順序。單人練習模式只有一個，
     *                南半場的位置改用開場時替靶子釘下的 {@link #dummyPos}
     */
    private void spawnCores(ServerPlayer[] players) {
        BlockPos posSouth = solo ? dummyPos : players[1].blockPosition();
        arena.placeCores(players[0].blockPosition(), posSouth, settings, services.buildings());
        north.setCore(arena.coreA());
        south.setCore(arena.coreB());

        for (ServerPlayer player : players) {
            player.sendSystemMessage(Msg.good("水晶已生成！打掉對方的水晶就獲勝——順帶一提，"
                    + "軍火商也會被打死，他死了你就買不到東西。"));
            beep(player, SoundEvents.BEACON_ACTIVATE, 1f);
        }
    }

    private void enterBuild(ServerPlayer[] players) {
        state = DuelState.BUILD;
        round++;
        phaseTicks = settings.buildSeconds() * 20;
        for (ServerPlayer player : players) {
            player.sendSystemMessage(Msg.good("第 " + round + " 輪 — 建造階段開始（"
                    + settings.buildSeconds() + " 秒）：可以蓋，不能攻擊。"));
            // 建造階段才發收入：這時你才有機會把錢花掉（蓋牆、去商店補彈藥）。
            // 第一輪不發——開局資金是 starting_money，第一輪就加一份收入的話，
            // 那個設定值講的就不是玩家實際開局拿到的錢了
            if (round > 1) {
                services.economy().payRoundIncome(player);
            }
            beep(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f);
        }
    }

    private void enterCombat(ServerPlayer[] players) {
        state = DuelState.COMBAT;
        phaseTicks = settings.combatSeconds() * 20;
        for (ServerPlayer player : players) {
            player.sendSystemMessage(Msg.warn("攻擊階段開始（" + settings.combatSeconds()
                    + " 秒）：不能再擺方塊，開打！"));
            beep(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f);
        }
    }

    /**
     * 動作列那一行：階段與剩餘秒數 ＋ 餘額 ＋ 手上武器的彈藥。
     *
     * <p>三樣資訊擠在同一行，因為 boss 血條已經被兩座核心佔滿了，而這三個都是每一秒都要看的東西。
     * 彈藥寫成 FPS 那種 {@code 100/120}，手上沒拿武器就不顯示那一段。
     */
    private Component hud(ServerPlayer player) {
        int seconds = (phaseTicks + 19) / 20;
        MutableComponent line = switch (state) {
            case PREPARE -> Msg.plain("水晶生成倒數 " + seconds + "s  站好別亂跑", ChatFormatting.YELLOW);
            case BUILD -> Msg.plain("建造 " + seconds + "s", ChatFormatting.GREEN);
            case COMBAT -> Msg.plain("攻擊 " + seconds + "s", ChatFormatting.RED);
            case ENDED -> Component.literal("");
        };

        line.append(Msg.plain("   $" + services.economy().balanceOf(player), ChatFormatting.GOLD));

        String ammo = services.weapons().ammoDisplay(player);
        if (ammo != null) {
            line.append(Msg.plain("   " + ammo, ChatFormatting.AQUA));
        }
        return line;
    }

    /** 在玩家腳下放一個提示音。用世界的 playSound 而不是只送給他一個人——兩邊聽到的節奏會一致。 */
    private void beep(ServerPlayer player, SoundEvent sound, float pitch) {
        player.level().playSound(null, player.blockPosition(), sound, SoundSource.MASTER, 1f, pitch);
    }

    /** 走出框線就拉回自己的出生點。只看水平方向——跳起來、挖到腳下都不算離場。 */
    private void keepInside(ServerPlayer player, Side side) {
        // 死亡畫面期間不要動他：那時原版正要把他移到重生點，兩邊搶著傳送會把人丟到奇怪的位置。
        // 等他按下重生、變回活著的狀態，下一 tick 自然會被拉回場內
        if (player.isDeadOrDying()) return;

        if (player.level() == arena.level()
                && arena.region().containsHorizontally(player.getX(), player.getZ())) {
            return;
        }
        Vec3 spawn = arena.spawnFor(side.core());
        player.teleportTo(arena.level(), spawn.x, spawn.y, spawn.z,
                Set.of(), arena.spawnYawFor(side.core()), 0f, true);
        player.sendSystemMessage(Msg.warn("你離開了競技場範圍，已被拉回。"));
    }

    // ---------- 核心 ----------

    /**
     * 對某一座核心造成傷害。
     *
     * @param core     被打的那一座烽火台
     * @param attacker 出手的人，可能是 null（怪物、突發事件）
     */
    public void damageCore(BlockPos core, ServerPlayer attacker, float amount) {
        if (!state.canAttack()) return;

        Side side = sideOfCore(core);
        if (side == null) return;

        float applied = side.damage(amount);
        if (applied <= 0) return;

        ServerPlayer owner = playerOf(side);
        DuelEvents.CORE_DAMAGED.invoker().onCoreDamaged(this, owner, attacker, applied);

        arena.level().playSound(null, core, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.6f, 1.4f);

        if (side.isDestroyed()) {
            finish(Result.coreDestroyed(opponentOf(side).playerId()));
        }
    }

    // ---------- 結束 ----------

    public void finish(Result result) {
        if (state == DuelState.ENDED) return;
        state = DuelState.ENDED;
        this.result = result;

        DuelEvents.END.invoker().onDuelEnd(this, result);

        announce(result);

        north.hideFromAll();
        south.hideFromAll();

        teleportOut(north);
        teleportOut(south);

        arena.restore();
    }

    private void announce(Result result) {
        for (Side side : new Side[]{north, south}) {
            ServerPlayer player = playerOf(side);
            if (player == null) continue;

            if (result.winner() == null) {
                player.sendSystemMessage(Msg.info("對戰結束：" + result.reason()));
            } else if (result.winner().equals(side.playerId())) {
                player.sendSystemMessage(Msg.good("你贏了！（" + result.reason() + "）"));
            } else {
                player.sendSystemMessage(Msg.warn("你輸了。（" + result.reason() + "）"));
            }
        }
    }

    private void teleportOut(Side side) {
        ServerPlayer player = playerOf(side);
        if (player == null) return;

        ServerLevel level = server.getLevel(side.returnLevel());
        if (level == null) {
            // 來源維度被移除了（資料包改動之類）。與其把人丟在競技場裡，不如送去主世界出生點
            level = server.overworld();
            player.sendSystemMessage(Msg.warn("你原本所在的維度已不存在，改送你到主世界出生點。"));
            BlockPos spawn = level.getRespawnData().pos();
            player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    Set.of(), 0f, 0f, true);
            return;
        }

        Vec3 pos = side.returnPos();
        player.teleportTo(level, pos.x, pos.y, pos.z,
                Set.of(), side.returnYaw(), side.returnPitch(), true);
    }

    // ---------- 查詢 ----------

    public DuelState state() {
        return state;
    }

    public Result result() {
        return result;
    }

    public Arena arena() {
        return arena;
    }

    public DuelSettings settings() {
        return settings;
    }

    public long ticksElapsed() {
        return ticksElapsed;
    }

    public int round() {
        return round;
    }

    /** 是不是單人練習模式（南半場是靶子，不是真人）。 */
    public boolean isSolo() {
        return solo;
    }

    public boolean involves(UUID playerId) {
        return north.playerId().equals(playerId) || south.playerId().equals(playerId);
    }

    public Side sideOf(UUID playerId) {
        if (north.playerId().equals(playerId)) return north;
        if (south.playerId().equals(playerId)) return south;
        return null;
    }

    public Side sideOfCore(BlockPos core) {
        if (north.core().equals(core)) return north;
        if (south.core().equals(core)) return south;
        return null;
    }

    public Side opponentOf(Side side) {
        return side == north ? south : north;
    }

    public Side north() {
        return north;
    }

    public Side south() {
        return south;
    }

    private ServerPlayer playerOf(Side side) {
        return server.getPlayerList().getPlayer(side.playerId());
    }
}
