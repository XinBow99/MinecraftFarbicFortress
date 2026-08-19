package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mixin.MobAccessor;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.util.Ground;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
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
        engine.registerType("restless", SkillTypes::restless);
        engine.registerType("steal", SkillTypes::steal);
        engine.registerType("expire", SkillTypes::expire);
    }

    /**
     * 壽命到了就自己消失。
     *
     * <p>params：{@code seconds}（活多久）。
     *
     * <p>給「一陣風式」的事件用：鼠疫那種一次放十幾隻、目的是逼玩家在幾秒內做決定的怪，
     * 留著只會變成打掃工作——而打掃不是遊戲。有了它，事件的張力落在**那二十秒**裡，
     * 而不是落在「等一下要花多久把牠們清乾淨」。
     *
     * <p>計時看的是實體自己的 {@code tickCount}（出生到現在幾 tick），不是技能的間隔。
     * {@code INTERVAL} 的第一次觸發是**立刻**發生的（見 {@code SkillEngine} 的倒數：初值
     * 不存在，{@code merge(-1)} 之後就已經 ≤ 0），拿它當計時器的話怪一生出來就死了。
     * 所以這個技能設成每秒問一次，真正的判斷交給 tickCount。
     *
     * <p>用 {@code discard()} 而不是 {@code kill()}：牠不是被殺的，**不該發賞金**——
     * 不然只要站著等就有錢拿。冒一陣煙是為了讓它看起來像自然消失，跟怪物數量上限
     * （{@code MobSpawner.enforceCap}）用的是同一個表達。
     */
    private static boolean expire(SkillContext ctx) {
        int lifespan = Math.max(1, ctx.skill().param("seconds", 20)) * 20;
        LivingEntity mob = ctx.caster();
        if (mob.tickCount < lifespan) return false;

        ctx.level().sendParticles(ParticleTypes.POOF,
                mob.getX(), mob.getY(0.5), mob.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
        mob.discard();
        return true;
    }

    /**
     * 小偷：追人 → 搶走一整疊東西叼在嘴上 → 掉頭就跑，直到被打死才吐出來。
     *
     * <p>params：{@code speed}（導航速度倍率）、{@code range}（看得到幾格內的玩家）、
     * {@code reach}（多近算搶得到）、{@code flee}（逃跑時一次跑多遠）。
     *
     * <h2>三件事是原版幫我們做的</h2>
     * <ul>
     *   <li><b>叼在嘴上</b>——搶到的東西放進主手欄位，而狐狸本來就會把主手的物品渲染在嘴裡</li>
     *   <li><b>死了才掉</b>——掉落機率設成 1.0，剩下的交給原版的死亡掉落</li>
     *   <li><b>一整疊拿走</b>——直接搬整個 {@link ItemStack}，不用自己算數量</li>
     * </ul>
     *
     * <h2>為什麼要把原版的行為整組拔掉</h2>
     * <p>原版的狐狸**白天會睡覺、會躲玩家、會去撿地上的東西、會撲雞**。那四條每一條都在跟
     * 這個技能搶導航，而「躲玩家」更是跟「追玩家」直接對衝。只靠每秒重下一次路徑壓不住
     * ——{@link #restless} 那種輕量作法能成立，是因為它跟原版的閒晃目標想做的事情一樣。
     *
     * <p>拔的動作是**冪等**的：每次發動都看一眼還有沒有目標在，有就清掉。這樣就不必另外記
     * 「這一隻清過了沒有」，也不用為了同一件事多寫一份 ON_SPAWN 的技能定義。
     */
    private static boolean steal(SkillContext ctx) {
        if (!(ctx.caster() instanceof PathfinderMob mob)) return false;

        clearVanillaGoals(mob);

        double speed = ctx.skill().param("speed", 1.3);
        double range = ctx.skill().param("range", 24.0);
        double reach = ctx.skill().param("reach", 1.8);
        int flee = Math.max(4, ctx.skill().param("flee", 16));

        Player nearest = ctx.level().getNearestPlayer(mob, range);
        if (nearest == null) return false;

        // 已經得手 → 掉頭就跑。目標是「離這個人最遠」而不是某個固定方向，
        // 所以玩家繞過去堵牠的時候牠會自己改道
        if (!mob.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            Vec3 away = DefaultRandomPos.getPosAway(mob, flee, 7, nearest.position());
            if (away == null) return false;
            return mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }

        if (mob.distanceToSqr(nearest) > reach * reach) {
            mob.getLookControl().setLookAt(nearest, 30f, 30f);
            return mob.getNavigation().moveTo(nearest, speed);
        }
        return grab(ctx.level(), mob, nearest);
    }

    /**
     * 從這個人身上隨機搶一疊。
     *
     * <p>**整疊拿走**，不是拿一個：被偷走 64 個石頭跟被偷走 1 個石頭，前者才是一件事。
     *
     * <p>只翻主要的物品欄（含快捷列與副手），不碰盔甲——盔甲穿在身上，被叼走的畫面說不通，
     * 而且那會讓這個事件從「討厭」變成「毀掉這一局」。
     */
    private static boolean grab(ServerLevel level, Mob mob, Player victim) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < victim.getInventory().getNonEquipmentItems().size(); i++) {
            if (!victim.getInventory().getItem(i).isEmpty()) candidates.add(i);
        }
        if (candidates.isEmpty()) return false;

        int slot = candidates.get(level.getRandom().nextInt(candidates.size()));
        ItemStack stolen = victim.getInventory().getItem(slot);
        victim.getInventory().setItem(slot, ItemStack.EMPTY);

        mob.setItemSlot(EquipmentSlot.MAINHAND, stolen);
        // 不設的話原版只有一成機率掉落，而「殺了牠就拿得回來」是這個事件唯一的出口
        mob.setDropChance(EquipmentSlot.MAINHAND, 1.0f);

        if (victim instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("狐狸叼走了你的 ").withStyle(ChatFormatting.RED)
                    .append(stolen.getHoverName())
                    .append(Component.literal(" ×" + stolen.getCount() + "！殺了牠才拿得回來")));
        }
        level.playSound(null, mob.blockPosition(), SoundEvents.FOX_AGGRO, SoundSource.HOSTILE, 1f, 1.4f);
        return true;
    }

    /**
     * 把原版掛在這隻怪身上的目標整組拔掉。
     *
     * <p>冪等：已經空了就什麼都不做，所以每次發動都呼叫一次也沒有成本。
     */
    private static void clearVanillaGoals(Mob mob) {
        ((MobAccessor) mob).fortressduel$goalSelector().removeAllGoals(goal -> true);
        ((MobAccessor) mob).fortressduel$targetSelector().removeAllGoals(goal -> true);
    }

    /**
     * 躁動：一停下來就馬上派牠去下一個地方。
     *
     * <p>params：{@code radius}（下一個目的地取幾格內）、{@code vertical}（上下浮動幾格，
     * 會飛的才有意義）、{@code speed}（導航速度倍率）。
     *
     * <p><b>為什麼需要這個</b>：原版沒有「發呆多久」這個設定，那個停頓是寫死在各自的 AI 裡的，
     * 而且兩種怪還是兩套系統——蜜蜂用 goal（{@code BeeWanderGoal} 是私有內部類別，
     * 而且要碰 {@code Mob.goalSelector} 得先開 access widener），悅靈用 brain
     * （{@code RunOne} 裡面排著帶隨機時長的 {@code DoNothing}）。想從那兩邊改，等於為了同一個
     * 效果寫兩份互不相干的 mixin。
     *
     * <p>所以這裡不去改牠們什麼時候決定停下來，而是**在牠停下來的那一刻就給牠下一個目的地**。
     * 走的是牠自己的導航（{@code moveTo}）而不是硬塞速度，所以路徑、避障、飛行姿態全部還是
     * 原版的——看起來就是這隻生物比較好動，不是被外力推著走。一份實作同時吃 goal 與 brain 兩種怪。
     *
     * <p>導航還在跑就回 false（不進冷卻）：那不是「發動了」，是牠本來就在動，不需要插手。
     */
    private static boolean restless(SkillContext ctx) {
        if (!(ctx.caster() instanceof Mob mob)) return false;

        PathNavigation navigation = mob.getNavigation();
        if (!navigation.isDone()) return false;   // 還在走就別打斷牠

        int radius = Math.max(1, ctx.skill().param("radius", 8));
        int vertical = Math.max(0, ctx.skill().param("vertical", 3));
        double speed = ctx.skill().param("speed", 1.0);

        RandomSource random = ctx.level().getRandom();
        double x = mob.getX() + random.nextInt(radius * 2 + 1) - radius;
        double z = mob.getZ() + random.nextInt(radius * 2 + 1) - radius;
        double y = vertical == 0 ? mob.getY() : mob.getY() + random.nextInt(vertical * 2 + 1) - vertical;

        return navigation.moveTo(x, y, z, speed);
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
     * <p>params：{@code radius}（找幾格內）、{@code count}（一次最多拆幾格）、
     * {@code max_hardness}（只啃得動硬度不超過這個值的方塊，省略 = 不限）。
     *
     * <p>只拆「跟開場前不一樣」的格子（見 {@code Arena.isBuilt}）——天然地形不動，框線也不動。
     * 不然這隻怪會在中場自己挖出一個坑，看起來只是壞掉，而它的定位是「對人造物有破壞慾」。
     *
     * <p>{@code max_hardness} 用的是原版硬度，跟 weapons.yml 算方塊血量的是同一個數字，
     * 所以門檻直接對應建材的價格階梯：泥土 0.5、石頭 1.5、橡木板 2.0、鐵塊 5.0、黑曜石 50。
     * 填 2.0 就是「便宜建材啃得穿、鐵塊與黑曜石完全免疫」——貴的建材因此買到的不只是血量。
     *
     * <p>拆掉的格子照樣進快照的還原路徑，所以對戰結束地形會補回來；也不掉落物品，
     * 理由跟玩家自己挖一樣——牆被拆開不該順便變成建材。
     *
     * <p>找不到人造物就回 false（不進冷卻）：這不是「發動了」，是「沒東西可拆」。
     * 硬度擋下來的也算「沒東西可拆」——啃不動黑曜石的老鼠應該一直試，不是試一次就休息。
     */
    private static boolean breakBlocks(SkillEngine engine, SkillContext ctx) {
        int radius = Math.max(1, ctx.skill().param("radius", 3));
        int count = Math.max(1, ctx.skill().param("count", 2));
        double maxHardness = ctx.skill().param("max_hardness", Double.MAX_VALUE);

        LivingEntity caster = ctx.caster();
        ServerLevel level = ctx.level();
        BlockPos origin = caster.blockPosition();

        Duel duel = engine.duels().duelAt(level, origin);
        if (duel == null) return false;   // 不在任何競技場裡，沒有規則可以套

        // 先收集再拆：邊掃邊拆會讓「已經變成空氣」的格子影響後面的判斷
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (!duel.arena().isBuilt(pos)) continue;
            // isBuilt 已經擋掉了硬度為負的（基岩之類），所以這裡只要比上界
            if (level.getBlockState(pos).getDestroySpeed(level, pos) > maxHardness) continue;
            targets.add(pos.immutable());
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
