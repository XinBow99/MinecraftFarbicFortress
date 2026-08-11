package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 武器系統：開火、彈道、命中結算。
 *
 * <p>三個設計決定：
 * <ul>
 *   <li><b>彈丸不是實體</b>——自己積分、自己射線檢測（見 {@link Projectile}）。</li>
 *   <li><b>方塊有血量</b>——原版只有「硬度」（挖多久），沒有「還能挨幾發」。這裡把硬度換算成血量
 *       （{@code hardness × block_hp_per_hardness}）並累積傷害，所以厚牆真的要打好幾發，
 *       跟網頁版的建材血量是同一個手感。累積值記在這裡，一場對戰結束就清掉。</li>
 *   <li><b>只在競技場內生效</b>——彈丸飛出框線就消失，不會打壞外面的世界。</li>
 * </ul>
 */
public final class WeaponSystem {

    /**
     * 滿弓要幾 tick。**這是客戶端寫死的，改了只會讓動畫跟數值對不起來**——拉弓動畫由客戶端
     * 自己數 tick 播放，伺服器改不到，而客戶端是純原版。
     */
    private static final int FULL_DRAW_TICKS = 20;
    /** 動作列上那條蓄力條有幾格。 */
    private static final int CHARGE_BAR_SEGMENTS = 5;
    /** 空弓的初速下限（滿速的幾成）。給 0 的話空弓的彈丸會原地落下，看起來像卡住。 */
    private static final double CHARGE_SPEED_FLOOR = 0.35;
    /** 空弓的傷害下限（滿傷的幾成）。 */
    private static final double CHARGE_DAMAGE_FLOOR = 0.3;

    private final ConfigManager config;
    private final DuelManager duels;

    private final List<Projectile> projectiles = new ArrayList<>();
    /** 玩家 → 各武器的剩餘冷卻（tick）。 */
    private final Map<UUID, Map<String, Integer>> cooldowns = new HashMap<>();
    /**
     * 玩家 → 各武器目前累積的後座力（度）。
     *
     * <p>逐武器而不是逐玩家：切到另一把槍不該繼承前一把的後座力，而放下一把槍去打別的、
     * 再切回來時它應該已經回穩了——這由 {@link #tickRecoil} 的固定衰減自然達成。
     */
    private final Map<UUID, Map<String, Double>> recoil = new HashMap<>();
    /** 每一場對戰裡、每一格已經累積的傷害。 */
    private final Map<Duel, Map<BlockPos, Float>> blockDamage = new HashMap<>();

    public WeaponSystem(ConfigManager config, DuelManager duels) {
        this.config = config;
        this.duels = duels;
    }

    public void register() {
        // Mixin 織進原版類別，沒有建構子可以注入，只能走這道靜態橋
        BowHooks.install(this);
        UseItemCallback.EVENT.register((player, level, hand) ->
                player instanceof ServerPlayer sp ? onUseItem(sp, hand) : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> forgetBlock(pos));
        DuelEvents.END.register((duel, result) -> cleanUp(duel));
    }

    /**
     * 對戰收尾。
     *
     * <p>三件事都不能省：
     * <ul>
     *   <li><b>清掉還在飛的彈丸</b>——{@link Duel#finish} 是先發 END 事件、再還原地形。這時候空中
     *       可能還有壽命長達 15 秒的無人機，不清掉的話它會在**已經還原好的**地形上炸出一個洞，
     *       而快照已經用掉了，那個洞永遠不會被補回來。</li>
     *   <li><b>清掉挖掘裂痕</b>——裂痕是送給客戶端的獨立狀態，不會因為方塊被還原就消失，
     *       要明確送一次 −1 才會不見。</li>
     *   <li>清掉累積傷害與後座力。彈藥不用清——它現在是玩家背包裡的實物。</li>
     * </ul>
     */
    private void cleanUp(Duel duel) {
        // 標記成死的再移除：結束這一刻很可能就在 tickProjectiles 的迴圈裡（是這一發打爆核心的），
        // 那邊握著清單的複本，只有 dead 這個旗標能讓它知道剩下的彈丸已經作廢
        projectiles.removeIf(projectile -> {
            if (projectile.duel != duel) return false;
            projectile.dead = true;
            return true;
        });

        Map<BlockPos, Float> damaged = blockDamage.remove(duel);
        if (damaged != null) {
            ServerLevel level = duel.arena().level();
            for (BlockPos pos : damaged.keySet()) {
                level.destroyBlockProgress(progressId(pos), pos, -1);
            }
        }

        // 後座力不跨場，否則上一場最後那串連射會讓下一場的第一發歪掉
        recoil.remove(duel.north().playerId());
        recoil.remove(duel.south().playerId());
    }

    /**
     * 玩家自己把方塊挖掉時，忘掉那一格的累積傷害。
     *
     * <p>不忘的話，補一塊新方塊上去會直接繼承舊的傷害——一面剛補好的牆一發就碎。
     */
    private void forgetBlock(BlockPos pos) {
        for (Map<BlockPos, Float> damaged : blockDamage.values()) {
            damaged.remove(pos);
        }
    }

    // ---------- 開火 ----------

    /**
     * 擋掉副手彈藥的原版右鍵行為。
     *
     * <p>幾種彈藥本身有原版行為：終界之眼會飛走、煙火會射出去、TNT 會被放下。主手拿弓時原版
     * 是「主手用掉了就不再試副手」，所以正常情況不會觸發；但主手**不是**弓的時候（切去拿建材、
     * 或弓掉了）右鍵仍然會走到副手，一個手滑就把彈藥丟掉了。對戰中一律擋下來。
     */
    private InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.OFF_HAND) return InteractionResult.PASS;

        Duel duel = duels.duelOf(player);
        // 沒在對戰就完全不插手：彈藥綁的是鐵錠、燧石、TNT、煙火這類原版常見物品，
        // 攔下來的話等於把整個伺服器的普通物品弄壞
        if (duel == null) return InteractionResult.PASS;
        if (byAmmoStack(player.getOffhandItem()) == null) return InteractionResult.PASS;

        return InteractionResult.FAIL;
    }

    /**
     * 這個人現在「上膛」的是哪把武器：主手是弓，副手是某種彈藥，而且他在對戰的攻擊階段。
     *
     * <p>這是全系統唯一一處決定「射出去的是什麼」的地方。副手優先正是原版弓找箭的順序，
     * 所以玩家不用學新規則。
     *
     * @return 沒在對戰、沒拿弓、副手不是彈藥時回傳 null
     */
    public WeaponDef armedWeaponOf(ServerPlayer player) {
        if (!player.getMainHandItem().is(Items.BOW)) return null;

        Duel duel = duels.duelOf(player);
        if (duel == null) return null;

        return byAmmoStack(player.getOffhandItem());
    }

    /** 這個人在不在對戰中。{@code InventoryDropMixin} 靠它決定死亡要不要掉落背包。 */
    public boolean isInDuel(ServerPlayer player) {
        return duels.duelOf(player) != null;
    }

    /** 這疊物品是哪一種彈藥；不是彈藥回傳 null。 */
    public WeaponDef byAmmoStack(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return config.weapons().byItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * 開場配給：把每種武器的 {@code starting_ammo} 當實物發下去。
     *
     * <p>弓不在這裡發——它跟泥土、牽繩一樣是 {@code battle.starting_items} 的一項，
     * 開場物資該由設定檔決定，程式不另外偷塞。
     */
    public void giveStartingAmmo(ServerPlayer player) {
        for (WeaponDef weapon : config.weapons().all()) {
            if (weapon.startingAmmo() > 0) {
                player.getInventory().placeItemBackInInventory(
                        WeaponItems.createAmmo(weapon, weapon.startingAmmo()));
            }
        }
    }

    /** 這名玩家背包裡有幾發這種彈藥。商店要靠它顯示「目前 N 發」。 */
    public int ammoCount(ServerPlayer player, WeaponDef weapon) {
        Item item = BuiltInRegistries.ITEM.getOptional(weapon.item()).orElse(null);
        if (item == null) return 0;

        int total = 0;
        for (ItemStack stack : player.getInventory()) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** 依 id 查武器。商店要靠它把商品接到武器定義上。 */
    public WeaponDef byId(String id) {
        return config.weapons().byId(id);
    }

    /** 全部武器，依設定檔的順序。商店的說明文字要靠它算「哪幾把打得動」。 */
    public Collection<WeaponDef> allWeapons() {
        return config.weapons().all();
    }

    /**
     * 一格硬度 {@code hardness} 的方塊有多少血量（不含穿甲折減）。
     *
     * <p>商店要用它把「硬度 50」翻譯成玩家真正在乎的「血量 500」——原版硬度是「挖多久」的單位，
     * 在這個 mod 裡沒有直接意義。
     */
    public float blockHpOf(float hardness) {
        return blockHp(hardness, 0);
    }

    /**
     * 這把武器要幾發才打得破一格硬度 {@code hardness} 的方塊。
     *
     * @return 打不破（傷害 0 或不破壞方塊）時回傳 -1
     */
    public int shotsToBreak(WeaponDef weapon, float hardness) {
        if (!weapon.breaksBlocks() || weapon.damageVsBlock() <= 0) return -1;
        float hp = blockHp(hardness, weapon.pierce());
        return (int) Math.ceil(hp / weapon.damageVsBlock());
    }

    /**
     * 放開弓 → 依蓄力程度開一發。
     *
     * <p>由 {@code BowItemMixin} 經 {@link BowHooks} 呼叫。這裡是唯一決定「拉多久 ＝ 多少力道」
     * 的地方，原版的 {@code getPowerForTime} 完全不參與——曲線是逐武器的資料（見 {@link ChargeCurve}）。
     *
     * @param usedTicks 拉了幾 tick
     * @return true ＝ 這一發由我們處理掉了
     */
    public boolean releaseCharged(ServerPlayer player, int usedTicks) {
        WeaponDef weapon = armedWeaponOf(player);
        if (weapon == null) return false;

        Duel duel = duels.duelOf(player);
        if (duel == null) return true;   // 沒在對戰：吃掉這一發，但什麼都不做
        if (!duel.state().canAttack()) {
            player.sendSystemMessage(Msg.warn("建造階段不能開火。"));
            return true;
        }

        // 滿弓固定 20 tick，對齊客戶端的拉弓動畫（見 ChargeCurve 的類別註解）
        double power = weapon.chargeCurve().power(usedTicks / (double) FULL_DRAW_TICKS);
        if (power < weapon.chargeMinDraw()) {
            // 放空弓不耗彈也不進冷卻——那只是手滑，不該被罰
            duels.notify(player, Msg.plain("拉得不夠，這一發沒射出去", ChatFormatting.GRAY));
            return true;
        }

        Map<String, Integer> playerCooldowns = cooldowns.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        if (playerCooldowns.containsKey(weapon.id())) return true;

        if (!consumeAmmo(player, weapon)) {
            duels.notify(player, Msg.plain(weapon.displayName() + " 用完了！去軍火商補貨", ChatFormatting.RED));
            player.level().playSound(null, player.blockPosition(),
                    SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.7f, 2.0f);
            // 空槍也要進冷卻，不然連點會每一下洗一次訊息
            playerCooldowns.put(weapon.id(), weapon.cooldownTicks());
            return true;
        }
        playerCooldowns.put(weapon.id(), weapon.cooldownTicks());

        fire(player, duel, weapon, power);
        return true;
    }

    /**
     * 扣掉這一發要用的彈藥。
     *
     * <p>只從**副手那一疊**扣，不會去背包裡湊：副手就是「上膛的彈匣」，打空了要自己補上去。
     * 散彈這種 {@code ammo_per_shot > 1} 的武器，副手剩的不夠就是打不出來。
     *
     * @return false ＝ 副手的量不夠
     */
    private boolean consumeAmmo(ServerPlayer player, WeaponDef weapon) {
        ItemStack ammo = player.getOffhandItem();
        if (ammo.getCount() < weapon.ammoPerShot()) return false;

        ammo.shrink(weapon.ammoPerShot());
        return true;
    }

    /**
     * 目前拉弓拉到哪裡，寫成 {@code ▮▮▮▯▯ 62%}；沒在拉就回 null。
     *
     * <p>反映的是**我們算的**力道，不是客戶端的動畫進度——曲線非線性時兩者不一樣，
     * 而玩家該看到的是前者。
     */
    public String chargeDisplay(ServerPlayer player) {
        if (!player.isUsingItem()) return null;

        WeaponDef weapon = armedWeaponOf(player);
        if (weapon == null) return null;

        double power = weapon.chargeCurve().power(player.getTicksUsingItem() / (double) FULL_DRAW_TICKS);
        int filled = (int) Math.round(power * CHARGE_BAR_SEGMENTS);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < CHARGE_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? '▮' : '▯');
        }
        return bar + " " + Math.round(power * 100) + "%";
    }

    /**
     * 開一發。
     *
     * @param power 蓄力程度 0~1。即發武器永遠傳 1.0——它們沒有蓄力這條軸，
     *              所以走的是完全相同的路徑、只是力道恆滿
     */
    private void fire(ServerPlayer player, Duel duel, WeaponDef weapon, double power) {
        ServerLevel level = player.level();
        Vec3 origin = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        // 這一發用的是「開火前」的後座力：第一發永遠是準的，代價從第二發才開始付
        double spread = weapon.spreadDegrees() + recoilOf(player, weapon);
        addRecoil(player, weapon);

        double speed = weapon.projectileSpeed();
        if (weapon.chargeAffectsSpeed()) {
            // 下限給滿速的三分之一：空弓也該飛得出去，只是又慢又短
            speed *= CHARGE_SPEED_FLOOR + (1 - CHARGE_SPEED_FLOOR) * power;
        }
        if (weapon.chargeAffectsSpread()) {
            // 滿弓最準：力道越低散得越開，額外散佈最多再加一倍基礎值
            spread += weapon.spreadDegrees() * (1 - power);
        }
        double damageScale = weapon.chargeAffectsDamage()
                ? CHARGE_DAMAGE_FLOOR + (1 - CHARGE_DAMAGE_FLOOR) * power
                : 1.0;

        for (int i = 0; i < weapon.pellets(); i++) {
            Vec3 direction = applySpread(level, look, spread);
            projectiles.add(new Projectile(weapon, duel, player, origin,
                    direction.scale(speed), damageScale));
        }

        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(weapon.fireSound());
        if (sound != null) {
            level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1f, 1f);
        }
    }

    /**
     * 把散佈角度套到視線方向上：以視線為軸的圓錐，{@code spreadDegrees} 是半頂角。
     *
     * <p>兩個地方跟直覺寫法不一樣，都是必要的：
     * <ul>
     *   <li><b>半徑要開根號</b>——直接 {@code nextDouble()} 當半徑會讓彈著點擠在圓心，
     *       因為圓盤的面積是隨半徑平方成長的。{@code sqrt} 之後才是均勻分布。</li>
     *   <li><b>偏轉要用視線自己的正交基底</b>，不是世界的 Y 軸。在世界 Y 軸上加偏移的話，
     *       視線越接近垂直、水平分量越小，垂直方向的散佈就跟著塌掉——仰角 60° 時大約只剩一半，
     *       打高牆或對空看得很明顯。</li>
     * </ul>
     */
    private Vec3 applySpread(ServerLevel level, Vec3 look, double spreadDegrees) {
        Vec3 forward = look.normalize();
        if (spreadDegrees <= 0) return forward;

        double theta = level.getRandom().nextDouble() * Math.PI * 2;
        double radius = Math.sqrt(level.getRandom().nextDouble()) * Math.tan(Math.toRadians(spreadDegrees));

        // 視線接近正上下時 (0,1,0) 會跟它平行、外積退化成零向量，那時改拿 X 軸當參考
        Vec3 reference = Math.abs(forward.y) > 0.999 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(reference).normalize();
        Vec3 up = right.cross(forward).normalize();

        return forward
                .add(right.scale(Math.cos(theta) * radius))
                .add(up.scale(Math.sin(theta) * radius))
                .normalize();
    }

    // ---------- 每 tick ----------

    private void onServerTick(MinecraftServer server) {
        tickCooldowns();
        tickRecoil();
        tickProjectiles();
    }

    /** 目前這把武器累積了多少後座力（度）。 */
    private double recoilOf(ServerPlayer player, WeaponDef weapon) {
        Map<String, Double> perWeapon = recoil.get(player.getUUID());
        return perWeapon == null ? 0 : perWeapon.getOrDefault(weapon.id(), 0.0);
    }

    private void addRecoil(ServerPlayer player, WeaponDef weapon) {
        if (weapon.recoil() <= 0) return;
        recoil.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .merge(weapon.id(), weapon.recoil(),
                        (old, add) -> Math.min(weapon.recoilMax(), old + add));
    }

    /**
     * 後座力回穩。
     *
     * <p>固定速率往下掉而不是按比例衰減：按比例的話尾巴會拖很長，玩家永遠等不到「完全回穩」
     * 的那一刻，而「停火多久才會恢復準度」是要能被背下來的。
     */
    private void tickRecoil() {
        for (Map.Entry<UUID, Map<String, Double>> entry : recoil.entrySet()) {
            entry.getValue().replaceAll((id, degrees) -> {
                WeaponDef weapon = config.weapons().byId(id);
                double perTick = (weapon == null ? 6.0 : weapon.recoilRecovery()) / 20.0;
                return degrees - perTick;
            });
            entry.getValue().values().removeIf(degrees -> degrees <= 0);
        }
        recoil.values().removeIf(Map::isEmpty);
    }

    private void tickCooldowns() {
        for (Map<String, Integer> playerCooldowns : cooldowns.values()) {
            playerCooldowns.replaceAll((id, ticks) -> ticks - 1);
            playerCooldowns.values().removeIf(ticks -> ticks <= 0);
        }
        cooldowns.values().removeIf(Map::isEmpty);
    }

    /**
     * 推進所有彈丸。
     *
     * <p>不能直接對 {@code projectiles} 開 iterator：{@link #step} 可能打爆核心而結束整場對戰，
     * END 事件會同步走到 {@link #cleanUp}、在迴圈中途改動這個清單，於是
     * {@code ConcurrentModificationException}。所以走一份複本，回頭再一次清掉死掉的。
     *
     * <p>複本裡可能有已經被 {@code cleanUp} 作廢的彈丸（同一場對戰的其他發），那些要跳過——
     * 對戰結束後地形已經還原，再讓它們飛下去就會在還原好的地形上炸洞。
     */
    private void tickProjectiles() {
        for (Projectile projectile : List.copyOf(projectiles)) {
            if (projectile.dead) continue;
            step(projectile);
        }
        projectiles.removeIf(projectile -> projectile.dead);
    }

    /** 推進一顆彈丸：先檢查這一步會不會打到東西，沒有的話才真的往前移動。 */
    private void step(Projectile projectile) {
        ServerLevel level = projectile.duel.arena().level();
        Vec3 from = projectile.pos;
        Vec3 to = projectile.nextPos();

        // 飛出競技場就消失。用終點判斷而不是起點——擦過框線的那一發不該還打得到外面
        if (!projectile.duel.arena().region().contains(to.x, to.y, to.z)) {
            projectile.dead = true;
            return;
        }

        // 把射手傳進去而不是 null：原版會自動把它排除在命中對象之外，而且不必再賭
        // 「這個 API 收不收 null」——ClipContext 就是因為傳了 null 實體才讓伺服器崩過一次
        ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(projectile.shooterId);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                level, shooter, from, to,
                new AABB(from, to).inflate(1.0),
                Entity::isAlive,
                0.3f);
        if (entityHit != null) {
            onHit(projectile, level, entityHit.getLocation(), entityHit.getEntity(), null);
            return;
        }

        // 一定要用 CollisionContext.empty()，不能傳 null 實體——ClipContext 內部會對它做
        // requireNonNull，彈丸沒有對應的實體所以只能給一個空的碰撞情境
        BlockHitResult blockHit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            onHit(projectile, level, blockHit.getLocation(), null, blockHit.getBlockPos());
            return;
        }

        trail(level, projectile, from, to);
        projectile.advance();
    }

    private void trail(ServerLevel level, Projectile projectile, Vec3 from, Vec3 to) {
        ParticleOptions particle = particle(projectile.weapon.trailParticle());
        // 一格一顆：速度快的武器（雷射一 tick 飛 20 格）也要畫成一條連續的線，不是一串點
        int steps = Math.max(1, (int) from.distanceTo(to));
        for (int i = 0; i < steps; i++) {
            Vec3 point = from.lerp(to, (double) i / steps);
            level.sendParticles(particle, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
    }

    // ---------- 命中 ----------

    private void onHit(Projectile projectile, ServerLevel level, Vec3 location,
                       Entity directEntity, BlockPos directBlock) {
        projectile.dead = true;
        WeaponDef weapon = projectile.weapon;

        level.sendParticles(ParticleTypes.EXPLOSION, location.x, location.y, location.z, 1, 0, 0, 0, 0);

        if (weapon.splashRadius() > 0) {
            splash(projectile, level, location);
            return;
        }

        if (directEntity instanceof LivingEntity living) {
            // 直擊：順著彈丸飛的方向推
            damageEntity(projectile, level, living, projectile.damage(), projectile.velocity, 1.0);
        } else if (directBlock != null) {
            damageBlock(projectile, level, directBlock, projectile.damageVsBlock());
        }
    }

    /** 濺射：範圍內的方塊與生物都吃傷害，離爆心越遠越低。 */
    private void splash(Projectile projectile, ServerLevel level, Vec3 center) {
        double radius = projectile.weapon.splashRadius();
        int r = (int) Math.ceil(radius);
        BlockPos origin = BlockPos.containing(center);

        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            double distance = Math.sqrt(pos.distToCenterSqr(center));
            if (distance > radius) continue;
            damageBlock(projectile, level, pos.immutable(),
                    projectile.damageVsBlock() * falloff(distance, radius));
        }

        AABB box = new AABB(center, center).inflate(radius);
        for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && e.isAlive())) {
            double distance = entity.position().distanceTo(center);
            if (distance > radius) continue;
            // 濺射：從爆心往外推，而不是順著彈丸的方向——爆炸該把人推開，不是把人推著走
            double scale = falloff(distance, radius);
            damageEntity(projectile, level, (LivingEntity) entity,
                    projectile.damage() * scale,
                    entity.getBoundingBox().getCenter().subtract(center), scale);
        }
    }

    private double falloff(double distance, double radius) {
        return Math.max(0.2, 1.0 - distance / radius);
    }

    /**
     * 打中一個生物：扣血並推開。
     *
     * <p>傷害沒吃到就不推。{@code ALLOW_DAMAGE} 會擋掉好幾種傷害——建造階段的互毆、
     * 打在自己人熊貓上的濺射誤傷——那些情況下如果還推得動，等於留了一個「不扣血但能位移」
     * 的後門：對著自己的熊貓開一發高爆彈就能把牠轟到想要的位置，牽繩那套慢慢牽的設計就被繞過了。
     *
     * @param direction 推的方向，不必先正規化；長度為 0 時只扣血不推
     * @param scale     力道倍率，濺射用距離衰減、直擊給 1.0
     */
    private void damageEntity(Projectile projectile, ServerLevel level, LivingEntity target,
                              double damage, Vec3 direction, double scale) {
        if (damage <= 0) return;

        ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(projectile.shooterId);
        // 找不到射手（離線了）就退回 generic，彈丸已經在飛了，不該因為射手離線就無效
        var source = shooter != null
                ? level.damageSources().playerAttack(shooter)
                : level.damageSources().generic();
        if (!target.hurtServer(level, source, (float) damage)) return;

        knockBack(target, direction, projectile.weapon.knockback() * scale);
    }

    /**
     * 把目標推開。
     *
     * <p>三個細節不能省：
     * <ul>
     *   <li><b>一定要往上抬一點</b>——純水平的推力會被地面摩擦當場吃掉大半，看起來像沒推到。
     *       原版的擊退也是這樣加垂直分量的。</li>
     *   <li><b>被推的如果是玩家，要自己把速度送給客戶端</b>——玩家的移動是客戶端說了算，
     *       伺服器改完 {@code deltaMovement} 不通知的話，下一個移動封包就把它蓋回去了，
     *       等於完全沒推。{@code hurtMarked} 只能讓**旁觀者**看到，本人看不到。</li>
     *   <li><b>關掉 AI 的生物不推</b>——軍火商那種站定點的 NPC 沒有 AI 可以走回去，推一次就
     *       永遠歪在那裡，商店的互動範圍也跟著跑掉。被打死是設計，被推走不是。</li>
     * </ul>
     */
    private void knockBack(LivingEntity target, Vec3 direction, double strength) {
        if (strength <= 0 || direction.lengthSqr() < 1.0E-6) return;
        if (target instanceof Mob mob && mob.isNoAi()) return;

        Vec3 push = direction.normalize().scale(strength);
        target.push(push.x, Math.max(push.y, strength * 0.35), push.z);
        target.hurtMarked = true;
        if (target instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }

    /**
     * 對一格方塊累積傷害，打穿了就拆掉。
     *
     * @return true ＝ 這一發把方塊打掉了
     */
    private boolean damageBlock(Projectile projectile, ServerLevel level, BlockPos pos, double damage) {
        if (damage <= 0 || !projectile.weapon.breaksBlocks()) return false;

        Duel duel = projectile.duel;
        Region region = duel.arena().region();
        if (!region.contains(pos)) return false;

        // 框線是場地的一部分，任何武器都拆不掉
        if (region.isHorizontalEdge(pos.getX(), pos.getZ())) return false;

        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.liquid()) return false;

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return false; // 基岩之類：原版就打不掉

        float maxHp = blockHp(hardness, projectile.weapon.pierce());
        Map<BlockPos, Float> damageMap = blockDamage.computeIfAbsent(duel, k -> new HashMap<>());
        float accumulated = damageMap.merge(pos, (float) damage, Float::sum);

        if (accumulated < maxHp) {
            // 讓玩家看得到「這格快破了」——沿用原版的挖掘裂痕動畫
            level.destroyBlockProgress(progressId(pos), pos, (int) (accumulated / maxHp * 10));
            return false;
        }

        damageMap.remove(pos);
        level.destroyBlockProgress(progressId(pos), pos, -1);
        // false ＝ 不掉落物品：對戰中把牆炸開不該順便給對手一堆建材
        level.destroyBlock(pos, false, null, 512);
        return true;
    }

    /**
     * 方塊血量 ＝ 硬度 × 係數，再依穿甲折減。
     *
     * <p>穿甲 1.0 會把血量壓到最低的 1 點——「完全無視硬度」，一發就破，但仍然要打中。
     */
    private float blockHp(float hardness, double pierce) {
        float base = Math.max(1f, hardness * (float) config.settings().blockHpPerHardness());
        return Math.max(1f, base * (float) (1.0 - pierce));
    }

    /**
     * 挖掘裂痕的識別碼。原版拿它當「是誰在挖」，我們沒有對應的實體，所以直接用座標推。
     *
     * <p>重點是**同一格永遠得到同一個值**：更新進度與最後清除（送 −1）必須用同一個 id，
     * 否則裂痕會清不掉。不同格之間偶爾撞號只會讓兩格的裂痕互相蓋掉，純視覺問題。
     */
    private static int progressId(BlockPos pos) {
        return pos.hashCode();
    }

    private ParticleOptions particle(Identifier id) {
        var type = BuiltInRegistries.PARTICLE_TYPE.getValue(id);
        if (type instanceof SimpleParticleType simple) {
            return simple;
        }
        // 帶參數的粒子（例如 dust 要指定顏色）沒辦法只用一個 id 建出來，退回一個看得見的預設
        FortressDuel.LOGGER.warn("Particle '{}' is missing or needs parameters, falling back to crit", id);
        return ParticleTypes.CRIT;
    }

    // ---------- 查詢 ----------

    public int activeProjectiles() {
        return projectiles.size();
    }
}
