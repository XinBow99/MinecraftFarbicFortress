package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一間商店：標題 + 一排商品。對應 shops.yml 裡的一個區段。 */
public record ShopDef(String id, String title, List<ShopEntry> entries) {

    @SuppressWarnings("unchecked")
    public static ShopDef from(String id, Map<String, Object> section) {
        List<ShopEntry> entries = new ArrayList<>();

        Object rawEntries = section.get("entries");
        if (rawEntries instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> child) {
                    entries.add(ShopEntry.from(String.valueOf(e.getKey()),
                            new LinkedHashMap<>((Map<String, Object>) child)));
                }
            }
        }

        return new ShopDef(id, YamlConfig.str(section, "title", id), List.copyOf(entries));
    }
}
