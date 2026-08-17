package com.xinbow99.fortressduel.incident;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.Side;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.mobs.entity.MobDef;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.npc.NpcDef;
import com.xinbow99.fortressduel.npc.NpcManager;
import com.xinbow99.fortressduel.util.Ground;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * 突發事件的排程。
 *
 * <p>網頁版是「每回合開始抽一次」，MC 版沒有回合，所以改成每隔 {@code interval_seconds} 抽一次。
 * 抽中什麼由 incidents.yml 的權重決定，跟網頁版同一套（權重比例照抄）。
 *
 * <p>骨架階段只實作兩種 action：{@code message}（純公告，用來驗證排程有在跑）與
 * {@code spawn_mobs}（在場中央放一群怪）。要加新效果就在 {@link #execute} 加一個分支。
 */
public final class IncidentScheduler {

    /** 隕石的初始下墜速度（格/tick）。原版 TNT 出生時是往上彈的，要覆蓋掉。 */
    private static final double METEOR_FALL_SPEED = 0.6;

    /** 結束時清怪的範圍要比競技場往外放寬幾格。見 {@link #clearMobs}。 */
    private static final double MOB_SWEEP_MARGIN = 16.0;

    /** 幾 tick 檢查一次怪物數量上限。見 {@link #capMobs}。 */
    private static final int MOB_CAP_INTERVAL = 10;

    private final ConfigManager config;
    private final SkillEngine skills;
    /** {@code action: merchant} 要靠它把商人放到場上。 */
    private final NpcManager npcs;

    /** 每一場對戰各自的倒數，key 用 Duel 物件本身（一場對戰的生命週期內都是同一個實例）。 */
    private final Map<Duel, Integer> countdowns = new HashMap<>();

    /** {@link #capMobs} 的節流計數器。全場共用一個就夠——它只是決定「這一 tick 要不要數」。 */
    private int mobCapTicks;

    public IncidentScheduler(ConfigManager config, SkillEngine skills, NpcManager npcs) {
        this.config = config;
        this.skills = skills;
        this.npcs = npcs;
    }

    public void register() {
        DuelEvents.START.register(duel -> countdowns.put(duel, intervalTicks()));
        DuelEvents.END.register((duel, result) -> {
            countdowns.remove(duel);
            clearMeteors(duel);
            clearMobs(duel);
        });
        DuelEvents.TICK.register(this::onDuelTick);
    }

    /** 抽籤間隔。放在 duel.yml 而不是 incidents.yml：這是對戰節奏，不是某個事件自己的設定。 */
    private int intervalTicks() {
        return Math.max(20, config.settings().incidentIntervalSeconds() * 20);
    }

    private void onDuelTick(Duel duel) {
        Integer remaining = countdowns.get(duel);
        if (remaining == null) return;

        capMobs(duel);

        if (remaining > 0) {
            countdowns.put(duel, remaining - 1);
            return;
        }
        countdowns.put(duel, intervalTicks());

        IncidentDef incident = config.incidents().randomWeighted(duel.arena().level().getRandom());
        if (incident == null) return;

        announce(duel, incident);
        execute(duel, incident);
    }

    /**
     * 把場上的怪壓回 mobs.yml 的上限以內。
     *
     * <p>掛在事件的 tick 上，但它管的**不只是事件生的怪**：寶貝蛋、還有會召喚與會分裂的技能
     * 同樣在加怪，而它們吃的是同一份效能預算。之所以住在這裡，是因為事件是量最大的來源，
     * 而這裡已經是逐場、逐 tick 在跑的地方（見 {@code MobSpawner.enforceCap}）。
     *
     * <p>每 {@link #MOB_CAP_INTERVAL} tick 才數一次：這是一次範圍實體查詢，每 tick 跑等於
     * 為了省效能而花效能。半秒的延遲在「怪太多了」這件事上完全無感。
     */
    private void capMobs(Duel duel) {
        if (++mobCapTicks < MOB_CAP_INTERVAL) return;
        mobCapTicks = 0;

        int max = config.mobs().maxAlive();
        int culled = MobSpawner.enforceCap(duel.arena().level(), boxOf(duel), max);
        if (culled > 0) {
            FortressDuel.LOGGER.info("Mob cap ({}) exceeded, removed {} of the oldest", max, culled);
        }
    }

    /**
     * 立刻在這一場觸發指定的事件。給 {@code /duel incident} 用。
     *
     * <p>沒有這個入口的話這些效果幾乎測不動：抽籤每 {@code interval_seconds} 才一次，
     * 而單一事件的權重只佔全部的幾個百分點——想看隕石雨平均要等半小時以上。
     *
     * @return 找不到這個 id 時回傳錯誤訊息，成功回傳 null
     */
    public String trigger(Duel duel, String incidentId) {
        IncidentDef incident = config.incidents().byId(incidentId);
        if (incident == null) {
            return "incidents.yml 裡沒有 '" + incidentId + "' 這個事件。";
        }

        announce(duel, incident);
        execute(duel, incident);
        return null;
    }

    private void announce(Duel duel, IncidentDef incident) {
        Component title = Component.literal("突發事件：" + incident.displayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        SoundEvent music = music(incident);

        for (ServerPlayer player : playersOf(duel)) {
            player.sendSystemMessage(title);
            if (!incident.description().isEmpty()) {
                player.sendSystemMessage(Msg.info(incident.description()));
            }
            if (music != null) {
                // 在每個人自己的位置放一次，而不是在場中央放一次——不然離得遠的那一方
                // 會因為距離衰減而聽不到自己這場的事件音樂。
                // 走 MUSIC 頻道，玩家調音樂音量就管得到它
                player.level().playSound(null, player.blockPosition(), music, SoundSource.MUSIC,
                        (float) incident.musicVolume(), (float) incident.musicPitch());
            }
        }
    }

    private SoundEvent music(IncidentDef incident) {
        if (incident.music() == null) return null;

        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(incident.music());
        if (sound == null) {
            FortressDuel.LOGGER.warn("Incident {} references sound '{}' which does not exist", incident.id(), incident.music());
        }
        return sound;
    }

    private void execute(Duel duel, IncidentDef incident) {
        switch (incident.action()) {
            case "message" -> {
                // 公告已經在 announce 做完了，沒有額外效果
            }
            case "spawn_mobs" -> spawnMobs(duel, incident);
            case "raid" -> raid(duel, incident);
            case "merchant" -> merchant(duel, incident);
            case "meteor" -> meteorShower(duel, incident);
            case "modifier" -> applyModifier(duel, incident);
            default -> FortressDuel.LOGGER.warn("Incident {} uses action '{}' which is not implemented yet",
                    incident.id(), incident.action());
        }
    }

    /**
     * 有時限的全域修正：低重力、銅牆鐵壁、火力全開。
     *
     * <p>效果本身由 {@link Duel} 持有（那是「這一場現在的規則」），這裡只負責把設定翻譯過去。
     */
    private void applyModifier(Duel duel, IncidentDef incident) {
        if (incident.modifier().isBlank()) {
            FortressDuel.LOGGER.warn("Incident {} uses action 'modifier' but has no modifier field", incident.id());
            return;
        }
        duel.applyModifier(incident.modifier(), incident.displayName(),
                incident.factor(), incident.durationSeconds() * 20);
    }

    /**
     * 在雙方各自的陣地放一個商人。
     *
     * <p>{@code npc} 欄位指向 npcs.yml 的哪一個。**兩邊各一個**，理由跟 {@code raid} 一樣：
     * 這是一個給雙方的機會，不是給先跑到中場的人的獎勵——不對稱的話它會變成「誰離中間近」
     * 決定的，而那跟玩得好不好無關。
     *
     * <p>他跟軍火商一樣**打得死、不會重生**，而且對戰結束時跟著被清掉。所以「先做掉對方的
     * 建築師」是一條真的戰術，跟做掉軍火商同一個道理。
     */
    private void merchant(Duel duel, IncidentDef incident) {
        if (incident.npc().isBlank()) {
            FortressDuel.LOGGER.warn("Incident {} uses action 'merchant' but has no npc field", incident.id());
            return;
        }
        NpcDef def = npcs.npc(incident.npc());
        if (def == null) {
            FortressDuel.LOGGER.warn("Incident {} references NPC '{}' which is not defined in npcs.yml",
                    incident.id(), incident.npc());
            return;
        }

        ServerLevel level = duel.arena().level();
        // 放在熊貓圈旁邊，跟 raid 用同一個落點邏輯——那是「你的陣地」最明確的座標
        int spread = Math.max(3, config.settings().penRadius() + 3);
        for (BlockPos pen : new BlockPos[]{duel.arena().penA(), duel.arena().penB()}) {
            BlockPos spot = Ground.onSurface(level, 
                    pen.getX() + level.getRandom().nextInt(spread * 2 + 1) - spread,
                    pen.getZ() + level.getRandom().nextInt(spread * 2 + 1) - spread);
            npcs.spawn(level, def, spot, level.getRandom().nextFloat() * 360f);
        }
    }

    /**
     * 在**雙方各自的熊貓圈旁邊**放一批怪，而不是中場。
     *
     * <p>跟 {@code spawn_mobs} 的差別就是落點，而落點決定了它是什麼樣的事件：中場的怪是
     * 雙方要搶的**收入**，家裡的怪是你自己要處理的**麻煩**。兩邊同時放，所以它仍然對稱。
     *
     * <p>{@code arena.creatures_roam_freely} 開著時牠們不會被鎖在生成的那一側，會追著人跑，
     * 所以有機會晃到對面去。起點對稱、而且牠們追的是最近的玩家，所以那是浮動不是不公平。
     */
    private void raid(Duel duel, IncidentDef incident) {
        ServerLevel level = duel.arena().level();
        BlockPos[] pens = {duel.arena().penA(), duel.arena().penB()};
        // 散在圈外一點：直接生在柵欄裡的話牠們會卡在熊貓中間，玩家不敢開火
        int spread = Math.max(3, config.settings().penRadius() + 3);

        for (String mobId : incident.mobs()) {
            MobDef def = config.mobs().byId(mobId);
            if (def == null) {
                FortressDuel.LOGGER.warn("Incident {} references mob '{}' which is not defined in mobs.yml",
                        incident.id(), mobId);
                continue;
            }
            for (BlockPos pen : pens) {
                if (pen == null) continue;
                MobSpawner.spawnPack(level, def, pen, spread, skills);
            }
        }
    }

    private void spawnMobs(Duel duel, IncidentDef incident) {
        ServerLevel level = duel.arena().level();
        BlockPos center = duel.arena().region().center();
        // 中場放怪，散佈半徑取半場寬度的一半——不要生到任何一方的核心腳邊
        int spread = duel.arena().region().sizeZ() / 4;

        for (String mobId : incident.mobs()) {
            MobDef def = config.mobs().byId(mobId);
            if (def == null) {
                FortressDuel.LOGGER.warn("Incident {} references mob '{}' which is not defined in mobs.yml", incident.id(), mobId);
                continue;
            }
            MobSpawner.spawnPack(level, def, center, spread, skills);
        }
    }

    /**
     * 隕石雨：在**雙方各自的**熊貓圈上方落下一批已點燃的 TNT。
     *
     * <p>打雙方而不是中場，因為這個事件的定位是「天災」——它不站在任何一邊，兩個要塞
     * 一起挨轟，所以它改變的是雙方的防禦狀態而不是誰領先。
     *
     * <p>幾件事是刻意的：
     * <ul>
     *   <li><b>用原版的 {@link PrimedTnt}</b>——爆炸、音效、方塊破壞全部沿用原版。順帶得到一個
     *       好性質：黑曜石的爆炸抗性擋得住 TNT，石頭擋不住，所以「花錢蓋好料」在天災面前
     *       真的有差。</li>
     *   <li><b>沒有 owner（傳 null）</b>——熊貓只吃對手玩家的攻擊（見 {@code Duel.allowGuardianDamage}），
     *       所以隕石炸不到熊貓。天災不該直接決定勝負，它拆的是你的牆。</li>
     *   <li><b>要自己往下丟</b>——原版的 TNT 出生時會往上彈一小段（那是被方塊點燃的表現），
     *       不覆蓋速度的話它會先往上飄再落下，看起來不像從天而降。</li>
     *   <li><b>引信隨機浮動</b>——不然幾十顆會在同一 tick 一起爆，那是一聲巨響而不是一陣雨。</li>
     * </ul>
     */
    private void meteorShower(Duel duel, IncidentDef incident) {
        ServerLevel level = duel.arena().level();
        Region region = duel.arena().region();
        RandomSource random = level.getRandom();

        for (Side side : new Side[]{duel.north(), duel.south()}) {
            BlockPos target = side.pen();
            // 熊貓圈還沒圍起來（準備階段）就沒有可以瞄的要塞，跳過這一方
            if (target == null) continue;

            for (int i = 0; i < incident.meteorCount(); i++) {
                double x = target.getX() + 0.5 + random.nextInt(incident.meteorSpread() * 2 + 1) - incident.meteorSpread();
                double z = target.getZ() + 0.5 + random.nextInt(incident.meteorSpread() * 2 + 1) - incident.meteorSpread();
                double y = Math.min(region.maxY(), target.getY() + incident.meteorHeight());

                // 落在框線外的就不放：那顆會在場外炸，破壞的是不屬於這場對戰的世界
                if (!region.contains(x, y, z)) continue;

                PrimedTnt tnt = new PrimedTnt(level, x, y, z, null);
                tnt.setFuse(incident.meteorFuseTicks()
                        + (incident.meteorFuseJitter() > 0 ? random.nextInt(incident.meteorFuseJitter()) : 0));
                tnt.setDeltaMovement(0, -METEOR_FALL_SPEED, 0);
                level.addFreshEntity(tnt);
            }
        }
    }

    /**
     * 對戰結束時清掉還在空中的隕石。
     *
     * <p>跟彈丸同一個理由（見 {@code WeaponSystem.cleanUp}）：{@link Duel#finish} 是先發 END 事件、
     * 再還原地形，所以這時候還沒炸的 TNT 會在**已經還原好的**地形上炸出一個洞，而快照已經
     * 用掉了，那個洞永遠不會被補回來。
     *
     * <p>用範圍掃描而不是記下每一顆的 UUID：TNT 會被爆炸推、會滾下坡，記帳反而容易漏，
     * 而這是一場對戰只做一次的事，掃一遍很便宜。
     */
    private void clearMeteors(Duel duel) {
        for (PrimedTnt tnt : duel.arena().level().getEntitiesOfClass(PrimedTnt.class, boxOf(duel))) {
            tnt.discard();
        }
    }

    /**
     * 對戰結束時收掉事件生出來的怪。
     *
     * <p>沒有這一步的話牠們會**永遠**留在世界上：{@code MobSpawner.applyStats} 會呼叫
     * {@code setPersistenceRequired()}（不然沒有玩家在附近時原版會把牠們清掉，突發事件就變成
     * 隨機失效），代價是自然消失這條退路也一起關掉了。所以「對戰結束時收乾淨」不是禮貌，
     * 是關掉自然消失之後唯一剩下的出口。
     *
     * <p>只收帶標籤的（見 {@code MobSpawner.DUEL_MOB_TAG}），玩家自己養的動物不會被波及。
     *
     * <p>掃描範圍往外放寬 {@link #MOB_SWEEP_MARGIN} 格：會飛的（悅靈、漂鳥、蝙蝠）可能正好在
     * 區域上緣外面，而 {@code arena.creatures_roam_freely} 開著時牠們也會追人追到邊上。
     * 因為篩選條件是標籤而不是位置，放寬範圍不會誤傷任何東西——寧可掃寬一點也不要漏。
     */
    private void clearMobs(Duel duel) {
        MobSpawner.clearIn(duel.arena().level(), boxOf(duel).inflate(MOB_SWEEP_MARGIN));
    }

    /** 競技場區域的碰撞箱。清隕石與清怪共用。 */
    private static AABB boxOf(Duel duel) {
        Region region = duel.arena().region();
        return new AABB(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1.0, region.maxY() + 1.0, region.maxZ() + 1.0);
    }

    private ServerPlayer[] playersOf(Duel duel) {
        var server = duel.arena().level().getServer();
        ServerPlayer north = server.getPlayerList().getPlayer(duel.north().playerId());
        ServerPlayer south = server.getPlayerList().getPlayer(duel.south().playerId());
        if (north != null && south != null) return new ServerPlayer[]{north, south};
        if (north != null) return new ServerPlayer[]{north};
        if (south != null) return new ServerPlayer[]{south};
        return new ServerPlayer[0];
    }
}
