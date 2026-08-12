package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.util.Ground;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 內建的技能實作。
 *
 * <p>每一個都對應 skills.yml 裡的一個 {@code type}；同一個 type 可以被好幾個技能用不同的
 * {@code params} 再包一層。要加新技能就在這裡多寫一個 {@code engine.registerType(...)}。
 */
public final class SkillTypes {

    private SkillTypes() {}

    public static void registerBuiltins(SkillEngine engine) {
        engine.registerType("clone", ctx -> clone(engine, ctx));
        engine.registerType("summon", ctx -> summon(engine, ctx));
        engine.registerType("heal", SkillTypes::heal);
        engine.registerType("teleport", SkillTypes::teleport);
        engine.registerType("effect", SkillTypes::effect);
        engine.registerType("break_blocks", ctx -> breakBlocks(engine, ctx));
    }

    /**
     * 分身：複製自己。
     *
     * <p>params：{@code count}（幾隻）、{@code health_ratio}（分身的血量佔本體上限多少）、
     * {@code scale_ratio}（體型倍率）、{@code radius}（散開幾格）、{@code inherit_skills}
     * （分身要不要也帶著技能——帶著才會連鎖分身，但受連鎖深度上限保護）。
     */
    private static boolean clone(SkillEngine engine, SkillContext ctx) {
        int count = Math.max(1, ctx.skill().param("count", 2));
        double healthRatio = ctx.skill().param("health_ratio", 0.5);
        double scaleRatio = ctx.skill().param("scale_ratio", 0.8);
        int radius = Math.max(1, ctx.skill().param("radius", 3));
        boolean inherit = ctx.skill().param("inherit_skills", false);

        // 深度到頂就不再分身。回 false 讓框架不要進冷卻——這不是「發動了」，是「發動不了」
        if (inherit && !engine.canChain(ctx)) return false;

        LivingEntity caster = ctx.caster();
        ServerLevel level = ctx.level();
        float health = (float) Math.max(1, caster.getMaxHealth() * healthRatio);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = caster.getType().spawn(level, nearby(level, caster, radius), EntitySpawnReason.EVENT);
            if (!(entity instanceof LivingEntity copy)) continue;

            copy.setCustomName(Component.literal(ctx.def().displayName() + "的分身"));
            MobSpawner.applyStats(copy, ctx.def());
            MobSpawner.setScale(copy, ctx.def().scale() * scaleRatio);
            // 血量上限已經被 applyStats 設成本體的值，這裡再壓成分身該有的血
            MobSpawner.setMaxHealth(copy, health);
            copy.setHealth(health);

            if (inherit) {
                engine.track(copy, ctx.def(), ctx.depth() + 1);
            }
            spawned++;
        }
        return spawned > 0;
    }

    /**
     * 啃方塊：把附近**玩家蓋的**東西拆掉。
     *
     * <p>params：{@code radius}（找幾格內）、{@code count}（一次最多拆幾格）。
     *
     * <p>只拆「跟開場前不一樣」的格子（見 {@code Arena.isBuilt}）——天然地形不動，框線也不動。
     * 不然這隻怪會在中場自己挖出一個坑，看起來只是壞掉，而它的定位是「對人造物有破壞慾」。
     *
     * <p>拆掉的格子照樣進快照的還原路徑，所以對戰結束地形會補回來；也不掉落物品，
     * 理由跟玩家自己挖一樣——牆被拆開不該順便變成建材。
     *
     * <p>找不到人造物就回 false（不進冷卻）：這不是「發動了」，是「沒東西可拆」。
     */
    private static boolean breakBlocks(SkillEngine engine, SkillContext ctx) {
        int radius = Math.max(1, ctx.skill().param("radius", 3));
        int count = Math.max(1, ctx.skill().param("count", 2));

        LivingEntity caster = ctx.caster();
        ServerLevel level = ctx.level();
        BlockPos origin = caster.blockPosition();

        Duel duel = engine.duels().duelAt(level, origin);
        if (duel == null) return false;   // 不在任何競技場裡，沒有規則可以套

        // 先收集再拆：邊掃邊拆會讓「已經變成空氣」的格子影響後面的判斷
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (duel.arena().isBuilt(pos)) {
                targets.add(pos.immutable());
            }
        }
        if (targets.isEmpty()) return false;

        Collections.shuffle(targets, new java.util.Random(level.getRandom().nextLong()));
        int broken = 0;
        for (BlockPos pos : targets) {
            if (broken >= count) break;
            duel.arena().breakBuilt(pos);
            // 武器系統累積的傷害要跟著忘掉，不然補一塊新的上去會繼承舊傷害
            engine.duels().forgetBlockDamage(pos);
            broken++;
        }
        return broken > 0;
    }

    /**
     * 召喚：叫出 mobs.yml 裡的另一種怪。
     *
     * <p>params：{@code mob}（怪物 id）、{@code radius}（散開幾格）。數量走那隻怪自己的
     * {@code pack_min/max}，不在這裡另外開一個會跟它打架的欄位。
     */
    private static boolean summon(SkillEngine engine, SkillContext ctx) {
        String mobId = ctx.skill().param("mob", "");
        MobDef def = engine.config().mobs().byId(mobId);
        if (def == null) {
            FortressDuel.LOGGER.warn("Skill {} tries to summon mob '{}' which is not defined in mobs.yml", ctx.skill().id(), mobId);
            return false;
        }
        int radius = Math.max(1, ctx.skill().param("radius", 4));
        return !MobSpawner.spawnPack(ctx.level(), def, ctx.caster().blockPosition(), radius, engine).isEmpty();
    }

    /**
     * 回血。
     *
     * <p>params：{@code amount}（固定值）或 {@code ratio}（佔血量上限的比例）。兩個都寫的話相加。
     */
    private static boolean heal(SkillContext ctx) {
        LivingEntity caster = ctx.caster();
        float amount = (float) ctx.skill().param("amount", 0.0)
                + (float) (caster.getMaxHealth() * ctx.skill().param("ratio", 0.0));
        if (amount <= 0 || caster.getHealth() >= caster.getMaxHealth()) return false;

        caster.heal(amount);
        return true;
    }

    /**
     * 順移：瞬間換一個位置（網頁版終界怪物的移動方式）。
     *
     * <p>params：{@code radius}（順移幾格內）、{@code toward_attacker}（true ＝ 往攻擊者身邊跳，
     * false ＝ 隨機跳開，用來當閃避）。
     */
    private static boolean teleport(SkillContext ctx) {
        int radius = Math.max(1, ctx.skill().param("radius", 8));
        boolean towardAttacker = ctx.skill().param("toward_attacker", false);

        BlockPos target;
        if (towardAttacker && ctx.attacker() != null) {
            target = nearby(ctx.level(), ctx.attacker(), 2);
        } else {
            target = nearby(ctx.level(), ctx.caster(), radius);
        }

        ctx.caster().snapTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        return true;
    }

    /**
     * 給自己上一個藥水效果。
     *
     * <p>params：{@code effect}（例如 minecraft:speed）、{@code duration_seconds}、{@code amplifier}。
     */
    private static boolean effect(SkillContext ctx) {
        String id = ctx.skill().param("effect", "");
        Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id)).orElse(null);
        if (effect == null) {
            FortressDuel.LOGGER.warn("Skill {} references effect '{}' which does not exist", ctx.skill().id(), id);
            return false;
        }

        int duration = (int) Math.round(ctx.skill().param("duration_seconds", 10.0) * 20);
        int amplifier = ctx.skill().param("amplifier", 0);
        ctx.caster().addEffect(new MobEffectInstance(effect, duration, amplifier));
        return true;
    }

    /** 在 origin 周圍隨機取一格地表。走 {@link Ground} 而不是直接問 heightmap——理由見那裡。 */
    private static BlockPos nearby(ServerLevel level, Entity origin, int radius) {
        Vec3 pos = origin.position();
        int x = (int) Math.round(pos.x) + level.getRandom().nextInt(radius * 2 + 1) - radius;
        int z = (int) Math.round(pos.z) + level.getRandom().nextInt(radius * 2 + 1) - radius;
        return Ground.onSurface(level, x, z);
    }
}
