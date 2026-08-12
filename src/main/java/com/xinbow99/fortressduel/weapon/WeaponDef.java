package com.xinbow99.fortressduel.weapon;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        /**
         * 穿甲只對這幾種方塊生效。空的 ＝ 對所有方塊生效（穿甲是一個泛用屬性）。
         *
         * <p>這是「剋制」與「泛用」的分界。穿甲彈填 {@code [minecraft:iron_block]} 之後，
         * 它對鐵塊一發一格、對石頭與黑曜石只剩自己那點基礎傷害——一把專門的破甲彈，
         * 而不是一把「什麼牆都一發」的萬用解。狙擊與導彈的穿甲維持泛用（留空）。
         */
        Set<Identifier> pierceBlocks,
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
        /**
         * 彈道的顏色（打包成 0xRRGGBB）。{@code -1} ＝ 不指定，用 {@link #trailParticle} 那顆原樣。
         *
         * <p>指定顏色時走 {@code dust} 粒子——那是原版唯一能任意上色的粒子，
         * 而 {@code trail_particle} 只吃得下一個 id，沒辦法把顏色一起塞進去。
         */
        int trailColor,
        /** 彈道粒子的大小倍率。只有指定顏色時有意義（dust 才吃得到）。 */
        double trailScale,
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
                pierceBlocks(section, id),
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
                parseColor(YamlConfig.str(section, "trail_color", ""), id),
                Math.max(0.1, YamlConfig.d(section, "trail_scale", 1.4)),
                Identifier.parse(YamlConfig.str(section, "fire_sound", "minecraft:entity.generic.explode")));
    }

    /**
     * 把設定裡的顏色字串解析成 0xRRGGBB。
     *
     * <p>接受 {@code "#A020F0"}、{@code "A020F0"}、{@code "0xA020F0"} 三種寫法——顏色是玩家
     * 從調色盤複製貼上的東西，為了一個 # 就整條變成預設值太苛刻了。
     *
     * @return -1 ＝ 沒指定或格式不對
     */
    private static int parseColor(String raw, String weaponId) {
        String text = raw.trim();
        if (text.isEmpty()) return -1;

        String hex = text.startsWith("#") ? text.substring(1)
                : text.toLowerCase().startsWith("0x") ? text.substring(2)
                : text;
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            FortressDuel.LOGGER.warn("Weapon {} has an unparsable trail_color '{}', ignoring it", weaponId, raw);
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castCharge(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * 讀 {@code pierce_blocks}。沒寫或寫成空的 ＝ 空集合 ＝ 穿甲對所有方塊生效。
     *
     * <p>「沒寫等於全部」而不是「沒寫等於都不」：這個欄位是後來加的，既有的設定檔不會被補鍵
     * （見 {@code YamlConfig}），預設成「都不」的話所有武器的穿甲會在升級後靜默失效。
     */
    private static Set<Identifier> pierceBlocks(Map<String, Object> section, String weaponId) {
        if (!(section.get("pierce_blocks") instanceof List<?> list)) return Set.of();

        Set<Identifier> blocks = new LinkedHashSet<>();
        for (Object raw : list) {
            try {
                blocks.add(Identifier.parse(String.valueOf(raw)));
            } catch (Exception e) {
                FortressDuel.LOGGER.warn("Weapon {} has an unparsable pierce_blocks entry '{}', ignoring it",
                        weaponId, raw);
            }
        }
        return blocks;
    }

    /**
     * 這一發的穿甲吃不吃得到這種方塊。
     *
     * @param block 方塊的註冊 id
     */
    public boolean piercesThrough(Identifier block) {
        return pierceBlocks.isEmpty() || pierceBlocks.contains(block);
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
