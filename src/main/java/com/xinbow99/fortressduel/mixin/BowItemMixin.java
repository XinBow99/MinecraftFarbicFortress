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
 * 讓原版的弓變成本 mod 的發射器。
 *
 * <p>這是專案唯一一個真正在用的 mixin，也因此是 MC 版本更新時最容易斷的地方。它依賴
 * {@link BowItem} 的兩個方法簽名（對 26.2 用 javap 確認過）：
 * <ul>
 *   <li>{@code InteractionResult use(Level, Player, InteractionHand)}</li>
 *   <li>{@code boolean releaseUsing(ItemStack, Level, LivingEntity, int)}</li>
 * </ul>
 * 升版之後如果 Mixin 報「target method not found」，先來這裡對簽名。
 *
 * <p><b>兩個注入點都嚴格限定在「對戰中、副手有彈藥」的玩家身上</b>（{@link BowHooks#isArmedBow}）。
 * 織進去的是原版的弓，不設限的話整個伺服器的弓都會被接管——沒在對戰的人連射箭都射不出去。
 *
 * <p>只在伺服器端做事：客戶端一律放行，讓它照原樣播拉弓動畫——那個動畫正是我們要的，
 * 也是唯一不用改客戶端就能拿到的力道回饋。
 */
@Mixin(BowItem.class)
public class BowItemMixin {

    /**
     * 沒有箭也要能拉弓。
     *
     * <p>原版 {@code use} 會先問 {@code getProjectile} 有沒有箭，沒有就直接回 FAIL、
     * 連拉弓狀態都不會進入。我們的彈藥是副手那一疊自訂彈藥，不是原版的箭，所以這一關要繞過去。
     *
     * <p>回傳 CONSUME 同時也擋掉了副手的原版行為：原版是「主手用掉了就不再試副手」，
     * 所以終界之眼不會被丟出去、煙火不會被射掉、TNT 不會被放下——那正是我們要的。
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void fortressDuel$allowDrawWithoutArrows(Level level, Player player, InteractionHand hand,
                                                     CallbackInfoReturnable<InteractionResult> cir) {
        if (!BowHooks.isArmedBow(player)) return;

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
        if (!BowHooks.isArmedBow(player)) return;

        // 原版把「還剩多久」交給我們，拉了多久要自己減
        int used = stack.getUseDuration(entity) - remainingTicks;
        BowHooks.release(player, used);
        cir.setReturnValue(true);
    }
}
