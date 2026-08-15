package com.xinbow99.fortressduel.jobs;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一種工作節點（礦脈、稻田），對應 jobs.yml 的 {@code nodes:} 底下一個區段。
 *
 * <p>節點長什麼樣子**不在這裡**——那是 buildings.yml 的一張藍圖，這裡只記它的 id。
 * 所以把稻田從一格小麥改成 5×5 的水田，或把礦脈換成 26.2 的朱砂礦，都不用碰 Java。
 */
public record NodeDef(
        String id,
        String displayName,
        /** 用 buildings.yml 的哪一張藍圖擺出來。 */
        String building,
        /**
         * 哪些方塊算「可以採的」。
         *
         * <p>它同時是**枯竭判定的依據**：每次工作前重數一次節點範圍內還剩幾格屬於這一組，
         * 歸零就代表這個節點結束了。玩家慢慢採光與對手一發轟平因此是同一條路徑，
         * 不需要各寫一套。
         *
         * <p>也因此藍圖裡的裝飾（水源、耕地、石頭外框）不該列進來——那些被打掉不算損失。
         */
        Set<String> harvestable,
        /** 每一輪停火階段開始時，替每一方補幾個。 */
        int perRound,
        /** 每一方場上最多同時存在幾個。補到上限就不再補。 */
        int max,
        /** 離自己的熊貓圈至少／最多幾格。太近＝縮在家裡就有錢，太遠＝工人送死。 */
        int minDistance,
        int maxDistance
) {

    public static NodeDef from(String id, Map<String, Object> section) {
        Set<String> harvestable = new LinkedHashSet<>();
        if (section.get("harvestable") instanceof List<?> list) {
            for (Object o : list) {
                harvestable.add(String.valueOf(o));
            }
        }

        int min = Math.max(0, YamlConfig.i(section, "min_distance", 8));
        // max 比 min 小的話下面挑位置的迴圈會永遠找不到落點，而症狀是「節點都不生成」
        // ——那看起來像功能壞了，不像設定寫錯。這裡直接夾住
        int max = Math.max(min + 1, YamlConfig.i(section, "max_distance", 22));

        return new NodeDef(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.str(section, "building", ""),
                Set.copyOf(harvestable),
                Math.max(0, YamlConfig.i(section, "per_round", 1)),
                Math.max(0, YamlConfig.i(section, "max", 4)),
                min,
                max);
    }
}
