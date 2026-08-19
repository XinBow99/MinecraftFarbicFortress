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
        int npcOffsetX, int npcOffsetY, int npcOffsetZ,
        /**
         * true ＝ 建築師會賣這一張圖紙。
         *
         * <p>價格**不寫在設定裡**，是照藍圖裡每一格的建材單價加總再打折算出來的
         * （見 {@code BlueprintShop}）——手寫的話改一層樓就要記得回頭改價格，
         * 而忘記改不會有任何徵兆。
         */
        boolean sold,
        /**
         * true ＝ 用圖紙蓋的時候跟著玩家的面向轉，讓它正對著他。
         *
         * <p>**只轉字元圖，不轉方塊狀態。** 所以有方向性方塊（梯子、階梯、按鈕…）的藍圖
         * 不能開這個——梯子的 {@code facing=south} 轉完之後會貼在錯的那面牆上，而那個錯誤
         * 只有到現場爬不上去才會發現。
         *
         * <p>圓形或對稱的建築本來就不需要（哈里發塔就是），所以這個欄位真正服務的是
         * 「一片牆」那種明確有正面的東西。
         */
        boolean rotates
) {

    public static BuildingDef from(String id, Map<String, Object> section) {
        Map<Character, String> palette = new LinkedHashMap<>();
        if (section.get("palette") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.length() != 1) {
                    FortressDuel.LOGGER.warn("Building {} has palette key '{}' that is not a single character, skipping it", id, key);
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
                YamlConfig.i(npcOffset, "z", 0),
                YamlConfig.bool(section, "sold", false),
                YamlConfig.bool(section, "rotates", false));
    }

    /**
     * 轉四分之一圈的整數倍之後的樣子。
     *
     * <p>格子圖是 {@code [z][x]}（z 由北往南、x 由西往東），順時針轉一次的對應是
     * {@code 新[z'][x'] = 舊[深度-1-x'][z']}——寬與深會對調。
     *
     * <p>方塊狀態不跟著轉，見 {@link #rotates()}。
     */
    public BuildingDef rotated(int quarterTurns) {
        List<List<String>> current = layers;
        for (int turn = 0; turn < Math.floorMod(quarterTurns, 4); turn++) {
            current = turnOnce(current);
        }
        return new BuildingDef(id, displayName, offsetX, offsetY, offsetZ, palette, current,
                npc, npcOffsetX, npcOffsetY, npcOffsetZ, sold, rotates);
    }

    private static List<List<String>> turnOnce(List<List<String>> layers) {
        List<List<String>> out = new ArrayList<>(layers.size());
        for (List<String> rows : layers) {
            int depth = rows.size();
            int width = rows.stream().mapToInt(String::length).max().orElse(0);

            List<String> turned = new ArrayList<>(width);
            for (int z = 0; z < width; z++) {
                StringBuilder row = new StringBuilder(depth);
                for (int x = 0; x < depth; x++) {
                    String source = rows.get(depth - 1 - x);
                    // 各列不一定等長，短的那幾格當成「不要動」
                    row.append(z < source.length() ? source.charAt(z) : ' ');
                }
                turned.add(row.toString());
            }
            out.add(List.copyOf(turned));
        }
        return List.copyOf(out);
    }

    /**
     * 這張藍圖佔的格數（調色盤裡有定義的才算，空氣與「不要動」的格子不算）。
     *
     * <p>價格靠它算，見 {@code BlueprintShop}。
     */
    public Map<String, Integer> blockCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> rows : layers) {
            for (String row : rows) {
                for (int x = 0; x < row.length(); x++) {
                    String block = palette.get(row.charAt(x));
                    if (block != null) counts.merge(block, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    public int width() {
        return layers.stream().flatMap(List::stream).mapToInt(String::length).max().orElse(0);
    }

    public int depth() {
        return layers.stream().mapToInt(List::size).max().orElse(0);
    }

    public int height() {
        return layers.size();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> section, String key) {
        return section.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
