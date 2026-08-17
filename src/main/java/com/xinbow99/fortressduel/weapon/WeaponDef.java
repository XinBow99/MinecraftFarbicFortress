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
        /** 一次擊發至少幾顆（散彈用）。 */
        int pellets,
        /**
         * 一次擊發最多幾顆。跟 {@link #pellets} 相同 ＝ 固定顆數。
         *
         * <p>有範圍是為了散彈那種「每一發的彈著都不一樣」的手感：固定顆數時彈著雖然隨機，
         * 但「這一發有多少火力」是恆定的，打起來比較像一把數值穩定的槍而不是霰彈。
         */
        int pelletsMax,
        /** 散佈：以視線為軸的圓錐半頂角（度）。0 ＝ 完全不散。 */
        double spreadDegrees,
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

        /**
         * 命中時在落點生出哪幾種怪（對應 mobs.yml 的 id）；空 ＝ 這不是投放型武器。
         *
         * <p>有這一欄的彈藥走完全不同的命中路徑：**不造成任何傷害、也不碰方塊**，只放怪。
         * 所以它的傷害與濺射半徑填什麼都無所謂，飛行參數才是它的全部——
         * 那決定了你丟不丟得進對方的院子。
         */
        List<String> spawnMobs,
        /** 命中時放幾隻，在 min~max 之間每一發重抽。 */
        int spawnMin,
        int spawnMax,

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
        /**
         * 開火音效。**可以有好幾個，會同時放。**
         *
         * <p>是清單而不是單一個，是因為玩家可以把音樂家的光碟合進彈藥裡，而且可以疊
         * （見 {@code AmmoDesign.fireSounds}）。weapons.yml 寫一個字串或一個清單都吃得下。
         */
        List<Identifier> fireSounds
) {

    /** 沒寫 knockback 時，用傷害推一個。乘數挑成讓導彈（180）大約推 1.4 格/tick。 */
    private static final double KNOCKBACK_PER_DAMAGE = 0.008;

    public static WeaponDef from(String id, Map<String, Object> section) {
        double damage = YamlConfig.d(section, "damage", 1.0);
        double spread = YamlConfig.d(section, "spread_degrees", 0.0);
        int pellets = Math.max(1, YamlConfig.i(section, "pellets", 1));

        Map<String, Object> charge = section.get("charge") instanceof Map<?, ?> map
                ? castCharge(map) : Map.of();
        // 沒寫 affects 就當成「只影響初速」——那是拋物線武器最直覺的一條，
        // 而且不會意外把傷害也變成蓄力的函數（那會讓平衡整個位移）
        List<String> affects = section.get("charge") instanceof Map<?, ?> map
                && map.get("affects") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("speed");

        List<String> spawnMobs = section.get("spawn_mobs") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        int spawnMin = Math.max(1, YamlConfig.i(section, "spawn_min", 1));

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
                pellets,
                Math.max(pellets, YamlConfig.i(section, "pellets_max", pellets)),
                spread,
                Math.max(1, YamlConfig.i(section, "cooldown_ticks", 20)),
                YamlConfig.d(section, "projectile_speed", 3.0),
                YamlConfig.d(section, "gravity", 0.04),
                Math.max(1, YamlConfig.i(section, "lifetime_ticks", 120)),
                YamlConfig.bool(section, "auto", false),
                YamlConfig.bool(section, "breaks_blocks", true),
                spawnMobs,
                spawnMin,
                Math.max(spawnMin, YamlConfig.i(section, "spawn_max", spawnMin)),
                Math.max(1, YamlConfig.i(section, "ammo_per_shot", 1)),
                Math.max(0, YamlConfig.i(section, "starting_ammo", 0)),
                Identifier.parse(YamlConfig.str(section, "trail_particle", "minecraft:crit")),
                parseColor(YamlConfig.str(section, "trail_color", ""), id),
                Math.max(0.1, YamlConfig.d(section, "trail_scale", 1.4)),
                fireSounds(section));
    }

    /**
     * {@code fire_sound} 可以寫一個字串，也可以寫一個清單。
     *
     * <pre>{@code
     * fire_sound: minecraft:entity.arrow.shoot
     * fire_sound: [minecraft:entity.arrow.shoot, fortress-duel:wow]
     * }</pre>
     *
     * <p>兩種都吃是因為既有的十把武器全部寫的是單一個字串，而清單是為了自製設計才需要的
     * ——為了一個新功能去改十個現成的設定檔，只會讓那十行變得更難讀。
     */
    private static List<Identifier> fireSounds(Map<String, Object> section) {
        Object raw = section.get("fire_sound");
        if (raw instanceof List<?> list) {
            List<Identifier> sounds = new java.util.ArrayList<>(list.size());
            for (Object entry : list) {
                Identifier id = Identifier.tryParse(String.valueOf(entry));
                if (id != null) sounds.add(id);
            }
            if (!sounds.isEmpty()) return List.copyOf(sounds);
        }
        return List.of(Identifier.parse(
                YamlConfig.str(section, "fire_sound", "minecraft:entity.generic.explode")));
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

    /**
     * 這一發最遠打得到幾格（滿蓄力、45 度、無阻擋的理想值）。
     *
     * <p>有重力的走斜拋的最大射程 {@code v² / g}；零重力的（雷射、導彈、無人機）飛行是直線，
     * 限制它的是壽命，所以是 {@code v × lifetime}。
     *
     * <p>用途是開場檢查「這把武器夠不夠打到對面的玻璃牆」——射程不足不會報錯，
     * 只會讓玩家覺得「我這把老是差一點」而查不出原因。見 {@code WeaponSystem.shortRangedFor}。
     */
    public double maxRange() {
        return gravity > 0
                ? projectileSpeed * projectileSpeed / gravity
                : projectileSpeed * lifetimeTicks;
    }

    /** 這是不是一發「投放怪物」的彈藥。是的話它完全不造成傷害，見 {@link #spawnMobs}。 */
    public boolean spawnsMobs() {
        return !spawnMobs.isEmpty();
    }
}
