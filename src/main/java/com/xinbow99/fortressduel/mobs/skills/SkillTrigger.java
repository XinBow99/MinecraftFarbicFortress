package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.FortressDuel;

import java.util.Locale;

/**
 * 技能什麼時候發動。
 *
 * <p>一個技能只綁一個觸發時機——想要「被打會分身、每 10 秒也會分身」的話，在 skills.yml 寫兩個
 * 技能指向同一個 {@code type} 就好，不要讓單一技能同時掛多個觸發，那會讓冷卻的語意變得說不清楚。
 */
public enum SkillTrigger {
    /** 怪物生成的當下。 */
    ON_SPAWN,
    /** 每隔一段時間（{@code interval_seconds}）。 */
    INTERVAL,
    /** 每次受到傷害之後。 */
    ON_DAMAGED,
    /** 血量第一次掉到 {@code health_threshold} 以下時（整個生命週期只會觸發一次）。 */
    ON_LOW_HEALTH,
    /** 死亡的當下。 */
    ON_DEATH;

    public static SkillTrigger parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            FortressDuel.LOGGER.warn("Unknown skill trigger '{}', falling back to ON_DAMAGED", raw);
            return ON_DAMAGED;
        }
    }
}
