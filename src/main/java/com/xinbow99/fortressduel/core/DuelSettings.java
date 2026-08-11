package com.xinbow99.fortressduel.core;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.List;

/**
 * duel.yml 的內容。競技場尺寸、核心血量、挑戰逾時這類「一場對戰怎麼跑」的參數。
 *
 * <p>怪物／武器／突發事件各自有自己的 yml（見 {@link ConfigManager}），不放這裡。
 */
public record DuelSettings(
        // ---- 挑戰 ----
        int challengeTimeoutSeconds,
        int maxChallengeDistance,

        // ---- 競技場 ----
        int arenaSize,
        int arenaHeight,
        int arenaDepth,
        int arenaMinSeparation,
        String borderBlock,
        int borderHeight,
        boolean restoreTerrain,
        /** 競技場邊界離最外側玩家至少留幾格。 */
        int arenaMargin,
        /** 熊貓圈生成在玩家往「遠離對手」方向退幾格的位置。 */
        int coreOffset,
        /** 開場時兩側各蓋哪幾棟建築（對應 buildings.yml）。 */
        List<String> arenaBuildings,
        /**
         * 中場佔兩座熊貓圈距離的比例。0.3 ＝ 中間 30%，兩邊各 35%。
         *
         * <p>用比例而不是固定格數：玩家可能站得很近，固定寬度的中場在那種局面會把整個場地吃掉。
         */
        double neutralFraction,

        // ---- 目標（要保護的熊貓）----
        /** 目標生物的實體 id。換成別種生物只要改這裡，不用寫 Java。 */
        String pandaEntity,
        /** 每一方要守幾隻熊貓。全部死光那一方就輸。 */
        int pandaCount,
        /** 每隻熊貓的血量。原版熊貓只有 20，不上調的話狙擊一發一隻。 */
        int pandaHp,
        /** 開場柵欄圈的半徑（格）。2 ＝ 5×5 的圈。 */
        int penRadius,
        String penBlock,
        /**
         * 熊貓周圍至少要有幾格可站的空間（3×3×3 共 27 格裡算）。低於這個值就持續掉血。
         *
         * <p>沒有這條規則的話，最優解固定是「把熊貓封進 1×1 黑曜石棺材」，佈局的博弈就不存在了。
         */
        int suffocationMinSpace,
        /** 空間不足時每秒扣多少血。 */
        double suffocationDamage,

        // ---- 對戰中 ----
        int countdownSeconds,
        /** 建造階段長度（秒）。這段時間可以擺方塊、不能攻擊。 */
        int buildSeconds,
        /** 攻擊階段長度（秒）。這段時間不能擺方塊、可以攻擊。 */
        int combatSeconds,
        int outOfBoundsGraceTicks,
        /** 開場發給雙方的物資，每一項寫成 {@code "minecraft:dirt 20"}。 */
        List<String> startingItems,
        /** 開場把玩家切成哪個模式，結束還原成他原本的。名稱同原版：survival／adventure／… */
        String gameMode,

        // ---- 突發事件 ----
        int incidentIntervalSeconds,

        // ---- 經濟 ----
        int startingMoney,
        /** 每一輪建造階段開始時雙方各拿多少。 */
        int roundIncome,

        // ---- 武器 ----
        /** 方塊血量 ＝ 原版硬度 × 這個係數。調大 ＝ 牆更耐打，整場節奏變慢。 */
        double blockHpPerHardness
) {

    public static DuelSettings from(YamlConfig cfg) {
        return new DuelSettings(
                cfg.getInt("challenge.timeout_seconds", 30),
                cfg.getInt("challenge.max_distance", 100),

                cfg.getInt("arena.size", 48),
                cfg.getInt("arena.height", 32),
                cfg.getInt("arena.depth", 8),
                cfg.getInt("arena.min_separation", 24),
                cfg.getString("arena.border_block", "minecraft:barrier"),
                cfg.getInt("arena.border_height", 32),
                cfg.getBoolean("arena.restore_terrain", true),
                cfg.getInt("arena.margin", 16),
                cfg.getInt("arena.core_offset", 3),
                cfg.getStringList("arena.buildings"),
                cfg.getDouble("arena.neutral_fraction", 0.3),

                cfg.getString("objective.entity", "minecraft:panda"),
                Math.max(1, cfg.getInt("objective.panda_count", 4)),
                Math.max(1, cfg.getInt("objective.panda_hp", 100)),
                Math.max(1, cfg.getInt("objective.pen_radius", 2)),
                cfg.getString("objective.pen_block", "minecraft:oak_fence"),
                cfg.getInt("objective.suffocation_min_space", 6),
                cfg.getDouble("objective.suffocation_damage", 2.0),

                cfg.getInt("battle.countdown_seconds", 10),
                cfg.getInt("battle.build_seconds", 60),
                cfg.getInt("battle.combat_seconds", 60),
                cfg.getInt("battle.out_of_bounds_grace_ticks", 40),
                startingItems(cfg),
                cfg.getString("battle.gamemode", "survival"),

                cfg.getInt("incident.interval_seconds", 120),

                cfg.getInt("economy.starting_money", 600),
                cfg.getInt("economy.round_income", 380),

                cfg.getDouble("weapon.block_hp_per_hardness", 10.0));
    }

    /**
     * 設定檔沒寫 starting_items 時給一份預設，而不是什麼都不發——雙方在建造階段手上是空的話
     * 第一回合完全沒事做（建材要自己挖，但挖也需要時間）。
     */
    private static List<String> startingItems(YamlConfig cfg) {
        List<String> configured = cfg.getStringList("battle.starting_items");
        return configured.isEmpty() ? List.of("minecraft:dirt 20", "minecraft:lead 4") : configured;
    }

    /** 全部用預設值，設定檔還沒讀進來時的退路。 */
    public static DuelSettings defaults() {
        return from(YamlConfig.empty("duel.yml"));
    }
}
