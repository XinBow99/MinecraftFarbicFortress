package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.weapon.BowHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 讓原版的弓變成本 mod 的蓄力發射器。
 *
 * <p>這是專案唯一一個真正在用的 mixin，也因此是 MC 版本更新時最容易斷的地方。它依賴
 * {@link BowItem} 的兩個方法簽名（對 26.2 用 javap 確認過）：
 * <ul>
 *   <li>{@code InteractionResult use(Level, Player, InteractionHand)}</li>
 *   <li>{@code boolean releaseUsing(ItemStack, Level, LivingEntity, int)}</li>
 * </ul>
 * 升版之後如果 Mixin 報「target method not found」，先來這裡對簽名。
 *
 * <p>只在伺服器端做事：{@code level.isClientSide} 為真時一律放行，讓客戶端照原樣播它的
 * 拉弓動畫——那個動畫正是我們要的，也是唯一不用改客戶端就能拿到的力道回饋。
 */
@Mixin(BowItem.class)
public class BowItemMixin {

    /**
     * 沒有箭也要能拉弓。
     *
     * <p>原版 {@code use} 會先問 {@code getProjectile} 有沒有箭，沒有就直接回 FAIL、
     * 連拉弓狀態都不會進入。我們的彈藥是自己的彈藥袋（{@code AmmoPouch}），跟背包裡有沒有
     * 箭無關，所以這一關要繞過去。
     *
     * <p>彈藥夠不夠是**放開的時候**才檢查的，不是這裡：拉到一半發現沒子彈而中斷，
     * 比拉不動更難理解發生了什麼事。
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void fortressDuel$allowDrawWithoutArrows(Level level, Player player, InteractionHand hand,
                                                     CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = player.getItemInHand(hand);
        if (!BowHooks.isChargedWeapon(stack)) return;

        player.startUsingItem(hand);
        cir.setReturnValue(InteractionResult.CONSUME);
    }

    /**
     * 放開右鍵 → 打出我們自己的彈丸，而不是原版的箭。
     *
     * <p>一定要取消原版的後續：不取消的話它會照樣生成一支箭實體（或因為找不到箭而丟掉這一發），
     * 兩種都不是我們要的。
     */
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void fortressDuel$fireOnRelease(ItemStack stack, Level level, LivingEntity entity,
                                            int remainingTicks, CallbackInfoReturnable<Boolean> cir) {
        // ServerPlayer 只存在於伺服器端，所以這一個判斷同時也擋掉了客戶端——
        // 客戶端要照原樣跑完它的拉弓動畫，那正是我們要的力道回饋
        if (!(entity instanceof ServerPlayer player)) return;
        if (!BowHooks.isChargedWeapon(stack)) return;

        // 原版把「還剩多久」交給我們，拉了多久要自己減
        int used = stack.getUseDuration(entity) - remainingTicks;
        BowHooks.release(player, stack, used);
        cir.setReturnValue(true);
    }
}
