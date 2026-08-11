package com.xinbow99.fortressduel.mixin;

import com.xinbow99.fortressduel.weapon.BowHooks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 對戰中的玩家死掉不掉落背包。
 *
 * <p>彈藥改成實物之後，死亡會把整背包（弓、彈藥、建材）灑在地上。加上中彈扣錢，被打死的
 * 代價會疊成兩層：掉錢 ＋ 要走回去撿東西。這個遊戲的懲罰應該是**經濟上的**，不是物流上的，
 * 所以對戰中一律不掉落——跟「錢是虛擬的」是同一個立場。
 *
 * <p>攔 {@link Inventory#dropAll} 而不是改 {@code keepInventory} 遊戲規則：後者是整個世界的
 * 設定，會連沒在對戰的人也一起改掉。
 */
@Mixin(Inventory.class)
public class InventoryDropMixin {

    @Shadow
    @Final
    public Player player;

    @Inject(method = "dropAll", at = @At("HEAD"), cancellable = true)
    private void fortressDuel$keepInventoryInDuel(CallbackInfo ci) {
        if (BowHooks.isInDuel(player)) {
            ci.cancel();
        }
    }
}
