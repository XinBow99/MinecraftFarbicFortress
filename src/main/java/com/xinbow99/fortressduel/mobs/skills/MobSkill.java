package com.xinbow99.fortressduel.mobs.skills;

/**
 * 一種技能的實作。一個實作對應 skills.yml 的一個 {@code type}。
 *
 * <p>加新技能只要寫一個這個介面的實作、在 {@link SkillTypes} 註冊，然後就能在 skills.yml 用了——
 * 觸發時機、機率、冷卻都由框架處理，實作只管「發動時做什麼」。
 */
@FunctionalInterface
public interface MobSkill {

    /**
     * 發動。
     *
     * @return true ＝ 真的發動了（會開始算冷卻）；false ＝ 條件不足沒做事（不進冷卻）
     */
    boolean cast(SkillContext ctx);
}
