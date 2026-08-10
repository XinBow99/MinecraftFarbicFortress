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
        Identifier entity,

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
        /** 會不會主動去打玩家的核心（網頁版的難民就是這種）。 */
        boolean attacksCore,
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
                Identifier.parse(YamlConfig.str(section, "entity", "minecraft:zombie")),
                YamlConfig.d(section, "health", 20.0),
                YamlConfig.d(section, "attack_damage", 3.0),
                YamlConfig.d(section, "movement_speed", 0.25),
                YamlConfig.d(section, "scale", 1.0),
                min,
                max,
                YamlConfig.d(section, "weight", 1.0),
                YamlConfig.bool(section, "attacks_core", false),
                YamlConfig.i(section, "reward", 0),
                skills);
    }
}
