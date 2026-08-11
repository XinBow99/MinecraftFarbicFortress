package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.List;
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
        /** 這種武器的**彈藥物品**。副手放著它，主手的弓射出去的就是這一種。 */
        Identifier item,

        double damage,
        /** 濺射半徑（格）。0 ＝ 單點命中。 */
        double splashRadius,
        /** 穿甲：0~1。1 ＝ 完全無視方塊硬度。 */
        double pierce,
        /** 命中生物時推開多遠（格/tick 的速度增量）。0 ＝ 不推。 */
        double knockback,
        /** 力道曲線。 */
        ChargeCurve chargeCurve,
        /** 低於這個力道就不發射、也不耗彈（放空弓）。 */
        double chargeMinDraw,
        /** 力道要不要影響初速。 */
        boolean chargeAffectsSpeed,
        /** 力道要不要影響傷害。 */
        boolean chargeAffectsDamage,
        /** 力道要不要影響散佈（滿弓最準）。 */
        boolean chargeAffectsSpread,
        /** 一次擊發幾顆（散彈用）。 */
        int pellets,
        /** 散佈：以視線為軸的圓錐半頂角（度）。0 ＝ 完全不散。 */
        double spreadDegrees,
        /** 後座力：每擊發一次，散佈額外增加幾度。 */
        double recoil,
        /** 後座力累積的上限（度）。 */
        double recoilMax,
        /** 後座力每秒回復幾度。 */
        double recoilRecovery,
        /** 裝填間隔（tick）。 */
        int cooldownTicks,
        /** 彈丸初速（格/tick）。 */
        double projectileSpeed,
        /** 重力（格/tick²）。0 ＝ 直線飛行，雷射與導彈用。 */
        double gravity,
        /** 飛行幾 tick 之後自己消失，避免打歪的彈丸永遠留在世界上。 */
        int lifetimeTicks,
        /**
         * 按住右鍵是否持續擊發。
         *
         * <p>{@code true} ＝ 按住就一直打，射速完全由 {@link #cooldownTicks} 決定，蓄力不參與；
         * {@code false} ＝ 拉弓、放開，發一發。連射武器（機槍 cooldown 3、雷射 2）只能設 true，
         * 靠連點右鍵打到每秒 7~10 發手感差得有感。
         */
        boolean auto,
        /** 命中的方塊是否會被打掉（只在競技場範圍內生效）。 */
        boolean breaksBlocks,

        /** 一次擊發消耗幾發。散彈打出 5 顆但通常只算 1 發，所以這跟 pellets 是兩回事。 */
        int ammoPerShot,
        /** 開場就配給的量。 */
        int startingAmmo,

        /** 飛行時拖的粒子。 */
        Identifier trailParticle,
        /** 開火音效。 */
        Identifier fireSound
) {

    /** 沒寫 knockback 時，用傷害推一個。乘數挑成讓導彈（180）大約推 1.4 格/tick。 */
    private static final double KNOCKBACK_PER_DAMAGE = 0.008;
    /** 沒寫 recoil 時，用基礎散佈推一個：本來就不準的槍，連射時散得更快。 */
    private static final double RECOIL_PER_SPREAD = 0.5;
    /** 後座力上限的預設倍率（相對基礎散佈），並且至少給這麼多度。 */
    private static final double RECOIL_MAX_FACTOR = 3.0;
    private static final double RECOIL_MAX_FLOOR = 3.0;

    public static WeaponDef from(String id, Map<String, Object> section) {
        double damage = YamlConfig.d(section, "damage", 1.0);
        double spread = YamlConfig.d(section, "spread_degrees", 0.0);

        Map<String, Object> charge = section.get("charge") instanceof Map<?, ?> map
                ? castCharge(map) : Map.of();
        // 沒寫 affects 就當成「只影響初速」——那是拋物線武器最直覺的一條，
        // 而且不會意外把傷害也變成蓄力的函數（那會讓平衡整個位移）
        List<String> affects = section.get("charge") instanceof Map<?, ?> map
                && map.get("affects") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("speed");
        return new WeaponDef(
                id,
                YamlConfig.str(section, "name", id),
                Identifier.parse(YamlConfig.str(section, "item", "minecraft:stick")),
                damage,
                YamlConfig.d(section, "splash_radius", 0.0),
                Math.clamp(YamlConfig.d(section, "pierce", 0.0), 0.0, 1.0),
                // 預設值跟著傷害走，這樣既有的 weapons.yml（沒有這個欄位）也會有力道感——
                // 設定檔是整份複製出去的、不會事後補鍵，預設 0 等於要玩家刪檔才吃得到這個功能
                Math.max(0, YamlConfig.d(section, "knockback", damage * KNOCKBACK_PER_DAMAGE)),
                ChargeCurve.parse(charge.get("curve"), id),
                Math.clamp(YamlConfig.d(charge, "min_draw", 0.15), 0.0, 1.0),
                affects.contains("speed"),
                affects.contains("damage"),
                affects.contains("spread"),
                Math.max(1, YamlConfig.i(section, "pellets", 1)),
                spread,
                // 跟 knockback 同樣的理由：既有的設定檔沒有這些鍵，預設 0 等於要玩家刪檔
                // 才吃得到後座力。用基礎散佈推一個——本來就不準的槍，連射時散得更快
                Math.max(0, YamlConfig.d(section, "recoil", spread * RECOIL_PER_SPREAD)),
                Math.max(0, YamlConfig.d(section, "recoil_max",
                        Math.max(RECOIL_MAX_FLOOR, spread * RECOIL_MAX_FACTOR))),
                Math.max(0, YamlConfig.d(section, "recoil_recovery", 6.0)),
                Math.max(1, YamlConfig.i(section, "cooldown_ticks", 20)),
                YamlConfig.d(section, "projectile_speed", 3.0),
                YamlConfig.d(section, "gravity", 0.04),
                Math.max(1, YamlConfig.i(section, "lifetime_ticks", 120)),
                YamlConfig.bool(section, "auto", false),
                YamlConfig.bool(section, "breaks_blocks", true),
                Math.max(1, YamlConfig.i(section, "ammo_per_shot", 1)),
                Math.max(0, YamlConfig.i(section, "starting_ammo", 0)),
                Identifier.parse(YamlConfig.str(section, "trail_particle", "minecraft:crit")),
                Identifier.parse(YamlConfig.str(section, "fire_sound", "minecraft:entity.generic.explode")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castCharge(Map<?, ?> map) {
        return (Map<String, Object>) map;
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
