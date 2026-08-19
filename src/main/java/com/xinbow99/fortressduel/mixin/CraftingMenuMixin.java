package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.craft.CraftingBench;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 工作台上的彈藥組合：格子一變動就重算結果格。
 *
 * <p>掛在 {@code TAIL} 而不是 {@code HEAD}：原版先照自己的配方表算一遍，我們**只在它算不出
 * 東西時**才填進去。這樣既有的原版配方全部不受影響，玩家在對戰之外照常合成。
 *
 * <p>代價是有兩條原版配方會被蓋掉：9 個鐵粒（霰粒）本來能合成鐵錠、9 個銅錠（校準環）
 * 本來能合成銅磚。那兩條在這個遊戲裡都用不到——建材是用買的、挖東西也不掉落——
 * 而讓「九個同樣的材料」變成一份純專精設計，比留著那兩條配方重要得多。
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin extends AbstractCraftingMenu {

    protected CraftingMenuMixin(net.minecraft.world.inventory.MenuType<?> type, int id,
                                int width, int height) {
        super(type, id, width, height);
    }

    @Inject(method = "slotsChanged", at = @At("TAIL"))
    private void fortressduel$offerDesign(Container container, CallbackInfo ci) {
        CraftingContainer grid = this.craftSlots;
        ResultContainer result = this.resultSlots;

        // 扣材料扣到一半，格子還不是最後的樣子，這時候算出來的東西沒有意義
        if (CraftingBench.busy()) return;

        // 原版已經算出東西了就不要插手——那是一條真正的原版配方
        if (!result.getItem(0).isEmpty()) {
            CraftingBench.rememberResult(grid, false);
            return;
        }

        CraftingBench.Offer offer = CraftingBench.offerFor(grid);
        if (offer.problem() != null) {
            // 認得的東西但組不起來。這一定要講：在畫面上「不能組」跟「壞掉了」是同一個樣子
            CraftingBench.rememberResult(grid, false);
            CraftingBench.explain(this.owner(), offer.problem());
            return;
        }
        if (offer.result().isEmpty()) {
            CraftingBench.rememberResult(grid, false);
            return;                             // 原版的合成，安靜放行
        }

        CraftingBench.forgetProblem(this.owner());
        // 「這一份是我們的」只在這裡決定一次。等到玩家拿走才回頭推，就會踩到
        // shift 取物把產物搬空、身分跟著消失的那個坑（見 CraftingBench.authored）
        CraftingBench.rememberResult(grid, true);
        result.setItem(0, offer.result());
        // 結果是我們事後塞的，原版那一輪的同步已經跑完了，不自己送一次客戶端看不到
        this.broadcastChanges();
    }
}
