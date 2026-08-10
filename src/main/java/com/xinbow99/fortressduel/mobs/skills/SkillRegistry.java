package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** skills.yml 讀出來的技能表。怪物的 {@code skills:} 欄位就是在指這裡的 id。 */
public final class SkillRegistry {

    private volatile Map<String, SkillDef> byId = Map.of();

    public void load(YamlConfig cfg) {
        Map<String, SkillDef> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("skills").entrySet()) {
            SkillDef def = SkillDef.from(e.getKey(), e.getValue());
            ids.put(def.id(), def);
        }
        this.byId = Map.copyOf(ids);
    }

    public SkillDef byId(String id) {
        return byId.get(id);
    }

    public Collection<SkillDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
