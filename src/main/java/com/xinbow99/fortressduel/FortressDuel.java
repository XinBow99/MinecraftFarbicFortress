package com.xinbow99.fortressduel;

import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.battle.DuelServices;
import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.craft.AmmoLook;
import com.xinbow99.fortressduel.building.Blueprint;
import com.xinbow99.fortressduel.craft.CraftingBench;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.npc.NpcManager;
import com.xinbow99.fortressduel.core.DuelCommands;
import com.xinbow99.fortressduel.incident.IncidentScheduler;
import com.xinbow99.fortressduel.jobs.JobManager;
import com.xinbow99.fortressduel.mobs.skills.SkillEngine;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 要塞對戰（Fortress Duel）。
 *
 * <p>玩家可以向任意玩家發起挑戰，接受後雙方被傳送到附近框出來的 n×n 競技場，各自那半場的正中央
 * 有一圈要保護的熊貓。即時制：進場倒數結束就開打，建材不用買——自己挖、自己蓋，把對方的熊貓
 * 全部打死就贏。玩法與內容（武器／怪物／突發事件）移植自同名的網頁版。
 *
 * <p>模組分層：
 * <ul>
 *   <li>{@code core}     — 設定載入、指令、對戰事件匯流排</li>
 *   <li>{@code battle}   — 挑戰流程、競技場、核心血量</li>
 *   <li>{@code weapon}   — 武器與技能效果（weapons.yml）</li>
 *   <li>{@code mobs.entity} — 怪物本身（mobs.yml）</li>
 *   <li>{@code mobs.skills} — 怪物技能（skills.yml）</li>
 *   <li>{@code incident} — 突發事件（incidents.yml）</li>
 *   <li>{@code jobs}     — 工人經濟：礦工與農夫（jobs.yml）</li>
 *   <li>{@code util}     — 共用工具</li>
 * </ul>
 */
public class FortressDuel implements ModInitializer {

    public static final String MOD_ID = "fortress-duel";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ConfigManager config = new ConfigManager();

        DuelManager duels = new DuelManager(config);
        SkillEngine skills = new SkillEngine(config, duels);
        WeaponSystem weapons = new WeaponSystem(config, duels, skills);
        EconomyManager economy = new EconomyManager(config, duels, skills);
        NpcManager npcs = new NpcManager(config, economy, weapons, duels);
        BuildingPlacer buildings = new BuildingPlacer(npcs);
        JobManager jobs = new JobManager(config, duels, npcs, buildings, economy, weapons);

        // 子系統之間互相需要，所以先全部建好再互相登記，最後才第一次讀設定
        config.attach(npcs, buildings);
        // 商店要靠它處理 type: worker 的商品，它要靠商店那邊的 NpcManager 生成工人——雙向，
        // 所以只能事後注入
        npcs.attach(jobs);
        duels.attach(new DuelServices(buildings, economy, weapons, jobs));
        config.reload();

        CraftingBench.install(config);
        // 圖紙的右鍵放置：它要對戰（拿還原快照）也要藍圖表
        Blueprint.register(duels, buildings);
        AmmoLook.install(config.materials());
        duels.register();
        skills.register();
        // 經濟要比技能引擎晚註冊沒關係——兩邊都掛在 AFTER_DEATH，但誰都不會刪掉對方要用的登記
        economy.register();
        weapons.register();
        npcs.register();
        jobs.register();
        IncidentScheduler incidents = new IncidentScheduler(config, skills, npcs);
        incidents.register();
        new DuelCommands(duels, config, skills, weapons, incidents, jobs, economy).register();

        LOGGER.info("Fortress Duel loaded. Config directory: {}", config.configDir());
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
