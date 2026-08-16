package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.weapon.KillCredit;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 死亡訊息改成講「被哪一發打死的」。
 *
 * <p>掛在 {@link CombatTracker#getDeathMessage()} 而不是 {@code ServerPlayer.die}：那是**唯一**
 * 生成死亡訊息的地方，聊天室廣播與死者自己的死亡畫面都問它，所以換一個點就兩邊都對得起來。
 *
 * <p>只有「這一發就是致命傷」才接手，其餘全部原樣交還原版——摔死就是摔死。判斷寫在
 * {@link KillCredit} 裡。
 */
@Mixin(CombatTracker.class)
public abstract class CombatTrackerMixin {

    @Shadow
    @Final
    private LivingEntity mob;

    @Inject(method = "getDeathMessage", at = @At("HEAD"), cancellable = true)
    private void fortressduel$ammoDeathMessage(CallbackInfoReturnable<Component> cir) {
        Component message = KillCredit.deathMessage(mob);
        if (message != null) {
            cir.setReturnValue(message);
        }
    }
}
