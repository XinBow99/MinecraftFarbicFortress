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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
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

    private final ConfigManager config;
    private final DuelManager duels;

    private final List<Projectile> projectiles = new ArrayList<>();
    /** 玩家 → 各武器的剩餘冷卻（tick）。 */
    private final Map<UUID, Map<String, Integer>> cooldowns = new HashMap<>();
    /** 玩家 → 他的彈藥袋。 */
    private final Map<UUID, AmmoPouch> pouches = new HashMap<>();
    /** 每一場對戰裡、每一格已經累積的傷害。 */
    private final Map<Duel, Map<BlockPos, Float>> blockDamage = new HashMap<>();

    public WeaponSystem(ConfigManager config, DuelManager duels) {
        this.config = config;
        this.duels = duels;
    }

    public void register() {
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
     *   <li>清掉累積傷害與彈藥。</li>
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

        // 彈藥跟錢一樣不跨場：下一場從開場配給重新算起
        pouches.remove(duel.north().playerId());
        pouches.remove(duel.south().playerId());
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

    private InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return InteractionResult.PASS;

        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        WeaponDef weapon = config.weapons().byItem(itemId);
        if (weapon == null) return InteractionResult.PASS;

        Duel duel = duels.duelOf(player);
        if (duel == null) {
            // 沒在對戰就完全不插手。武器綁的是鐵錠、燧石、TNT、煙火這類原版常見物品，
            // 攔下來的話等於把整個伺服器的普通物品弄壞（煙火放不出去、終界之眼丟不了），
            // 還會對每個右鍵的人洗一則訊息
            return InteractionResult.PASS;
        }
        if (!duel.state().canAttack()) {
            player.sendSystemMessage(Msg.warn("建造階段不能開火。"));
            return InteractionResult.FAIL;
        }

        Map<String, Integer> playerCooldowns = cooldowns.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        if (playerCooldowns.containsKey(weapon.id())) {
            return InteractionResult.FAIL;
        }

        AmmoPouch pouch = pouchOf(player);
        if (!pouch.consume(weapon.id(), weapon.ammoPerShot())) {
            player.sendSystemMessage(Msg.plain("沒有子彈了！去武器商店補充", ChatFormatting.RED), true);
            player.level().playSound(null, player.blockPosition(),
                    SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.7f, 2.0f);
            // 空槍也要進冷卻，不然按住不放會每 tick 洗一次「沒有子彈」
            playerCooldowns.put(weapon.id(), weapon.cooldownTicks());
            return InteractionResult.FAIL;
        }
        playerCooldowns.put(weapon.id(), weapon.cooldownTicks());

        fire(player, duel, weapon);
        return InteractionResult.SUCCESS;
    }

    /** 這名玩家的彈藥袋，沒有就開一個。 */
    public AmmoPouch pouchOf(ServerPlayer player) {
        return pouches.computeIfAbsent(player.getUUID(), k -> new AmmoPouch());
    }

    /** 開場配給：把每把武器的 starting_ammo 發下去。 */
    public void giveStartingAmmo(ServerPlayer player) {
        AmmoPouch pouch = pouchOf(player);
        for (WeaponDef weapon : config.weapons().all()) {
            if (weapon.startingAmmo() > 0) {
                pouch.set(weapon.id(), Math.min(weapon.startingAmmo(), weapon.ammoCapacity()));
            }
        }
    }

    /**
     * HUD 用的「現有/上限」，例如 {@code 100/120}。
     *
     * @return 手上沒拿武器時回傳 null
     */
    public String ammoDisplay(ServerPlayer player) {
        WeaponDef weapon = weaponInHand(player);
        if (weapon == null) return null;
        return pouchOf(player).get(weapon.id()) + "/" + weapon.ammoCapacity();
    }

    /** 依 id 查武器。商店要靠它把商品接到武器定義上。 */
    public WeaponDef byId(String id) {
        return config.weapons().byId(id);
    }

    /**
     * 玩家手上拿的武器，主手優先；兩手都不是武器就回 null。
     *
     * <p>要看兩隻手：開火走的是 {@code getItemInHand(hand)}（兩手都能觸發），HUD 只看主手的話，
     * 武器放副手就會變成「打得出去但看不到剩幾發」。
     */
    public WeaponDef weaponInHand(ServerPlayer player) {
        WeaponDef main = weaponOf(player.getMainHandItem());
        return main != null ? main : weaponOf(player.getOffhandItem());
    }

    private WeaponDef weaponOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return config.weapons().byItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private void fire(ServerPlayer player, Duel duel, WeaponDef weapon) {
        ServerLevel level = player.level();
        Vec3 origin = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        for (int i = 0; i < weapon.pellets(); i++) {
            Vec3 direction = applySpread(level, look, weapon.spreadDegrees());
            projectiles.add(new Projectile(weapon, duel, player, origin,
                    direction.scale(weapon.projectileSpeed())));
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
        tickProjectiles();
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
            damageEntity(projectile, level, living, weapon.damage(), projectile.velocity, 1.0);
        } else if (directBlock != null) {
            damageBlock(projectile, level, directBlock, weapon.damageVsBlock());
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
                    projectile.weapon.damageVsBlock() * falloff(distance, radius));
        }

        AABB box = new AABB(center, center).inflate(radius);
        for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && e.isAlive())) {
            double distance = entity.position().distanceTo(center);
            if (distance > radius) continue;
            // 濺射：從爆心往外推，而不是順著彈丸的方向——爆炸該把人推開，不是把人推著走
            double scale = falloff(distance, radius);
            damageEntity(projectile, level, (LivingEntity) entity,
                    projectile.weapon.damage() * scale,
                    entity.getBoundingBox().getCenter().subtract(center), scale);
        }
    }

    private double falloff(double distance, double radius) {
        return Math.max(0.2, 1.0 - distance / radius);
    }

    /**
     * 打中一個生物：扣血並推開。
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
        target.hurtServer(level, source, (float) damage);
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
