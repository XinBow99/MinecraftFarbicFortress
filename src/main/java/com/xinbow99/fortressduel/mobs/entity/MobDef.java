package com.xinbow99.fortressduel.mobs.entity;

import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * 一種怪物的設定，對應 mobs.yml 裡的一個區段。
 *
 * <p>MC 版不自己做實體，而是拿一個原版實體當底（{@code entity}）再套上這裡的數值，
 * 所以新增一種怪不需要寫任何 Java。
 */
public record MobDef(
        String id,
        String displayName,
        /**
         * 這一群可以用哪幾種原版實體當底，**每一隻各自隨機抽一種**。
         *
         * <p>寫成清單而不是單一個值，是為了「混合族群」這種事件：一群裡有兔子、狐狸、
         * 駱駝、北極熊，每隻長得不一樣。只寫一種的話（{@code entity: minecraft:rabbit}）
         * 這裡就是只有一個元素的清單，行為跟以前完全一樣。
         *
         * <p>數值（血量、攻擊、體型）仍然是整群共用的——這裡混的是**外觀與原版行為**，
         * 不是強度。要不同強度就開兩個 MobDef。
         */
        List<Identifier> entities,

        double health,
        double attackDamage,
        double movementSpeed,
        /** 體型倍率，1.0 ＝ 原版大小。 */
        double scale,
        /** 一次降臨幾隻。 */
        int packMin,
        int packMax,
        /** 抽籤權重，越大越常出現；0 ＝ 只能被突發事件指名叫出來。 */
        double weight,
        /** 打死牠給多少錢（對應網頁版的怪物賞金）。 */
        int reward,
        /** 掛在牠身上的技能 id（對應 skills.yml）。 */
        List<String> skills
) {

    public static MobDef from(String id, Map<String, Object> section) {
        int min = Math.max(1, YamlConfig.i(section, "pack_min", 1));
        int max = Math.max(min, YamlConfig.i(section, "pack_max", min));

        Object rawSkills = section.get("skills");
        List<String> skills = rawSkills instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        return new MobDef(
                id,
                YamlConfig.str(section, "name", id),
                entities(section),
                YamlConfig.d(section, "health", 20.0),
                YamlConfig.d(section, "attack_damage", 3.0),
                YamlConfig.d(section, "movement_speed", 0.25),
                YamlConfig.d(section, "scale", 1.0),
                min,
                max,
                YamlConfig.d(section, "weight", 1.0),
                YamlConfig.i(section, "reward", 0),
                skills);
    }

    /**
     * 讀 {@code entities:}（清單）或 {@code entity:}（單一個）。兩個都寫的話清單優先。
     *
     * <p>兩種寫法並存而不是只留清單：絕大多數的怪就是一種實體，逼它們寫成單元素清單
     * 只會讓 mobs.yml 變吵。
     */
    private static List<Identifier> entities(Map<String, Object> section) {
        if (section.get("entities") instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(raw -> Identifier.parse(String.valueOf(raw))).toList();
        }
        return List.of(Identifier.parse(YamlConfig.str(section, "entity", "minecraft:zombie")));
    }

    /** 這一群的代表實體，只有在需要「單一個型別」的地方用（例如錯誤訊息）。 */
    public Identifier entity() {
        return entities.getFirst();
    }
}
