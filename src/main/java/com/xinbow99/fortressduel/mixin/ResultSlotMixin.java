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
 * 而我們**沒有註冊任何配方**——那條路對這份產物是走不通的。所以整段接管掉，
 * 自己做原版合成本來就會做的那件事：**每一格各消耗 1 個**。
 *
 * <p>「每格只扣 1 個」正是這套組合守恆的來源：一次合成最多吃掉 9 個材料、產出一份設計，
 * 而那份設計記著的就是那 9 個。想要更大的東西就得把原型再丟回去合一次。
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void fortressduel$consumeMaterials(Player player, ItemStack stack, CallbackInfo ci) {
        if (!CraftingBench.isOurs(stack)) return;

        CraftingBench.consume(craftSlots);
        CraftingBench.hintOnce(player);
        ci.cancel();
    }
}
