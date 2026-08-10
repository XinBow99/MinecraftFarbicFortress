package com.xinbow99.fortressduel.incident;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.RandomSource;

/** incidents.yml 讀出來的突發事件表。 */
public final class IncidentRegistry {

    private volatile Map<String, IncidentDef> byId = Map.of();

    public void load(YamlConfig cfg) {
        Map<String, IncidentDef> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("incidents").entrySet()) {
            IncidentDef def = IncidentDef.from(e.getKey(), e.getValue());
            ids.put(def.id(), def);
        }
        this.byId = Map.copyOf(ids);
    }

    public IncidentDef byId(String id) {
        return byId.get(id);
    }

    public Collection<IncidentDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    /** 依權重抽一個事件；沒有任何可抽的事件時回傳 null。 */
    public IncidentDef randomWeighted(RandomSource random) {
        List<IncidentDef> pool = byId.values().stream().filter(i -> i.weight() > 0).toList();
        double total = pool.stream().mapToDouble(IncidentDef::weight).sum();
        if (total <= 0) return null;

        double roll = random.nextDouble() * total;
        for (IncidentDef def : pool) {
            roll -= def.weight();
            if (roll <= 0) return def;
        }
        return pool.getLast();
    }
}
