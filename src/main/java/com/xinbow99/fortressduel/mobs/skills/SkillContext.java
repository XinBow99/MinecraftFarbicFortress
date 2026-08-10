package com.xinbow99.fortressduel.mobs.skills;

import com.xinbow99.fortressduel.mobs.entity.MobDef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * 一次技能發動的現場資訊。
 *
 * @param caster    施放技能的怪
 * @param def       牠的怪物設定
 * @param skill     正在發動的技能
 * @param attacker  觸發這次發動的攻擊者（只有 ON_DAMAGED / ON_DEATH 會有，其餘是 null）
 * @param depth     連鎖深度。分身出來的怪再分身時會 +1，用來擋住無限增殖
 */
public record SkillContext(
        ServerLevel level,
        LivingEntity caster,
        MobDef def,
        SkillDef skill,
        LivingEntity attacker,
        int depth
) {
    public SkillContext deeper() {
        return new SkillContext(level, caster, def, skill, attacker, depth + 1);
    }
}
