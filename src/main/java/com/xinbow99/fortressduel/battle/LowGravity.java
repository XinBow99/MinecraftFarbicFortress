package com.xinbow99.fortressduel.battle;

import com.xinbow99.fortressduel.FortressDuel;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 把「低重力」這個突發事件套到**玩家身上**。
 *
 * <p>在這之前 {@code gravity} 這個修正只寫進彈丸（見 {@code WeaponSystem}），玩家本體走原版物理——
 * 事件叫「低重力」但跳起來跟平常一樣高，那個落差玩家一跳就會發現。
 *
 * <p>走原版屬性而不是每 tick 去改玩家的速度：屬性是伺服器算完同步給客戶端的，所以客戶端的
 * 預測跟得上，跳起來是滑順的；每 tick 改速度則會跟客戶端的預測打架，畫面會抖。
 *
 * <h2>為什麼要連 safe_fall_distance 一起動</h2>
 * 原版的摔傷是算**落下的距離**，不是落地的速度。重力減半之後你跳得高、落得慢，落地那一下
 * 其實很輕，但傷害照原本的高度算——變成「飄起來然後摔死」。所以安全落下距離要跟著放大
 * 同樣的倍數（跳躍高度 h = v²/2g，重力減半高度就是兩倍），這樣「跳一下」的代價才跟平常一樣。
 *
 * <h2>為什麼用 transient</h2>
 * transient 的修正不會寫進玩家存檔。伺服器如果在事件進行中被硬砍掉，玩家不會帶著半個重力
 * 重新上線——那種殘留在單機測試時特別難發現，而且只能用 /attribute 手動清。
 * 這跟空氣牆殘留是同一類的問題，能靠選對 API 避掉就不要靠記得清理。
 */
final class LowGravity {

    private static final Identifier GRAVITY_ID = FortressDuel.id("low_gravity");
    private static final Identifier FALL_ID = FortressDuel.id("low_gravity_fall");

    private LowGravity() {}

    /**
     * 確保這名玩家身上掛著對應 {@code factor} 的低重力。已經掛好了就什麼都不做。
     *
     * <p>每 tick 呼叫是刻意的，這是自我修復的入口：玩家死亡重生或斷線重連之後
     * {@link ServerPlayer} 是一個新物件，transient 修正不會跟過去。檢查成本只是一次 map 查詢，
     * 真正會送封包的 add 只在第一次（或修好的那一次）發生。
     */
    static void sync(ServerPlayer player, double factor) {
        if (factor <= 0 || factor == 1.0) {
            clear(player);
            return;
        }

        // ADD_MULTIPLIED_BASE：amount 是「在基礎值上加幾成」，所以 0.5 倍重力要寫 -0.5
        apply(player, Attributes.GRAVITY, GRAVITY_ID, factor - 1.0);
        // 跳得多高，安全落下距離就放大多少倍（見類別註解）
        apply(player, Attributes.SAFE_FALL_DISTANCE, FALL_ID, 1.0 / factor - 1.0);
    }

    /** 拿掉低重力。事件到期、對戰結束都要呼叫，重複呼叫無害。 */
    static void clear(ServerPlayer player) {
        remove(player, Attributes.GRAVITY, GRAVITY_ID);
        remove(player, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
    }

    private static void apply(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;

        AttributeModifier existing = instance.getModifier(id);
        if (existing != null && existing.amount() == amount) return;   // 已經是對的，不要重送封包

        instance.addOrUpdateTransientModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    private static void remove(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) instance.removeModifier(id);
    }
}
