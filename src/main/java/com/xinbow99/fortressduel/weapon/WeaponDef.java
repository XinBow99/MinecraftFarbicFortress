package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * 一種武器的設定，對應 weapons.yml 裡的一個區段。
 *
 * <p>欄位沿用網頁版 {@code AMMO} 表的那幾條軸（傷害／濺射／穿甲／散佈／裝填／連發），
 * 但拿掉了價格——MC 版不買彈藥，武器是手上的物品。
 *
 * <p>速度與重力的單位是「格 / tick」與「格 / tick²」，不是網頁版的像素；數值移植時要換算過。
 */
public record WeaponDef(
        String id,
        String displayName,
        Identifier item,

        double damage,
        /** 濺射半徑（格）。0 ＝ 單點命中。 */
        double splashRadius,
        /** 穿甲：0~1。1 ＝ 完全無視方塊硬度。 */
        double pierce,
        /** 一次擊發幾顆（散彈用）。 */
        int pellets,
        /** 角度散佈（±度）。 */
        double spreadDegrees,
        /** 裝填間隔（tick）。 */
        int cooldownTicks,
        /** 彈丸初速（格/tick）。 */
        double projectileSpeed,
        /** 重力（格/tick²）。0 ＝ 直線飛行，雷射與導彈用。 */
        double gravity,
        /** 飛行幾 tick 之後自己消失，避免打歪的彈丸永遠留在世界上。 */
        int lifetimeTicks,
        /** 按住不放是否持續擊發。 */
        boolean auto,
        /** 命中的方塊是否會被打掉（只在競技場範圍內生效）。 */
        boolean breaksBlocks,

        /** 最多能帶幾發，也就是 HUD 上 {@code 100/120} 的分母。 */
        int ammoCapacity,
        /** 一次擊發消耗幾發。散彈打出 5 顆但通常只算 1 發，所以這跟 pellets 是兩回事。 */
        int ammoPerShot,
        /** 開場就配給的量。 */
        int startingAmmo,

        /** 飛行時拖的粒子。 */
        Identifier trailParticle,
        /** 開火音效。 */
        Identifier fireSound
) {

    public static WeaponDef from(String id, Map<String, Object> section) {
        return new WeaponDef(
                id,
                YamlConfig.str(section, "name", id),
                Identifier.parse(YamlConfig.str(section, "item", "minecraft:stick")),
                YamlConfig.d(section, "damage", 1.0),
                YamlConfig.d(section, "splash_radius", 0.0),
                Math.clamp(YamlConfig.d(section, "pierce", 0.0), 0.0, 1.0),
                Math.max(1, YamlConfig.i(section, "pellets", 1)),
                YamlConfig.d(section, "spread_degrees", 0.0),
                Math.max(1, YamlConfig.i(section, "cooldown_ticks", 20)),
                YamlConfig.d(section, "projectile_speed", 3.0),
                YamlConfig.d(section, "gravity", 0.04),
                Math.max(1, YamlConfig.i(section, "lifetime_ticks", 120)),
                YamlConfig.bool(section, "auto", false),
                YamlConfig.bool(section, "breaks_blocks", true),
                Math.max(1, YamlConfig.i(section, "ammo_capacity", 120)),
                Math.max(1, YamlConfig.i(section, "ammo_per_shot", 1)),
                Math.max(0, YamlConfig.i(section, "starting_ammo", 0)),
                Identifier.parse(YamlConfig.str(section, "trail_particle", "minecraft:crit")),
                Identifier.parse(YamlConfig.str(section, "fire_sound", "minecraft:entity.generic.explode")));
    }

    /**
     * 對方塊的有效傷害。
     *
     * <p>穿甲在網頁版是「無視材質減傷」，MC 沒有材質減傷這條軸，最接近的對應是方塊硬度——
     * 所以穿甲改成「無視硬度」，由 {@link WeaponSystem} 在算方塊血量時套用。這裡只負責傷害本身。
     */
    public double damageVsBlock() {
        return damage;
    }
}
