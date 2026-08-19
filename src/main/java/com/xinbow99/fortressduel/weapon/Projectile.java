package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.battle.Duel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一顆飛行中的彈丸。
 *
 * <p>刻意**不是**一個 MC 實體：自己積分位置、自己做射線檢測。理由有三個——
 * 不必註冊實體型別（客戶端不用裝 mod）、彈道參數（初速／重力／壽命）完全由 YAML 決定而不是
 * 綁死在某個原版彈丸類別上、而且可以在飛行途中一格一格判斷「有沒有飛出競技場」。
 *
 * <p>可變物件，由 {@link WeaponSystem} 每 tick 推進；命中或壽命到就標成 dead 並被移除。
 */
final class Projectile {

    final WeaponDef weapon;
    /**
     * 這一發屬於哪一場對戰；**null ＝ 試射**（{@code /duel testfire}，沒有競技場）。
     *
     * <p>試射的那一發少掉三件事：不受框線限制（改用固定的最大飛行距離收尾）、
     * 打不壞方塊、也不孵怪。那三件事都需要一個能還原的場地，而試射沒有——
     * 在真實世界上留一個永久的洞不是「測試」，是災情。
     */
    final Duel duel;
    /** 彈丸飛在哪個世界。試射沒有競技場可以問，所以自己記著。 */
    final ServerLevel level;
    /** 起點。試射時用來算「飛多遠了」——沒有框線可以收尾，總得有個東西讓它停下來。 */
    final Vec3 origin;
    final UUID shooterId;
    final String shooterName;
    /**
     * 這一發在玩家眼裡叫什麼（死亡訊息用，見 {@link KillCredit}）。
     *
     * <p>不能用 {@code weapon.displayName()} 代替：自製設計可以被玩家取名，而那個名字記在
     * 彈藥物品上、不在 {@link WeaponDef} 裡。打死人的時候該報的是他手上那疊叫什麼。
     */
    final String ammoName;

    Vec3 pos;
    Vec3 velocity;
    int ticksLeft;
    boolean dead;
    /**
     * 傷害倍率，由蓄力程度決定；即發武器恆為 1。
     *
     * <p>記在彈丸上而不是開火時就把數字算進去——傷害還要經過濺射衰減與穿甲折減，
     * 留著倍率才能讓那些計算照原本的順序疊上去。
     */
    final double damageScale;
    /**
     * 開火那一刻的全域修正（低重力、火力全開），見 {@link Duel#modifierFactor}。
     *
     * <p>寫進彈丸而不是每 tick 去問對戰：效果在飛行途中結束時，已經射出去的那一發應該
     * 照它離手時的規則走完。一顆飛到一半忽然開始正常下墜的彈丸，玩家只會覺得是 bug。
     */
    final double gravityScale;
    final double damageBoost;

    Projectile(WeaponDef weapon, Duel duel, ServerLevel level, ServerPlayer shooter, String ammoName,
               Vec3 pos, Vec3 velocity,
               double damageScale, double gravityScale, double damageBoost) {
        this.weapon = weapon;
        this.ammoName = ammoName;
        this.duel = duel;
        this.level = level;
        this.origin = pos;
        this.shooterId = shooter.getUUID();
        this.shooterName = shooter.getGameProfile().name();
        this.pos = pos;
        this.velocity = velocity;
        this.damageScale = damageScale;
        this.gravityScale = gravityScale;
        this.damageBoost = damageBoost;
        this.ticksLeft = weapon.lifetimeTicks();
    }

    /** 這一發的實際傷害（已含蓄力倍率與開火時的全域修正）。 */
    double damage() {
        return weapon.damage() * damageScale * damageBoost;
    }

    /** 這一發對方塊的實際傷害（已含蓄力倍率與開火時的全域修正）。 */
    double damageVsBlock() {
        return weapon.damageVsBlock() * damageScale * damageBoost;
    }

    /** 這一 tick 的終點（還沒考慮碰撞）。 */
    Vec3 nextPos() {
        return pos.add(velocity);
    }

    /** 推進一步：位置往前、速度受重力。 */
    void advance() {
        pos = pos.add(velocity);
        if (weapon.gravity() != 0) {
            velocity = velocity.subtract(0, weapon.gravity() * gravityScale, 0);
        }
        ticksLeft--;
        if (ticksLeft <= 0) {
            dead = true;
        }
    }
}
