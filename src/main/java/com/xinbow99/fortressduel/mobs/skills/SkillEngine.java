package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 技能的執行引擎：誰身上掛了哪些技能、什麼時候發動、冷卻好了沒。
 *
 * <p>技能本身（做什麼）在 {@link SkillTypes}，設定（怎麼發動）在 skills.yml，這裡只負責把兩者接起來。
 *
 * <p>掛著技能的怪記在一張以 UUID 為鍵的表裡，而不是寫進實體的 NBT——這些怪只活在一場對戰之內，
 * 對戰結束或伺服器重啟就沒有意義了，持久化反而會留下一堆孤兒資料。代價是伺服器重啟後
 * 場上殘留的怪會退化成普通原版怪，這在對戰情境下可以接受。
 */
public final class SkillEngine {

    /** 分身的分身的分身……的上限。超過就不再連鎖，避免一場對戰被指數增殖打爆。 */
    private static final int MAX_DEPTH = 2;

    private final ConfigManager config;
    private final Map<String, MobSkill> types = new HashMap<>();
    private final Map<UUID, Tracked> tracked = new HashMap<>();

    /**
     * 對戰的總管。技能要拆方塊時得先問「這一格屬於哪一場、拆不拆得動」——那是對戰的規則，
     * 不是怪物的（見 {@code break_blocks}）。
     */
    private final DuelManager duels;

    public SkillEngine(ConfigManager config, DuelManager duels) {
        this.config = config;
        this.duels = duels;
        SkillTypes.registerBuiltins(this);
    }

    public DuelManager duels() {
        return duels;
    }

    /** 一隻被追蹤的怪：牠的設定、連鎖深度、各技能的冷卻與「只觸發一次」的記錄。 */
    private static final class Tracked {
        final MobDef def;
        final int depth;
        final Map<String, Integer> cooldowns = new HashMap<>();
        final Map<String, Integer> intervals = new HashMap<>();
        final Set<String> firedOnce = new HashSet<>();

        Tracked(MobDef def, int depth) {
            this.def = def;
            this.depth = depth;
        }
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, dealt, taken, blocked) -> onDamaged(entity, source));
        ServerLivingEntityEvents.AFTER_DEATH.register(this::onDeath);
    }

    /** 註冊一種技能實作。type 就是 skills.yml 裡的 {@code type} 欄位。 */
    public void registerType(String type, MobSkill skill) {
        if (types.putIfAbsent(type, skill) != null) {
            FortressDuel.LOGGER.warn("Skill type {} was registered twice, the later registration is ignored", type);
        }
    }

    // ---------- 追蹤 ----------

    /**
     * 生成怪物之後呼叫，把牠登記進來並掛上技能。
     *
     * <p>沒有技能的怪也要登記——這張表同時是「這隻實體是哪一個 MobDef」的唯一來源，
     * 經濟系統要靠它決定賞金（見 {@link #definitionOf}）。
     */
    public void track(LivingEntity entity, MobDef def, int depth) {
        tracked.put(entity.getUUID(), new Tracked(def, depth));
        if (!def.skills().isEmpty()) {
            fire(SkillTrigger.ON_SPAWN, entity, null);
        }
    }

    /** 這隻實體是從哪個 MobDef 生出來的；不是本 mod 生成的原版生物回傳 null。 */
    public MobDef definitionOf(LivingEntity entity) {
        Tracked state = tracked.get(entity.getUUID());
        return state == null ? null : state.def;
    }

    public void untrack(LivingEntity entity) {
        tracked.remove(entity.getUUID());
    }

    public int trackedCount() {
        return tracked.size();
    }

    // ---------- 觸發 ----------

    private void onServerTick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Tracked>> it = tracked.entrySet().iterator();
        // 一邊走一邊可能會有新的分身被加進來，所以先收集要觸發的對象再統一發動
        List<LivingEntity> intervalDue = new ArrayList<>();

        while (it.hasNext()) {
            Map.Entry<UUID, Tracked> entry = it.next();
            LivingEntity entity = findEntity(server, entry.getKey());
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }

            Tracked state = entry.getValue();
            state.cooldowns.replaceAll((id, ticks) -> ticks - 1);
            state.cooldowns.values().removeIf(ticks -> ticks <= 0);

            for (SkillDef skill : skillsOf(state.def)) {
                if (skill.trigger() != SkillTrigger.INTERVAL) continue;
                int remaining = state.intervals.merge(skill.id(), -1, Integer::sum);
                if (remaining <= 0) {
                    state.intervals.put(skill.id(), skill.intervalTicks());
                    intervalDue.add(entity);
                }
            }
        }

        for (LivingEntity entity : intervalDue) {
            fire(SkillTrigger.INTERVAL, entity, null);
        }
    }

    private void onDamaged(LivingEntity entity, DamageSource source) {
        if (!tracked.containsKey(entity.getUUID())) return;

        LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
        fire(SkillTrigger.ON_DAMAGED, entity, attacker);
        fire(SkillTrigger.ON_LOW_HEALTH, entity, attacker);
    }

    private void onDeath(LivingEntity entity, DamageSource source) {
        if (!tracked.containsKey(entity.getUUID())) return;

        LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
        fire(SkillTrigger.ON_DEATH, entity, attacker);
        // 這裡**不**移除登記：賞金結算也掛在 AFTER_DEATH 上，先跑到的那個把記錄刪掉的話，
        // 另一個就查不到 MobDef 了。交給 tick 迴圈下一 tick 清掉死掉的實體，
        // 兩邊誰先誰後都不影響
    }

    /** 把某個觸發時機下、這隻怪身上所有符合條件的技能跑一次。 */
    private void fire(SkillTrigger trigger, LivingEntity entity, LivingEntity attacker) {
        Tracked state = tracked.get(entity.getUUID());
        if (state == null || !(entity.level() instanceof ServerLevel level)) return;

        for (SkillDef skill : skillsOf(state.def)) {
            if (skill.trigger() != trigger) continue;
            if (state.cooldowns.containsKey(skill.id())) continue;

            // 一輩子只一次。殘血技能永遠算在內——殘血是持續狀態，不鎖的話每挨一下就再觸發
            if ((skill.once() || trigger == SkillTrigger.ON_LOW_HEALTH)
                    && state.firedOnce.contains(skill.id())) {
                continue;
            }
            if (trigger == SkillTrigger.ON_LOW_HEALTH
                    && entity.getHealth() > entity.getMaxHealth() * skill.healthThreshold()) {
                continue;
            }

            if (skill.chance() < 1.0 && level.getRandom().nextDouble() > skill.chance()) continue;

            MobSkill impl = types.get(skill.type());
            if (impl == null) {
                FortressDuel.LOGGER.warn("Skill {} uses type '{}' which has no implementation", skill.id(), skill.type());
                continue;
            }

            SkillContext ctx = new SkillContext(level, entity, state.def, skill, attacker, state.depth);
            if (!impl.cast(ctx)) continue;

            state.firedOnce.add(skill.id());
            if (skill.cooldownTicks() > 0) {
                state.cooldowns.put(skill.id(), skill.cooldownTicks());
            }
            announce(level, entity, skill);
        }
    }

    private void announce(ServerLevel level, LivingEntity caster, SkillDef skill) {
        if (skill.message().isEmpty()) return;

        Component text = Component.literal(skill.message());
        // 只講給看得到的人聽：技能是場上的事件，不該洗到整個伺服器的聊天欄
        level.getPlayers(player -> player.distanceToSqr(caster) < 64 * 64)
                .forEach(player -> player.sendSystemMessage(text));
    }

    // ---------- 工具 ----------

    /**
     * 這隻怪身上實際掛得起來的技能（設定裡寫錯 id 的會被濾掉並留下一行 log）。
     *
     * <p>結果快取起來：這個方法在 tick 迴圈裡對**每一隻**怪都會呼叫一次，不快取的話
     * 每 tick 每隻怪都會配一個新的 List 再逐個查表。場上四十隻怪就是每秒八百次無謂的配置。
     *
     * <p>快取的鍵是 {@link MobDef} 物件本身，而 {@code /duel reload} 會整個換掉那些物件，
     * 所以重讀設定之後舊的 entry 只是變成垃圾，不會回傳過期的技能。用 WeakHashMap
     * 讓它們跟著被回收。
     */
    private final Map<MobDef, List<SkillDef>> skillCache = new java.util.WeakHashMap<>();

    private List<SkillDef> skillsOf(MobDef def) {
        return skillCache.computeIfAbsent(def, this::resolveSkills);
    }

    private List<SkillDef> resolveSkills(MobDef def) {
        List<SkillDef> out = new ArrayList<>(def.skills().size());
        for (String id : def.skills()) {
            SkillDef skill = config.skills().byId(id);
            if (skill == null) {
                FortressDuel.LOGGER.warn("Mob {} references skill '{}' which is not defined in skills.yml", def.id(), id);
                continue;
            }
            out.add(skill);
        }
        return List.copyOf(out);
    }

    private LivingEntity findEntity(MinecraftServer server, UUID id) {
        // getEntityInAnyDimension 已經會掃過所有維度，所以拿主世界當入口就夠了
        return server.overworld().getEntityInAnyDimension(id) instanceof LivingEntity living ? living : null;
    }

    /** 分身這類會生出新怪的技能用這個判斷還能不能再連鎖。 */
    public boolean canChain(SkillContext ctx) {
        return ctx.depth() < MAX_DEPTH;
    }

    public ConfigManager config() {
        return config;
    }
}
