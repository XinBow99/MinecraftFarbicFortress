package com.xinbow99.fortressduel.core;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.building.BuildingPlacer;
import com.xinbow99.fortressduel.incident.IncidentRegistry;
import com.xinbow99.fortressduel.npc.NpcManager;
import com.xinbow99.fortressduel.mobs.entity.MobRegistry;
import com.xinbow99.fortressduel.mobs.skills.SkillRegistry;
import com.xinbow99.fortressduel.util.YamlConfig;
import com.xinbow99.fortressduel.weapon.WeaponRegistry;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 所有 YAML 設定的單一入口。
 *
 * <p>設定放在 {@code config/fortress-duel/}，第一次啟動時會從 jar 內複製一份預設檔出去。
 * {@code /duel reload} 會整包重讀——重讀只換掉這裡的參考，不動任何進行中的對戰
 * （進行中那場沿用它開場時拿到的設定，避免打到一半數值突然變了）。
 */
public final class ConfigManager {

    private final Path configDir;
    private final WeaponRegistry weapons = new WeaponRegistry();
    private final MobRegistry mobs = new MobRegistry();
    private final SkillRegistry skills = new SkillRegistry();
    private final IncidentRegistry incidents = new IncidentRegistry();

    private volatile DuelSettings settings = DuelSettings.defaults();

    /**
     * NPC／商店／建築三張表由各自的子系統持有，但要跟其他設定在同一次 reload 裡一起重讀，
     * 所以在啟動時登記進來。建構 ConfigManager 的時候它們還不存在（它們反過來需要設定），
     * 因此是事後注入而不是建構子參數。
     */
    private NpcManager npcs;
    private BuildingPlacer buildings;

    public ConfigManager() {
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve(FortressDuel.MOD_ID);
    }

    public void reload() {
        // npc / shop / building 三張表的擁有者是各自的子系統，這裡只負責在同一次 reload 裡餵給它們
        settings = DuelSettings.from(YamlConfig.load(configDir, "duel.yml"));
        weapons.load(YamlConfig.load(configDir, "weapons.yml"));
        mobs.load(YamlConfig.load(configDir, "mobs.yml"));
        skills.load(YamlConfig.load(configDir, "skills.yml"));
        incidents.load(YamlConfig.load(configDir, "incidents.yml"));
        if (npcs != null) {
            npcs.loadNpcs(YamlConfig.load(configDir, "npcs.yml"));
            npcs.loadShops(YamlConfig.load(configDir, "shops.yml"));
            // 音樂家那間店是從曲目表生出來的，所以一定要排在 loadShops 後面（見 loadSongs）
            npcs.loadSongs(YamlConfig.load(configDir, "songs.yml"));
        }
        if (buildings != null) {
            buildings.load(YamlConfig.load(configDir, "buildings.yml"));
        }

        FortressDuel.LOGGER.info(
                "Config loaded: {} weapons, {} mobs, {} skills, {} incidents, {} NPCs, {} shops, {} buildings",
                weapons.size(), mobs.size(), skills.size(), incidents.size(),
                npcs == null ? 0 : npcs.npcCount(),
                npcs == null ? 0 : npcs.shopCount(),
                buildings == null ? 0 : buildings.size());
    }

    /** 啟動時把兩個子系統登記進來，之後每次 reload 都會一併重讀它們的表。 */
    public void attach(NpcManager npcs, BuildingPlacer buildings) {
        this.npcs = npcs;
        this.buildings = buildings;
    }

    public Path configDir() {
        return configDir;
    }

    public DuelSettings settings() {
        return settings;
    }

    public WeaponRegistry weapons() {
        return weapons;
    }

    public MobRegistry mobs() {
        return mobs;
    }

    public SkillRegistry skills() {
        return skills;
    }

    public IncidentRegistry incidents() {
        return incidents;
    }
}
