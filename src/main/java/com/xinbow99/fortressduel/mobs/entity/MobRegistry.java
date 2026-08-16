package com.xinbow99.fortressduel.mobs.entity;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.RandomSource;

/** mobs.yml 讀出來的怪物表。 */
public final class MobRegistry {

    private volatile Map<String, MobDef> byId = Map.of();

    /**
     * 一場對戰的場上最多同時幾隻怪；0 ＝ 不限制。見 {@code MobSpawner.enforceCap}。
     *
     * <p>放在 mobs.yml 而不是 incidents.yml：突發事件只是來源之一，寶貝蛋與會召喚、
     * 會分裂的技能同樣在加怪，而它們吃的是同一份效能預算。
     */
    private volatile int maxAlive = 30;

    public int maxAlive() {
        return maxAlive;
    }

    public void load(YamlConfig cfg) {
        this.maxAlive = Math.max(0, cfg.getInt("limits.max_alive", 30));

        Map<String, MobDef> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("mobs").entrySet()) {
            MobDef def = MobDef.from(e.getKey(), e.getValue());
            ids.put(def.id(), def);
        }
        this.byId = Map.copyOf(ids);
    }

    public MobDef byId(String id) {
        return byId.get(id);
    }

    public Collection<MobDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    /** 依權重抽一種怪。全部權重都是 0（或表是空的）時回傳 null。 */
    public MobDef randomWeighted(RandomSource random) {
        List<MobDef> pool = byId.values().stream().filter(m -> m.weight() > 0).toList();
        double total = pool.stream().mapToDouble(MobDef::weight).sum();
        if (total <= 0) return null;

        double roll = random.nextDouble() * total;
        for (MobDef def : pool) {
            roll -= def.weight();
            if (roll <= 0) return def;
        }
        return pool.getLast();
    }
}
