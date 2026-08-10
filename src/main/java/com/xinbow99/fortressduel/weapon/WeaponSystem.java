package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.Iterator;
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
        DuelEvents.END.register((duel, result) -> {
            blockDamage.remove(duel);
            // 彈藥跟錢一樣不跨場：下一場從開場配給重新算起
            pouches.remove(duel.north().playerId());
            pouches.remove(duel.south().playerId());
        });
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
            player.sendSystemMessage(Msg.warn("武器只在對戰中能用。"));
            return InteractionResult.FAIL;
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

    /** 玩家主手上拿的武器；不是武器就回 null。 */
    public WeaponDef weaponInHand(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
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

    /** 把散佈角度套到視線方向上。散佈是圓錐狀的，所以兩個軸各抖一次。 */
    private Vec3 applySpread(ServerLevel level, Vec3 look, double spreadDegrees) {
        if (spreadDegrees <= 0) return look.normalize();

        double spread = Math.toRadians(spreadDegrees);
        double yaw = (level.getRandom().nextDouble() * 2 - 1) * spread;
        double pitch = (level.getRandom().nextDouble() * 2 - 1) * spread;

        // 繞世界的 Y 軸轉 yaw、再往上下偏 pitch。視線接近正上下時這個近似會失真，
        // 但散佈本來就只有幾度，那點誤差看不出來
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        Vec3 rotated = new Vec3(look.x * cos - look.z * sin, look.y, look.x * sin + look.z * cos);
        return rotated.add(0, Math.tan(pitch), 0).normalize();
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

    private void tickProjectiles() {
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile projectile = it.next();
            step(projectile);
            if (projectile.dead) {
                it.remove();
            }
        }
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

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                level, null, from, to,
                new AABB(from, to).inflate(1.0),
                entity -> entity.isAlive() && !entity.getUUID().equals(projectile.shooterId),
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
            damageEntity(projectile, level, living, weapon.damage());
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
            damageEntity(projectile, level, (LivingEntity) entity,
                    projectile.weapon.damage() * falloff(distance, radius));
        }
    }

    private double falloff(double distance, double radius) {
        return Math.max(0.2, 1.0 - distance / radius);
    }

    private void damageEntity(Projectile projectile, ServerLevel level, LivingEntity target, double damage) {
        if (damage <= 0) return;

        ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(projectile.shooterId);
        // 找不到射手（離線了）就退回 generic，彈丸已經在飛了，不該因為射手離線就無效
        var source = shooter != null
                ? level.damageSources().playerAttack(shooter)
                : level.damageSources().generic();
        target.hurtServer(level, source, (float) damage);
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

        // 核心不是普通方塊：打到它是扣核心血量，不是拆方塊
        if (duel.arena().isCoreBlock(pos)) {
            ServerPlayer shooter = level.getServer().getPlayerList().getPlayer(projectile.shooterId);
            duel.damageCore(pos, shooter, (float) damage);
            return false;
        }
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
            level.destroyBlockProgress(pos.hashCode(), pos, (int) (accumulated / maxHp * 10));
            return false;
        }

        damageMap.remove(pos);
        level.destroyBlockProgress(pos.hashCode(), pos, -1);
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
