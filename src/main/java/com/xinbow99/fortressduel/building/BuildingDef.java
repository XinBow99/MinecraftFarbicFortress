package com.xinbow99.fortressduel.building;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一棟建築的藍圖，對應 buildings.yml 裡的一個區段。
 *
 * <p>用「調色盤 + 逐層字元圖」描述，跟很多主流插件的 schematic 寫法一樣：
 * <pre>
 * palette:
 *   S: minecraft:stone_bricks
 *   .: minecraft:air
 * layers:                # 由下往上，一層一個 y
 *   - ["SSSSS",          # 一列一個 z（由北到南），一個字元一個 x（由西到東）
 *      "S...S",
 *      "SSSSS"]
 * </pre>
 *
 * <p>字元不在調色盤裡就當成「不要動這一格」——所以可以只描述牆而讓地形穿過去。
 */
public record BuildingDef(
        String id,
        String displayName,
        /** 相對於自己核心的位置。 */
        int offsetX, int offsetY, int offsetZ,
        Map<Character, String> palette,
        List<List<String>> layers,
        /** 要放在裡面的 NPC id（對應 npcs.yml），空字串 ＝ 不放。 */
        String npc,
        int npcOffsetX, int npcOffsetY, int npcOffsetZ
) {

    public static BuildingDef from(String id, Map<String, Object> section) {
        Map<Character, String> palette = new LinkedHashMap<>();
        if (section.get("palette") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.length() != 1) {
                    FortressDuel.LOGGER.warn("建築 {} 的調色盤鍵 '{}' 不是單一字元，已略過", id, key);
                    continue;
                }
                palette.put(key.charAt(0), String.valueOf(e.getValue()));
            }
        }

        List<List<String>> layers = new ArrayList<>();
        if (section.get("layers") instanceof List<?> rawLayers) {
            for (Object rawLayer : rawLayers) {
                if (rawLayer instanceof List<?> rows) {
                    layers.add(rows.stream().map(String::valueOf).toList());
                }
            }
        }

        Map<String, Object> offset = child(section, "offset");
        Map<String, Object> npcOffset = child(section, "npc_offset");

        return new BuildingDef(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.i(offset, "x", 0),
                YamlConfig.i(offset, "y", 0),
                YamlConfig.i(offset, "z", 0),
                Map.copyOf(palette),
                List.copyOf(layers),
                YamlConfig.str(section, "npc", ""),
                YamlConfig.i(npcOffset, "x", 0),
                YamlConfig.i(npcOffset, "y", 1),
                YamlConfig.i(npcOffset, "z", 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> section, String key) {
        return section.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
