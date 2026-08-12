package com.xinbow99.fortressduel.mobs.entity;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 依 {@link MobDef} 生成怪物。
 *
 * <p>不註冊新的實體型別——拿原版實體當底再套設定裡的數值，所以 mobs.yml 加一種怪不用寫 Java，
 * 也不會有「客戶端沒裝 mod 就看不到」的問題。技能同理：生成時把 {@code skills:} 交給
 * {@link SkillEngine} 掛上去。
 */
public final class MobSpawner {

    private MobSpawner() {}

    /**
     * 在 center 附近散開生成一群，並掛上牠的技能。
     *
     * @param engine 技能引擎；傳 null ＝ 這群怪不掛技能
     * @param spread 散佈半徑（格）
     * @return 實際生成出來的實體；型別不存在或生成失敗時是空的
     */
    public static List<Entity> spawnPack(ServerLevel level, MobDef def, BlockPos center, int spread,
                                         SkillEngine engine) {
        // 逐隻依權重抽型別，所以「混合族群」（一群裡有兔子、狐狸、駱駝…）不用開好幾個
        // MobDef。只寫一種 entity 的怪，這裡就是一個只有一個元素的清單，行為跟以前完全一樣
        List<EntityType<?>> types = new ArrayList<>(def.entities().size());
        List<Double> weights = new ArrayList<>(def.entities().size());
        double totalWeight = 0;
        for (MobDef.EntityChoice choice : def.entities()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(choice.entity()).orElse(null);
            if (type == null) {
                FortressDuel.LOGGER.warn("Mob {} references entity '{}' which does not exist, skipping that one",
                        def.id(), choice.entity());
                continue;
            }
            types.add(type);
            weights.add(choice.weight());
            totalWeight += choice.weight();
        }
        if (types.isEmpty()) {
            FortressDuel.LOGGER.warn("Mob {} has no valid entity types, skipping this spawn", def.id());
            return List.of();
        }

        int count = def.packMin() + level.getRandom().nextInt(def.packMax() - def.packMin() + 1);
        List<Entity> spawned = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            BlockPos pos = scatter(level, center, spread);
            EntityType<?> type = pick(types, weights, totalWeight, level);
            Entity entity = type.spawn(level, pos, EntitySpawnReason.EVENT);
            if (entity == null) continue;

            entity.setCustomName(Component.literal(def.displayName()));
            if (entity instanceof LivingEntity living) {
                applyStats(living, def);
                if (engine != null) {
                    engine.track(living, def, 0);
                }
            }
            spawned.add(entity);
        }
        return spawned;
    }


    /** 依權重抽一種實體型別。跟 MobRegistry／IncidentRegistry 的抽籤是同一個做法。 */
    private static EntityType<?> pick(List<EntityType<?>> types, List<Double> weights,
                                      double totalWeight, ServerLevel level) {
        if (types.size() == 1 || totalWeight <= 0) return types.getFirst();

        double roll = level.getRandom().nextDouble() * totalWeight;
        for (int i = 0; i < types.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return types.get(i);
        }
        return types.getLast();
    }

    private static BlockPos scatter(ServerLevel level, BlockPos center, int spread) {
        int x = center.getX() + level.getRandom().nextInt(spread * 2 + 1) - spread;
        int z = center.getZ() + level.getRandom().nextInt(spread * 2 + 1) - spread;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    // ---------- 屬性（技能也會用到，所以是 public） ----------

    /** 把 MobDef 的數值套到實體上、把血補滿，並標記成不會自然消失。 */
    public static void applyStats(LivingEntity living, MobDef def) {
        // 對戰用的怪（含分身）不能被自然消失機制清掉——沒有玩家在附近時原版會直接把牠們移除，
        // 突發事件會變成隨機失效
        if (living instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        setMaxHealth(living, (float) def.health());
        set(living, Attributes.ATTACK_DAMAGE, def.attackDamage());
        set(living, Attributes.MOVEMENT_SPEED, def.movementSpeed());
        setScale(living, def.scale());
        // 血量上限改完要補滿，否則實體會維持原本型別的血量（可能只有新上限的一小截）
        living.setHealth(living.getMaxHealth());
    }

    public static void setMaxHealth(LivingEntity living, float maxHealth) {
        set(living, Attributes.MAX_HEALTH, maxHealth);
    }

    public static void setScale(LivingEntity living, double scale) {
        set(living, Attributes.SCALE, scale);
    }

    private static void set(LivingEntity living, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = living.getAttribute(attribute);
        // 不是每種實體都有每一條屬性（例如盔甲座沒有攻擊力），沒有就跳過
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
