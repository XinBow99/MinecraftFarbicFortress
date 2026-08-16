package com.xinbow99.fortressduel.core;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.List;
import java.util.Map;

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
        /**
         * 天花板與地板用什麼方塊；留空 ＝ 不封頂不封底（只有一圈牆）。
         *
         * <p>設了就是一個封閉的盒子，四面牆也跟著長到盒底與盒頂——玩家往上爬或往下挖都會
         * 撞到同一個殼。跟牆用不同的方塊是為了視覺：紅色玻璃當天花板會把整片天空染紅。
         */
        String borderCapBlock,
        /** 沒封頂時，牆從地表往上長幾格。封頂時這個值不參與（牆一律長滿整個盒子）。 */
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
        /**
         * 中場的怪可不可以走進玩家的陣地。
         *
         * <p>true ＝ 只限制「不能離開競技場」，區塊分界對牠們不生效——怪會自己送上門，也可能
         * 自己跑掉。false ＝ 關回中場（原本的行為）。
         *
         * <p>玩家與熊貓**不受這個設定影響**，永遠關在自己那半場：那是三區塊限制真正要解的
         * 問題（不能跑去對方陣地破壞），怪物待在哪裡只是附帶的。
         */
        boolean creaturesRoamFreely,

        // ---- 目標（要保護的熊貓）----
        /** 目標生物的實體 id。換成別種生物只要改這裡，不用寫 Java。 */
        String pandaEntity,
        /** 每一方要守幾隻熊貓。全部死光那一方就輸。 */
        int pandaCount,
        /** 每隻熊貓的血量。原版熊貓只有 20，不上調的話狙擊一發一隻。 */
        int pandaHp,
        /**
         * 每隻熊貓的個性，照順序對到第 1、2、3… 隻（不夠就從頭循環）。
         *
         * <p>原版是隨機抽的，而個性直接決定牠好不好牽：worried 會主動躲開玩家、lazy 會躺著
         * 不動、aggressive 會反過來打你。抽籤決定的話，一方拿到三隻膽小、另一方三隻正常，
         * 就是純運氣造成的優劣勢——所以這裡寫死。
         */
        List<String> pandaPersonalities,
        /** 開場柵欄圈的半徑（格）。2 ＝ 5×5 的圈。 */
        int penRadius,
        String penBlock,
        /** 熊貓圈外再往外幾格的木製平台。0 ＝ 不鋪，沿用原本的地形。 */
        int platformRadius,
        String platformBlock,
        /** 開場放在平台上的商人 NPC（對應 npcs.yml），依序左右排開。空的 ＝ 一個都不放。 */
        List<String> dealerNpcs,
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
        /** 建造階段長度（秒）。只在 {@link #buildUntilReady()} 關掉時當長度用。 */
        int buildSeconds,
        /**
         * 建造階段等雙方 {@code /duel ready} 才開戰，而不是倒數固定秒數。
         *
         * <p>蓋一座能守的房子要多久，取決於你想蓋什麼——固定秒數逼所有人蓋同一種規模的東西，
         * 而那正是這個遊戲想讓玩家自己決定的部分。攻擊階段仍然計時：那是節奏的來源。
         */
        boolean buildUntilReady,
        /**
         * ready 模式下最多等幾秒，時間到就強制開戰。0 ＝ 不限。
         *
         * <p>純粹是掛機的保險。離線會直接判負，但掛在原地不按 ready 的話這一場會永遠停住。
         */
        int buildTimeoutSeconds,
        /** 攻擊階段長度（秒）。這段時間不能擺方塊、可以攻擊。 */
        int combatSeconds,
        int outOfBoundsGraceTicks,
        /**
         * 有人離線之後最多等他幾秒才判他放棄。0 ＝ 不等，離線立刻判負（舊的行為）。
         *
         * <p>斷線不等於投降。網路斷一下就輸掉整場，輸的原因跟遊戲本身無關，而這一場的
         * 進度（蓋好的房子、買的東西、剩下的熊貓）也一起沒了——那是最沒有價值的敗局。
         *
         * <p>等待期間整場**暫停**：階段計時器不走、窒息不算、突發事件不發，還在線上的人
         * 也不會在這段時間繼續蓋牆。否則「等對手回來」就變成單方面的免費建造時間。
         */
        int reconnectGraceSeconds,
        /** 開場發給雙方的物資，每一項寫成 {@code "minecraft:dirt 20"}。 */
        List<String> startingItems,
        /** 開場把玩家切成哪個模式，結束還原成他原本的。名稱同原版：survival／adventure／… */
        String gameMode,
        /**
         * 開場是否把玩家原本的背包整份寄放起來（結束原封不動還他）。
         *
         * <p>玩家是帶著自己的家當就地進場的，不清空的話身上本來就有整套裝備的人跟剛上線的人
         * 打的不是同一場遊戲。關掉它是給開發用的——每次測試都被收走測試道具很難做事。
         */
        boolean clearInventory,
        /**
         * 對戰期間把時間釘在哪個時刻：noon／day／night／midnight；空字串 ＝ 不鎖。
         *
         * <p>入夜之後什麼都看不見，而這是一個靠看彈道打的遊戲——勝負不該取決於它剛好開在幾點。
         */
        String lockTime,
        /** 對戰期間強制晴天。下雨會讓遠處的彈道粒子糊掉。 */
        boolean lockWeather,
        /**
         * 自己也打得到自己的熊貓。
         *
         * <p>false ＝ 只有對手的攻擊算數（原本的行為）。true ＝ 攻擊階段裡，任何一方玩家打在
         * 熊貓身上的傷害都算，包含自己的濺射誤傷。
         *
         * <p>打開之後高爆彈與無人機在自家陣地變成真的危險：3.5 格的濺射從自己牆內炸出去，
         * 波及的是自己要守的東西。那把「站在核心旁邊近距離轟」從免費變成有代價。
         *
         * <p>怪物、摔落、隕石造成的傷害仍然一律免疫——那些不是任何一方的操作，
         * 因為一個你控制不了的意外而輸掉整場仍然是很糟的體驗。
         */
        boolean guardianFriendlyFire,

        // ---- 突發事件 ----
        int incidentIntervalSeconds,

        // ---- 經濟 ----
        int startingMoney,
        /** 每一輪建造階段開始時雙方各拿多少。 */
        int roundIncome,
        /**
         * 被對手打中時，每一點傷害扣多少錢。0 ＝ 關掉這個機制。
         *
         * <p>單次罰款以玩家的滿血量為上限：狙擊一發 52 傷害打在只有 20 血的人身上，
         * 罰的是「一條命份量」的錢，不是 52 點的錢。
         */
        int damagePenalty,

        // ---- 武器 ----
        /** 方塊血量 ＝ 原版硬度 × 這個係數。調大 ＝ 牆更耐打，整場節奏變慢。 */
        double blockHpPerHardness,
        /**
         * 逐方塊的血量覆寫（方塊 id → 血量），蓋過「硬度 × 係數」那條公式。
         *
         * <p>原版硬度是「挖多久」的單位，不是「多耐打」——兩者大部分時候方向一致，但也有
         * 對不上的地方：橡木板的硬度 2.0 比石頭的 1.5 高，照公式算木牆會比石牆耐打。
         * 沒有人會這樣預期，而建材的取捨就是靠這種直覺在做的。
         */
        Map<String, Double> blockHpOverrides
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
                cfg.getString("arena.border_cap_block", ""),
                cfg.getInt("arena.border_height", 32),
                cfg.getBoolean("arena.restore_terrain", true),
                cfg.getInt("arena.margin", 16),
                cfg.getInt("arena.core_offset", 3),
                cfg.getStringList("arena.buildings"),
                cfg.getDouble("arena.neutral_fraction", 0.3),
                cfg.getBoolean("arena.creatures_roam_freely", true),

                cfg.getString("objective.entity", "minecraft:panda"),
                Math.max(1, cfg.getInt("objective.panda_count", 4)),
                Math.max(1, cfg.getInt("objective.panda_hp", 100)),
                pandaPersonalities(cfg),
                Math.max(1, cfg.getInt("objective.pen_radius", 2)),
                cfg.getString("objective.pen_block", "minecraft:oak_fence"),
                Math.max(0, cfg.getInt("arena.platform_radius", 5)),
                cfg.getString("arena.platform_block", "minecraft:oak_planks"),
                dealerNpcs(cfg),
                cfg.getInt("objective.suffocation_min_space", 6),
                cfg.getDouble("objective.suffocation_damage", 2.0),

                cfg.getInt("battle.countdown_seconds", 10),
                cfg.getInt("battle.build_seconds", 60),
                cfg.getBoolean("battle.build_until_ready", true),
                Math.max(0, cfg.getInt("battle.build_timeout_seconds", 300)),
                cfg.getInt("battle.combat_seconds", 60),
                cfg.getInt("battle.out_of_bounds_grace_ticks", 40),
                Math.max(0, cfg.getInt("battle.reconnect_grace_seconds", 600)),
                cfg.getStringList("battle.starting_items"),
                cfg.getString("battle.gamemode", "survival"),
                cfg.getBoolean("battle.clear_inventory", true),
                cfg.getString("battle.lock_time", "noon"),
                cfg.getBoolean("battle.lock_weather", true),
                cfg.getBoolean("battle.guardian_friendly_fire", true),

                cfg.getInt("incident.interval_seconds", 120),

                cfg.getInt("economy.starting_money", 600),
                cfg.getInt("economy.round_income", 380),
                cfg.getInt("economy.damage_penalty", 2),

                cfg.getDouble("weapon.block_hp_per_hardness", 10.0),
                cfg.getDoubleMap("weapon.block_hp"));
    }

    /**
     * 設定檔沒寫 personalities 時的預設：三隻正常、一隻懶惰。
     *
     * <p>不是全部正常——四隻一模一樣的話牠們會擠成一團動作一致，看起來像四個複製品。
     * 一隻懶惰的躺在旁邊剛好給這一圈一點差異，而且懶惰只是不太走動，不會像 worried
     * 那樣主動躲開你，牽起來仍然是可預期的。
     */
    private static List<String> pandaPersonalities(YamlConfig cfg) {
        List<String> configured = cfg.getStringList("objective.personalities");
        return configured.isEmpty() ? List.of("normal", "normal", "normal", "lazy") : configured;
    }

    /**
     * 開場要放哪幾個商人。
     *
     * <p>舊設定檔寫的是單數的 {@code arena.dealer_npc}，那些檔案還在玩家的 config 資料夾裡，
     * 所以複數的沒寫時退回去讀它——不然升級之後場上會一個商人都沒有，而那是「補不到子彈」。
     */
    private static List<String> dealerNpcs(YamlConfig cfg) {
        List<String> configured = cfg.getStringList("arena.dealer_npcs");
        if (!configured.isEmpty()) return List.copyOf(configured);

        String single = cfg.getString("arena.dealer_npc", "");
        if (!single.isBlank()) return List.of(single);

        return List.of("arms_dealer", "musician");
    }

    /** 全部用預設值，設定檔還沒讀進來時的退路。 */
    public static DuelSettings defaults() {
        return from(YamlConfig.empty("duel.yml"));
    }
}
