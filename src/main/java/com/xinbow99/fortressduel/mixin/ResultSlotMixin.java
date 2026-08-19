package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.craft.CraftingBench;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拿走自製彈藥時自己扣材料。
 *
 * <p>原版的 {@code onTake} 會去問「這是哪一條配方」來決定要留下什麼副產品（桶子、瓶子…），
 * 而我們**沒有註冊任何配方**——那條路對這份產物是走不通的。所以整段接管掉，自己扣材料。
 *
 * <p>吃掉的是**整疊**，跟算的時候「整疊都算進去」是同一條規則——兩邊一起看數量才守恆。
 *
 * <h2>不要用傳進來的 stack 判斷這是不是我們的</h2>
 * <p>shift 取物那條路上，原版是**先把結果格搬進背包、再呼叫這個方法**，所以傳進來的
 * 那一份已經被搬空了；而空堆疊的 component 一律讀成空，設計的身分在那一刻就消失了。
 * 於是判斷會失敗、原版接手每格只扣 1、扣完觸發重算又補一份新設計進結果格，而 shift 的
 * 迴圈只比對物品種類，看到格子又滿了就再跑一輪——複製出一堆設計，背包塞爆之後灑到地上。
 *
 * <p>所以問的是 {@link CraftingBench#resultWasOurs}，它記的是當初放進去時就決定好的答案。
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void fortressduel$consumeMaterials(Player player, ItemStack stack, CallbackInfo ci) {
        if (!CraftingBench.resultWasOurs(craftSlots, stack)) return;

        CraftingBench.consume(craftSlots);
        CraftingBench.hintOnce(player);
        ci.cancel();
    }
}
