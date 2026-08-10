package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Map;

/**
 * 一個技能的設定，對應 skills.yml 裡的一個區段。
 *
 * <p>{@code type} 決定「做什麼」（對應 {@link SkillTypes} 註冊的實作），其餘欄位決定「什麼時候做、
 * 多久能做一次」。同一個 type 可以被好幾個技能用不同參數再包一層——例如 {@code clone} 型別可以
 * 同時存在「被打時分身 2 隻」與「殘血時分身 4 隻」兩個技能。
 *
 * <p>{@code params} 是留給各 type 自己解讀的自由欄位，所以加新技能只要在 {@link SkillTypes}
 * 註冊一個實作，不用動這個 record。
 */
public record SkillDef(
        String id,
        String displayName,
        String type,
        SkillTrigger trigger,
        /** 觸發時真正發動的機率 0~1。 */
        double chance,
        /** 冷卻（秒）；同一隻怪在冷卻期間不會再發動這個技能。 */
        double cooldownSeconds,
        /** trigger = INTERVAL 時，每隔幾秒檢查一次。 */
        double intervalSeconds,
        /** trigger = ON_LOW_HEALTH 時的血量門檻（佔血量上限的比例）。 */
        double healthThreshold,
        /** 發動時給雙方看的訊息；空字串 ＝ 不公告。 */
        String message,
        /** 各 type 自己解讀的參數。 */
        Map<String, Object> params
) {

    @SuppressWarnings("unchecked")
    public static SkillDef from(String id, Map<String, Object> section) {
        Object rawParams = section.get("params");
        Map<String, Object> params = rawParams instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        return new SkillDef(
                id,
                YamlConfig.str(section, "name", id),
                YamlConfig.str(section, "type", id),
                SkillTrigger.parse(YamlConfig.str(section, "trigger", "ON_DAMAGED")),
                YamlConfig.d(section, "chance", 1.0),
                YamlConfig.d(section, "cooldown_seconds", 0.0),
                YamlConfig.d(section, "interval_seconds", 10.0),
                YamlConfig.d(section, "health_threshold", 0.3),
                YamlConfig.str(section, "message", ""),
                params);
    }

    public int cooldownTicks() {
        return (int) Math.round(cooldownSeconds * 20);
    }

    public int intervalTicks() {
        return Math.max(1, (int) Math.round(intervalSeconds * 20));
    }

    // ---------- params 取值 ----------

    public int param(String key, int def) {
        return YamlConfig.i(params, key, def);
    }

    public double param(String key, double def) {
        return YamlConfig.d(params, key, def);
    }

    public boolean param(String key, boolean def) {
        return YamlConfig.bool(params, key, def);
    }

    public String param(String key, String def) {
        return YamlConfig.str(params, key, def);
    }
}
