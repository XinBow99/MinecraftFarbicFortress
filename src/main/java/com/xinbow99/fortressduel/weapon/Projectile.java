package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.battle.Duel;
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
    final Duel duel;
    final UUID shooterId;
    final String shooterName;

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

    Projectile(WeaponDef weapon, Duel duel, ServerPlayer shooter, Vec3 pos, Vec3 velocity,
               double damageScale) {
        this.weapon = weapon;
        this.duel = duel;
        this.shooterId = shooter.getUUID();
        this.shooterName = shooter.getGameProfile().name();
        this.pos = pos;
        this.velocity = velocity;
        this.damageScale = damageScale;
        this.ticksLeft = weapon.lifetimeTicks();
    }

    /** 這一發的實際傷害（已含蓄力倍率）。 */
    double damage() {
        return weapon.damage() * damageScale;
    }

    /** 這一發對方塊的實際傷害（已含蓄力倍率）。 */
    double damageVsBlock() {
        return weapon.damageVsBlock() * damageScale;
    }

    /** 這一 tick 的終點（還沒考慮碰撞）。 */
    Vec3 nextPos() {
        return pos.add(velocity);
    }

    /** 推進一步：位置往前、速度受重力。 */
    void advance() {
        pos = pos.add(velocity);
        if (weapon.gravity() != 0) {
            velocity = velocity.subtract(0, weapon.gravity(), 0);
        }
        ticksLeft--;
        if (ticksLeft <= 0) {
            dead = true;
        }
    }
}
