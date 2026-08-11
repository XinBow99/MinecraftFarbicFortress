package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.core.DuelSettings;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.util.DuelItems;
import com.xinbow99.fortressduel.util.InventoryStash;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一場進行中的對戰。
 *
 * <p>開場不傳送玩家：競技場就地框在雙方站的位置之間，準備倒數結束才在各自腳邊圍出熊貓圈。
 * 之後建造與攻擊階段輪替，勝負條件只有一條：把對方的熊貓全部打死。
 *
 * <p>目標是**實體**而不是方塊，這是刻意的——熊貓可以被牽繩帶走、藏進地下室或假房間，
 * 所以「打哪裡」本身變成攻方要解的問題，蓋房子的 3D 結構也才有意義。代價是要自己處理
 * 一堆實體才有的問題：意外死亡、被推走、對戰結束要清乾淨。
 *
 * <p>所有狀態變更都只在伺服器主執行緒（tick 或指令）發生，所以這裡沒有任何同步處理。
 */
public final class Duel {

    /** 對戰結束的原因與贏家。{@code winner} 為 null 代表沒有贏家（雙方都離開之類）。 */
    public record Result(UUID winner, String reason) {
        public static Result coreDestroyed(UUID winner) {
            return new Result(winner, "對方的熊貓全滅");
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

    /** 被夾回區塊時要離邊界多遠（格）。太小的話下一 tick 又剛好壓在線上，會一直觸發。 */
    private static final double CONFINE_BUFFER = 1.0;
    /** 越界提示的最短間隔（tick）。 */
    private static final long BOUNDARY_WARN_INTERVAL = 40;
    /** 動作列上的短訊顯示多久（tick）。 */
    private static final long NOTICE_TICKS = 40;

    private final MinecraftServer server;
    private final Arena arena;
    private final DuelSettings settings;
    private final DuelServices services;
    private final Side north;
    private final Side south;
    /**
     * 靶子站的位置；null ＝ 這是正常的兩人對戰。
     *
     * <p>單人練習模式下南半場沒有真人，但競技場是就地框在「雙方位置」之間的、熊貓圈也是圍在
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
    /**
     * 正在對熊貓送窒息傷害。{@link #allowGuardianDamage} 靠它認出「這一發是我們自己打的」。
     *
     * <p>不能靠傷害型別辨識——那是一個沒有寫下來的約定：只要有人改了 {@link #tickSuffocation}
     * 用的型別，或原版哪天讓熊貓也會餓，判斷就會靜默失效。旗標是明確的因果關係，而且所有狀態
     * 變更都在伺服器主執行緒上發生（見類別註解），{@code hurtServer} 是同步的，不會有交錯。
     */
    private boolean applyingSuffocation;
    /**
     * 玩家 → 動作列上要蓋過常規 HUD 的短訊。
     *
     * <p>存在的理由：{@link #hud} **每一 tick** 都往動作列寫一次階段與餘額，所以子系統自己送
     * 動作列訊息的話，活不過 50 毫秒就被蓋掉——「沒有子彈了」「拉得不夠」「+$25 賞金」
     * 「中彈 −$100」在對戰中全部等於隱形。要顯示在動作列上的東西必須交給 HUD 自己排版。
     */
    private final Map<UUID, Notice> notices = new HashMap<>();

    /** 一則短訊與它的到期時間。 */
    private record Notice(Component text, long until) {
    }

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

        Side north = new Side(challenger, ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE);
        Side south = new Side(target, ChatFormatting.RED, BossEvent.BossBarColor.RED);

        Duel duel = new Duel(server, arena, settings, services, north, south, null);

        // 兩條血條雙方都要看得到——你必須知道自己還剩多少，也必須知道還要打幾下才贏
        for (ServerPlayer player : new ServerPlayer[]{challenger, target}) {
            duel.admit(player);
            player.sendSystemMessage(Msg.good("對戰開始！站好別亂跑——"
                    + settings.countdownSeconds() + " 秒後你的熊貓會在腳邊的柵欄圈裡生成，"
                    + "那就是你要守的東西。"));
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

        Side north = new Side(player, ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE);
        Side south = Side.dummy(DUMMY_NAME, ChatFormatting.RED, BossEvent.BossBarColor.RED);

        Duel duel = new Duel(server, arena, settings, services, north, south, dummyPos);
        duel.admit(player);
        player.sendSystemMessage(Msg.good("單人練習開始！對手是不會還手的「" + DUMMY_NAME
                + "」，站好別亂跑——" + settings.countdownSeconds()
                + " 秒後雙方的熊貓會生成，把它的熊貓全部打死就結束。想提前收場用 /duel forfeit。"));

        DuelEvents.START.invoker().onDuelStart(duel);
        return duel;
    }

    /** 進場手續：兩條血條都給他看、發開場物資與彈藥。 */
    private void admit(ServerPlayer player) {
        north.showTo(player);
        south.showTo(player);
        applyDuelGameMode(player);
        stashInventory(player);
        giveStartingItems(player);
        services.weapons().giveStartingAmmo(player);
    }

    /**
     * 開場把玩家切成設定裡的模式（預設 survival），結束再還原成他原本的。
     *
     * <p>**這是防呆，不是防弊**——對戰中自己 {@code /gamemode creative} 不會被改回來。
     * 刻意的：持續強制會讓開發期間沒辦法隨時切模式測試，而作弊本來就該用權限管，不是用遊戲規則管。
     *
     * <p>不要設成 adventure。它看起來更安全，但 adventure 挖不了方塊，而本作的前提是
     * 「建材自己挖、自己蓋」——那等於把玩法整個廢掉。
     */
    private void applyDuelGameMode(ServerPlayer player) {
        GameType mode = gameType(settings.gameMode());
        if (mode == null) {
            FortressDuel.LOGGER.warn("battle.gamemode '{}' is not a valid game mode, leaving {} as they are",
                    settings.gameMode(), player.getGameProfile().name());
            return;
        }
        sideOf(player.getUUID()).setReturnGameMode(player.gameMode());
        player.setGameMode(mode);
    }

    /** 設定檔寫的模式名（survival／creative／…）對到原版的 {@link GameType}；認不得回 null。 */
    private static GameType gameType(String name) {
        for (GameType type : GameType.values()) {
            if (type.getName().equalsIgnoreCase(name)) return type;
        }
        return null;
    }

    /**
     * 開場把玩家原本的背包整份寄放起來，打完再還他（見 {@link InventoryStash}）。
     *
     * <p>不清空的話，身上本來就有鑽石裝、一堆黑曜石、一把附魔弓的人跟剛上線的人打的不是同一場
     * 遊戲——而這個遊戲的前提是雙方靠同一份開場物資與同一個經濟系統長出差距。
     *
     * <p>可以用 {@code battle.clear_inventory: false} 關掉。開發時常常需要帶著測試用的東西
     * 直接開一場，每次都被收走很難做事。
     */
    private void stashInventory(ServerPlayer player) {
        if (!settings.clearInventory()) return;

        int stashed = InventoryStash.take(player);
        if (stashed > 0) {
            player.sendSystemMessage(Msg.info("你原本的 " + stashed
                    + " 疊物品先寄放著，對戰結束會原封不動還你。"));
        }
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
            // 標記成「對戰發的」，結束時才收得回來——玩家是帶著自己的背包就地進場的，
            // 不能靠清空背包收尾（見 DuelItems）
            player.getInventory().placeItemBackInInventory(DuelItems.issue(new ItemStack(item, count)));
        }
    }


    // ---------- 每 tick ----------

    public void tick() {
        if (state == DuelState.ENDED) return;
        ticksElapsed++;

        // 每秒一次就夠：封死是持續狀態，不是瞬間事件，而且掉血的單位本來就是「每秒」
        if (ticksElapsed % 20 == 0) {
            tickSuffocation();
            if (state == DuelState.ENDED) return;
        }

        ServerPlayer a = playerOf(north);

        // 單人練習：南半場是靶子，本來就沒有對應的線上玩家，不能套用離線判負
        if (solo) {
            if (a == null) {
                finish(Result.aborted());
                return;
            }
            tickPhase(new ServerPlayer[]{a});
            keepInside(a, north);
            enforceZones();
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
        enforceZones();

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
                spawnObjectives(players);
                enterBuild(players);
            }
            case COMBAT -> enterBuild(players);
            case BUILD -> enterCombat(players);
            default -> { /* ENDED：不再換階段 */ }
        }
    }

    /**
     * 準備階段結束：在雙方**當下站的位置**旁邊圍出熊貓圈並生成熊貓。
     *
     * <p>位置是這一刻才決定的，不是開場那一刻——所以準備階段的十秒是玩家的：想把圈擺在
     * 高地上、擺進山洞裡，就走過去站好。這也是為什麼開場不傳送人。
     *
     * @param players 場上的真人，依 north、south 的順序。單人練習模式只有一個，
     *                南半場的位置改用開場時替靶子釘下的 {@link #dummyPos}
     */
    private void spawnObjectives(ServerPlayer[] players) {
        BlockPos posSouth = solo ? dummyPos : players[1].blockPosition();
        arena.placePens(players[0].blockPosition(), posSouth, settings, services.buildings());

        north.setPen(arena.penA());
        south.setPen(arena.penB());
        north.setGuardians(spawnPandas(north, arena.penA()), settings.pandaHp());
        south.setGuardians(spawnPandas(south, arena.penB()), settings.pandaHp());

        for (ServerPlayer player : players) {
            player.sendSystemMessage(Msg.good("熊貓已生成！把對方的 " + settings.pandaCount()
                    + " 隻熊貓全部打死就獲勝。牠們可以用**牽繩**拉走藏起來——但別把牠們封死，"
                    + "空間太小會讓牠們持續掉血。"));
            beep(player, SoundEvents.PANDA_AMBIENT, 1f);
        }
    }

    /**
     * 生成一方的熊貓。
     *
     * <p>刻意**不關 AI**：關掉的話牽繩拉不動，「自己安排佈局」這件事就沒了。代價是牠們會亂晃，
     * 所以柵欄圈疊了兩格高（見 {@code Arena.placePen}）。
     */
    private List<UUID> spawnPandas(Side side, BlockPos pen) {
        ServerLevel level = arena.level();
        List<UUID> ids = new ArrayList<>();

        // 走 registry 而不是 EntityType.PANDA：跟 MobSpawner／NpcManager 同一個模式，
        // 換一種目標生物只要改 YAML，而且不會因為 mapping 改欄位名就編不過
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
                .getOptional(Identifier.parse(settings.pandaEntity())).orElse(null);
        if (type == null) {
            FortressDuel.LOGGER.error("Objective entity '{}' does not exist, this duel has no objective",
                    settings.pandaEntity());
            return ids;
        }

        for (int i = 0; i < settings.pandaCount(); i++) {
            Entity entity = type.spawn(level, arena.guardianSpot(pen, i), EntitySpawnReason.EVENT);
            if (!(entity instanceof LivingEntity panda)) {
                FortressDuel.LOGGER.warn("Failed to spawn objective #{} for {} at {}", i, side.playerName(), pen);
                if (entity != null) entity.discard();
                continue;
            }

            panda.setCustomName(Component.literal(side.playerName() + " 的熊貓")
                    .withStyle(ChatFormatting.GREEN));
            panda.setCustomNameVisible(true);
            // 沒有玩家在附近時原版會把牠清掉，那等於隨機判輸
            if (panda instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
            MobSpawner.setMaxHealth(panda, settings.pandaHp());
            panda.setHealth(panda.getMaxHealth());
            ids.add(panda.getUUID());
        }
        return ids;
    }

    /**
     * 這隻實體是不是某一方要守的熊貓；是的話回傳牠的主人。
     *
     * <p>{@code DuelManager} 用它把傷害事件接回來——熊貓的血量就是這一方的血量，
     * 但「誰打的、算不算數」只有這裡知道。
     */
    public Side sideOfGuardian(UUID entityId) {
        if (north.owns(entityId)) return north;
        if (south.owns(entityId)) return south;
        return null;
    }

    /**
     * 熊貓身上的傷害算不算數。
     *
     * <p>只有**對手的攻擊**與我們自己送的窒息傷害算。摔落、突發事件的怪、自己人的濺射誤傷
     * 一律免疫——因為一個你無法控制的意外而輸掉整場是很糟的體驗，而這些來源全都不是對手的操作。
     * 建造階段也一律免疫，那時本來就不能攻擊。
     */
    public boolean allowGuardianDamage(Side owner, DamageSource source) {
        if (applyingSuffocation) return true;  // 我們自己送的窒息傷害
        if (!state.canAttack()) return false;

        return source.getEntity() instanceof ServerPlayer attacker
                && opponentOf(owner).playerId().equals(attacker.getUUID());
    }

    /**
     * 熊貓掉血或死掉之後重算這一方的血量，順便判斷是不是全滅了。
     *
     * <p>扣了多少是**自己從血量差算出來的**，不是相信事件帶進來的數字。兩個理由：
     * <ul>
     *   <li>致命一擊走的是 {@code AFTER_DEATH}，那條事件根本沒有傷害量可拿——照著事件走的話，
     *       打死熊貓的那一發永遠不會發 {@code CORE_DAMAGED}，之後要做「打中目標給錢」或
     *       戰報統計就會固定少算最後一擊。</li>
     *   <li>血量差本來就是「真的扣掉多少」：剩 3 血時挨一發 70 傷害，記的是 3 而不是 70，
     *       超殺的部分不會被灌進統計裡。</li>
     * </ul>
     */
    public void onGuardianChanged(Side owner, ServerPlayer attacker) {
        float before = owner.hp();
        refreshSides();
        float applied = before - owner.hp();

        if (applied > 0) {
            ServerPlayer ownerPlayer = playerOf(owner);
            DuelEvents.CORE_DAMAGED.invoker().onCoreDamaged(this, ownerPlayer, attacker, applied);
        }

        if (owner.isDestroyed()) {
            finish(Result.coreDestroyed(opponentOf(owner).playerId()));
        }
    }

    /** 從世界裡的實體重算兩邊的血量與存活數。 */
    private void refreshSides() {
        ServerLevel level = arena.level();
        for (Side side : new Side[]{north, south}) {
            side.refresh(id -> {
                Entity entity = level.getEntity(id);
                return entity instanceof LivingEntity living && living.isAlive() ? living.getHealth() : 0;
            });
        }
    }

    /**
     * 每秒檢查一次：被封死的熊貓要掉血。
     *
     * <p>沒有這條規則的話最優解固定是「1×1 黑曜石棺材，封死不動」，佈局的博弈就不存在了。
     * 判準是牠周圍 3×3×3 裡有幾格站得進去——空曠地面約 18 格，棺材只有 1~2 格。
     */
    private void tickSuffocation() {
        ServerLevel level = arena.level();

        for (Side side : new Side[]{north, south}) {
            for (UUID id : side.guardians()) {
                if (!(level.getEntity(id) instanceof LivingEntity panda) || !panda.isAlive()) continue;
                if (freeSpaceAround(level, panda.blockPosition()) >= settings.suffocationMinSpace()) continue;

                // 不必自己叫 onGuardianChanged：hurtServer 會走 AFTER_DAMAGE，
                // DuelManager 已經把那條路接回來了。自己再叫一次只會讓 CORE_DAMAGED 發兩遍
                //
                // 旗標要在 finally 裡關掉：這一發可能打死熊貓，那條路會一路走到 finish()，
                // 中途任何一個環節丟例外都不能讓旗標卡在開著的狀態——那等於之後所有打在
                // 熊貓身上的傷害都被當成自家的窒息傷害放行
                applyingSuffocation = true;
                try {
                    panda.hurtServer(level, level.damageSources().starve(),
                            (float) settings.suffocationDamage());
                } finally {
                    applyingSuffocation = false;
                }
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                        panda.getX(), panda.getY() + 1.0, panda.getZ(), 1, 0.2, 0.2, 0.2, 0);
                if (state == DuelState.ENDED) return;
            }
        }
    }

    /** 這一格周圍 3×3×3（含自己）有幾格是站得進去的，也就是不擋碰撞的。 */
    private static int freeSpaceAround(ServerLevel level, BlockPos center) {
        int free = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * 對戰結束時把熊貓清掉。
     *
     * <p>{@link ArenaSnapshot} 只還原方塊。熊貓是實體，不明確移除的話會留在世界上——
     * 而且牠們是 {@code persistenceRequired}，連自然消失都不會。
     */
    private void removeGuardians() {
        ServerLevel level = arena.level();
        for (Side side : new Side[]{north, south}) {
            for (UUID id : side.guardians()) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
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
     * 在動作列上顯示一則短訊，蓋過常規 HUD 兩秒。
     *
     * <p>子系統要在動作列上說話一律走這裡，不要自己 {@code sendSystemMessage(..., true)}——
     * 那會在下一 tick 被 {@link #hud} 蓋掉。
     */
    public void notify(ServerPlayer player, Component text) {
        notices.put(player.getUUID(), new Notice(text, ticksElapsed + NOTICE_TICKS));
    }

    private Component noticeFor(ServerPlayer player) {
        Notice notice = notices.get(player.getUUID());
        if (notice == null) return null;
        if (ticksElapsed > notice.until()) {
            notices.remove(player.getUUID());
            return null;
        }
        return notice.text();
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
            case PREPARE -> Msg.plain("熊貓生成倒數 " + seconds + "s  站好別亂跑", ChatFormatting.YELLOW);
            case BUILD -> Msg.plain("建造 " + seconds + "s", ChatFormatting.GREEN);
            case COMBAT -> Msg.plain("攻擊 " + seconds + "s", ChatFormatting.RED);
            case ENDED -> Component.literal("");
        };

        line.append(Msg.plain("   $" + services.economy().balanceOf(player), ChatFormatting.GOLD));

        // 短訊優先：它是「剛剛發生了什麼」，比恆常顯示的彈藥數重要，而且只活兩秒
        Component notice = noticeFor(player);
        if (notice != null) {
            return line.append(Component.literal("   ")).append(notice);
        }

        // 拉弓時顯示蓄力條。這條反映的是**我們算的**力道而不是客戶端的動畫進度，
        // 曲線非線性時兩者不一樣
        String charge = services.weapons().chargeDisplay(player);
        if (charge != null) {
            line.append(Msg.plain("   " + charge, ChatFormatting.YELLOW));
            return line;
        }

        // 彈藥數不用畫：它現在是副手的實物，原版自己會在那一格畫數量
        return line;
    }

    /** 在玩家腳下放一個提示音。用世界的 playSound 而不是只送給他一個人——兩邊聽到的節奏會一致。 */
    private void beep(ServerPlayer player, SoundEvent sound, float pitch) {
        player.level().playSound(null, player.blockPosition(), sound, SoundSource.MASTER, 1f, pitch);
    }

    /**
     * 把場上所有活物夾回它該待的區塊。
     *
     * <p>場地沿著兩座熊貓圈的連線分成三塊——A 的半場、中場、B 的半場。誰該待在哪：
     * <ul>
     *   <li><b>玩家</b>：自己的半場。這是「只靠遠程決勝」的實作——衝進對方陣地拆牆、
     *       貼臉砍熊貓都不該是一個選項。</li>
     *   <li><b>熊貓</b>：主人的半場。擊退仍然推得動牠們（那是刻意保留的手感），
     *       但推不出自家邊界。</li>
     *   <li><b>其他活物</b>：中場。突發事件的怪生在中場，賞金是唯一的競爭性收入，
     *       讓牠們跑進任何一邊的陣地都會讓那一方白白多守一輪。</li>
     * </ul>
     *
     * <p>關掉 AI 的實體（軍火商那種站定點的 NPC）不動——牠本來就該待在自己那一側的店裡，
     * 而且被移動之後沒有 AI 可以走回去。
     */
    private void enforceZones() {
        if (!arena.zonesReady()) return;

        confinePlayer(playerOf(north), north, Arena.Zone.A);
        confinePlayer(playerOf(south), south, Arena.Zone.B);

        confineGuardians(north, Arena.Zone.A);
        confineGuardians(south, Arena.Zone.B);

        // 中場的怪每 5 tick 掃一次就好：牠們走得慢，而這是唯一需要遍歷實體的一段
        if (ticksElapsed % 5 == 0) {
            confineBystanders();
        }
    }

    private void confinePlayer(ServerPlayer player, Side side, Arena.Zone zone) {
        if (player == null || player.isDeadOrDying()) return;

        Vec3 corrected = arena.confine(player.position(), zone, CONFINE_BUFFER);
        if (corrected == null) return;

        player.teleportTo(arena.level(), corrected.x, corrected.y, corrected.z,
                Set.of(), player.getYRot(), player.getXRot(), true);
        if (side.shouldWarnBoundary(ticksElapsed, BOUNDARY_WARN_INTERVAL)) {
            player.sendSystemMessage(Msg.warn("那是對方的陣地，過不去——用武器打。"));
        }
    }

    private void confineGuardians(Side side, Arena.Zone zone) {
        ServerLevel level = arena.level();
        for (UUID id : side.guardians()) {
            if (level.getEntity(id) instanceof LivingEntity panda && panda.isAlive()) {
                confineCreature(panda, zone);
            }
        }
    }

    /** 場上除了玩家與熊貓以外的活物，一律關在中場。 */
    private void confineBystanders() {
        ServerLevel level = arena.level();
        Region region = arena.region();
        AABB box = new AABB(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1.0, region.maxY() + 1.0, region.maxZ() + 1.0);

        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity instanceof ServerPlayer) continue;
            if (sideOfGuardian(entity.getUUID()) != null) continue;
            if (entity instanceof Mob mob && mob.isNoAi()) continue;

            confineCreature(entity, Arena.Zone.NEUTRAL);
        }
    }

    /**
     * 夾一隻非玩家的生物。
     *
     * <p>夾完要把速度歸零：被擊退推出界的那一下帶著動量，只改位置的話下一 tick 又會衝出去，
     * 看起來像在邊界上彈跳。
     */
    private void confineCreature(LivingEntity entity, Arena.Zone zone) {
        Vec3 corrected = arena.confine(entity.position(), zone, CONFINE_BUFFER);
        if (corrected == null) return;

        entity.teleportTo(corrected.x, corrected.y, corrected.z);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.hurtMarked = true;
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
        Vec3 spawn = arena.spawnFor(side.pen());
        player.teleportTo(arena.level(), spawn.x, spawn.y, spawn.z,
                Set.of(), arena.spawnYawFor(side.pen()), 0f, true);
        player.sendSystemMessage(Msg.warn("你離開了競技場範圍，已被拉回。"));
    }

    // ---------- 結束 ----------

    public void finish(Result result) {
        if (state == DuelState.ENDED) return;
        state = DuelState.ENDED;
        this.result = result;

        DuelEvents.END.invoker().onDuelEnd(this, result);

        // 要在 arena.restore() 之前：還原只處理方塊，實體得自己收
        removeGuardians();
        reclaimIssuedItems();
        // 一定排在收回之後：先把對戰發的清掉，原本的東西才回得去原本的格子
        returnStashedInventories();

        announce(result);

        north.hideFromAll();
        south.hideFromAll();

        teleportOut(north);
        teleportOut(south);

        arena.restore();
    }

    /**
     * 把對戰發的東西收回來：開場物資、彈藥、商店買的建材，還有那把弓。
     *
     * <p>不收的話這些東西會被帶回主世界——彈藥、黑曜石、TNT 全都是免費的，開一場對戰就等於
     * 一次補給。錢本來就是虛擬且不跨場的（見 {@code Wallet}），物資沒有理由例外。
     *
     * <p>只收帶標記的，不是清空背包：玩家是帶著自己原本的背包就地進場的（開場不傳送人），
     * 清空等於沒收他的家當。
     *
     * <p>離線的人這裡碰不到，改在他下次上線時收（見 {@code DuelManager} 的 JOIN 處理）。
     */
    private void reclaimIssuedItems() {
        for (Side side : new Side[]{north, south}) {
            ServerPlayer player = playerOf(side);
            if (player == null) continue;

            int removed = DuelItems.stripFrom(player);
            if (removed > 0) {
                player.sendSystemMessage(Msg.info("對戰結束，收回了對戰期間發放與購買的 "
                        + removed + " 疊物資。"));
            }
        }
        clearDroppedIssuedItems();
    }

    /**
     * 把開場寄放的背包還回去。
     *
     * <p>離線的人這裡碰不到，但他的東西在檔案裡不會不見——下次上線就會還（見
     * {@code DuelManager} 的 JOIN 處理）。這也是 {@link InventoryStash} 要寫進檔案的原因。
     */
    private void returnStashedInventories() {
        for (Side side : new Side[]{north, south}) {
            ServerPlayer player = playerOf(side);
            if (player == null) continue;

            int returned = InventoryStash.returnTo(player);
            if (returned > 0) {
                player.sendSystemMessage(Msg.good("你原本的 " + returned + " 疊物品還你了。"));
            }
        }
    }

    /**
     * 掃掉散落在場上的對戰物資。
     *
     * <p>只收背包是不夠的：玩家可以在結束前把東西丟在地上，那些掉落物不屬於任何人的背包，
     * 地形還原也只處理方塊——不清的話它們會留在世界上，撿起來就等於繞過了回收。
     */
    private void clearDroppedIssuedItems() {
        Region region = arena.region();
        AABB box = new AABB(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1.0, region.maxY() + 1.0, region.maxZ() + 1.0);

        for (ItemEntity dropped : arena.level().getEntitiesOfClass(ItemEntity.class, box)) {
            if (DuelItems.isIssued(dropped.getItem())) {
                dropped.discard();
            }
        }
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

        // 模式跟座標一樣是「怎麼把人放回去」的一部分，所以收在同一個地方
        if (side.returnGameMode() != null) {
            player.setGameMode(side.returnGameMode());
        }

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
