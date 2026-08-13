package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.battle.ArenaGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 爆炸炸不掉競技場的外殼。
 *
 * <p>外殼（四面牆、天花板、地板）的保護原本有三條路，全部走我們自己的程式碼：玩家挖
 * （{@code DuelManager.allowBreak}）、武器打（{@code WeaponSystem.damageBlock}）、
 * 在場外放方塊（{@code onUseBlock}）。**但原版的爆炸完全不經過那些地方**，所以有兩個
 * 現實的破口：高爆彈的彈藥物品就是 TNT，玩家可以主手拿著放在牆邊點掉；隕石雨也可能
 * 剛好落在邊上。
 *
 * <p>攔在 {@code calculateExplodedPositions} 的回傳值上——那是爆炸決定「哪幾格要炸掉」
 * 的地方，把外殼從清單裡濾掉就好。攔在這裡而不是攔整個爆炸：爆炸的其他效果（推力、
 * 對實體的傷害、其他方塊）都該照常發生，被保護的只有那一層殼。
 *
 * <p>沒有對戰進行中時第一個判斷就整個跳過，所以這個 mixin 對平常的爆炸沒有成本。
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {

    @Shadow
    public abstract ServerLevel level();

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void fortressduel$keepArenaShell(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!ArenaGuard.anyActiveDuel()) return;

        List<BlockPos> positions = cir.getReturnValue();
        if (positions == null || positions.isEmpty()) return;

        List<BlockPos> kept = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (!ArenaGuard.isProtected(level(), pos)) {
                kept.add(pos);
            }
        }
        // 一格都沒濾掉就不要換掉回傳值，省一次配置
        if (kept.size() != positions.size()) {
            cir.setReturnValue(kept);
        }
    }
}
