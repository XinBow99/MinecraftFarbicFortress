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
        /** 水晶生成在玩家往「遠離對手」方向退幾格的位置。 */
        int coreOffset,
        /** 開場時兩側各蓋哪幾棟建築（對應 buildings.yml）。 */
        List<String> arenaBuildings,

        // ---- 核心（烽火台）----
        int coreHp,
        int coreHitDamage,
        String coreBaseBlock,

        // ---- 對戰中 ----
        int countdownSeconds,
        /** 建造階段長度（秒）。這段時間可以擺方塊、不能攻擊。 */
        int buildSeconds,
        /** 攻擊階段長度（秒）。這段時間不能擺方塊、可以攻擊。 */
        int combatSeconds,
        int outOfBoundsGraceTicks,
        /** 開場發給雙方的物資，每一項寫成 {@code "minecraft:dirt 20"}。 */
        List<String> startingItems,

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

                cfg.getInt("core.hp", 400),
                cfg.getInt("core.hit_damage", 10),
                cfg.getString("core.base_block", "minecraft:iron_block"),

                cfg.getInt("battle.countdown_seconds", 10),
                cfg.getInt("battle.build_seconds", 60),
                cfg.getInt("battle.combat_seconds", 60),
                cfg.getInt("battle.out_of_bounds_grace_ticks", 40),
                startingItems(cfg),

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
        return configured.isEmpty() ? List.of("minecraft:dirt 20") : configured;
    }

    /** 全部用預設值，設定檔還沒讀進來時的退路。 */
    public static DuelSettings defaults() {
        return from(YamlConfig.empty("duel.yml"));
    }
}
