package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** weapons.yml 讀出來的武器表。以 id 查，也可以用手上的物品反查。 */
public final class WeaponRegistry {

    private volatile Map<String, WeaponDef> byId = Map.of();
    private volatile Map<Identifier, WeaponDef> byItem = Map.of();

    public void load(YamlConfig cfg) {
        Map<String, WeaponDef> ids = new LinkedHashMap<>();
        Map<Identifier, WeaponDef> items = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("weapons").entrySet()) {
            WeaponDef def = WeaponDef.from(e.getKey(), e.getValue());
            ids.put(def.id(), def);
            // 一個物品只能綁一種武器：兩個定義搶同一個物品的話，後面的會蓋掉前面的，
            // 這種通常是複製貼上忘了改，所以要講出來
            WeaponDef previous = items.put(def.item(), def);
            if (previous != null) {
                FortressDuel.LOGGER.warn("Weapons {} and {} are bound to the same item {}; only {} will work",
                        previous.id(), def.id(), def.item(), def.id());
            }
        }

        this.byId = Map.copyOf(ids);
        this.byItem = Map.copyOf(items);
    }

    public WeaponDef byId(String id) {
        return byId.get(id);
    }

    public WeaponDef byItem(Identifier item) {
        return byItem.get(item);
    }

    public Collection<WeaponDef> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
