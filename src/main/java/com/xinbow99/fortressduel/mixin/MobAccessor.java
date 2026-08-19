package com.xinbow99.fortressduel.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 讓我們碰得到 {@code Mob} 的兩個目標選擇器。
 *
 * <p>{@code goalSelector} 與 {@code targetSelector} 都是 {@code protected final}，而有些技能
 * 需要**把原版的行為整組拔掉**再自己接管——例如小偷狐狸：牠得追人、搶東西、然後逃跑，
 * 而原版的狐狸會白天睡覺、會躲玩家、會去撿地上的東西、會撲雞。那些行為每一條都在跟
 * 技能搶導航，光靠每秒重下一次路徑是壓不住的。
 *
 * <p>用 {@code @Accessor} 而不是開 access widener：這個專案已經有六個 mixin，多一個存取器
 * 是同一套機制；而 AW 是另一個要記得維護的建置設定，為了一個欄位不值得。
 */
@Mixin(Mob.class)
public interface MobAccessor {

    @Accessor("goalSelector")
    GoalSelector fortressduel$goalSelector();

    @Accessor("targetSelector")
    GoalSelector fortressduel$targetSelector();
}
