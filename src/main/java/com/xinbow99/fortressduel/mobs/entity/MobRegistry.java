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

    public void load(YamlConfig cfg) {
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
