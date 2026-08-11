package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.mobs.entity.MobSpawner;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import com.xinbow99.fortressduel.util.YamlConfig;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NPC 的生成與互動，以及 npcs.yml / shops.yml 兩張表。
 *
 * <p>NPC 是「站著不動的介面」：關掉 AI、不會被推走，右鍵就開店。但**打得死**——
 * 軍火商是玩家要保護的資產，他一倒那一側就補不到子彈。對戰結束時一併移除——
 * 它們屬於那一場的場地，不該留在世界上。
 */
public final class NpcManager {

    private final ConfigManager config;
    private final EconomyManager economy;
    private final WeaponSystem weapons;

    private volatile Map<String, NpcDef> npcs = Map.of();
    private volatile Map<String, ShopDef> shops = Map.of();

    /** 生出來的 NPC → 牠是哪個定義。對戰結束時要照這張表把牠們清掉。 */
    private final Map<UUID, NpcDef> spawned = new HashMap<>();

    public NpcManager(ConfigManager config, EconomyManager economy, WeaponSystem weapons) {
        this.config = config;
        this.economy = economy;
        this.weapons = weapons;
    }

    public void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                player instanceof ServerPlayer sp ? onInteract(sp, entity) : InteractionResult.PASS);
        DuelEvents.END.register((duel, result) ->
                removeIn(duel.arena().level(), duel.arena().region()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> onNpcDeath(entity));
    }

    /**
     * NPC 被打死。
     *
     * <p>不重生、不還原——這正是他能當成戰術目標的原因：與其硬啃對方的熊貓，
     * 先把他的軍火商做掉，對面接下來就補不到子彈。
     */
    private void onNpcDeath(LivingEntity entity) {
        NpcDef def = spawned.remove(entity.getUUID());
        if (def == null) return;

        if (!(entity.level() instanceof ServerLevel level)) return;

        // 只講給看得到的人聽：這是場上的事件，不該洗到整個伺服器
        Component text = Msg.warn(def.displayName() + " 被擊殺了！這一側再也買不到東西。");
        level.getPlayers(player -> player.distanceToSqr(entity) < 96 * 96)
                .forEach(player -> player.sendSystemMessage(text));
    }

    // ---------- 設定 ----------

    public void loadNpcs(YamlConfig cfg) {
        Map<String, NpcDef> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("npcs").entrySet()) {
            loaded.put(e.getKey(), NpcDef.from(e.getKey(), e.getValue()));
        }
        this.npcs = Map.copyOf(loaded);
    }

    public void loadShops(YamlConfig cfg) {
        Map<String, ShopDef> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("shops").entrySet()) {
            loaded.put(e.getKey(), ShopDef.from(e.getKey(), e.getValue()));
        }
        this.shops = Map.copyOf(loaded);
    }

    public int npcCount() {
        return npcs.size();
    }

    public int shopCount() {
        return shops.size();
    }

    public NpcDef npc(String id) {
        return npcs.get(id);
    }

    public ShopDef shop(String id) {
        return shops.get(id);
    }

    public Collection<NpcDef> allNpcs() {
        return npcs.values();
    }

    // ---------- 生成 ----------

    /** 在指定位置放一個 NPC。 */
    public Entity spawn(ServerLevel level, NpcDef def, BlockPos pos, float yaw) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(def.entity()).orElse(null);
        if (type == null) {
            FortressDuel.LOGGER.warn("NPC {} references entity '{}' which does not exist", def.id(), def.entity());
            return null;
        }

        Entity entity = type.spawn(level, pos, EntitySpawnReason.EVENT);
        if (entity == null) return null;

        entity.setCustomName(Component.literal(def.displayName()));
        entity.setCustomNameVisible(def.nameVisible());
        entity.setSilent(true);
        entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);

        if (entity instanceof Mob mob) {
            // 關掉 AI 才會站在原地：不然村民會自己跑去睡覺、被怪追著跑。
            // 但**不設無敵**——軍火商是可以被打死的資產，守住他是玩家的責任
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            MobSpawner.setMaxHealth(mob, (float) def.health());
            mob.setHealth(mob.getMaxHealth());
        }

        spawned.put(entity.getUUID(), def);
        return entity;
    }

    /**
     * 移除某一座競技場裡的 NPC。
     *
     * <p>用「位置落在這場的範圍內」判斷，而不是清掉整張表——同時有兩場對戰時，一場結束
     * 把另一場的軍火商也清掉的話，那一場的玩家從此買不到東西。
     *
     * <p>之所以不是把 NPC 記成「屬於哪一場對戰」，是因為建築（連同 NPC）是在 {@code Arena.build}
     * 裡放下去的，那時 {@link com.xinbow99.fortressduel.battle.Duel} 物件還沒被建出來。
     * 用範圍判斷就不需要把 Duel 一路傳進放置流程。
     */
    private void removeIn(ServerLevel level, Region region) {
        Iterator<Map.Entry<UUID, NpcDef>> it = spawned.entrySet().iterator();
        while (it.hasNext()) {
            UUID id = it.next().getKey();
            Entity entity = level.getEntityInAnyDimension(id);

            if (entity == null) {
                // 已經不在世界上了（被指令清掉、區塊卸載後消失…），記錄留著也沒用
                it.remove();
                continue;
            }
            if (entity.level() == level && region.contains(entity.blockPosition())) {
                entity.discard();
                it.remove();
            }
        }
    }

    // ---------- 互動 ----------

    private InteractionResult onInteract(ServerPlayer player, Entity entity) {
        NpcDef def = spawned.get(entity.getUUID());
        if (def == null) return InteractionResult.PASS;

        if (def.shop().isEmpty()) {
            return InteractionResult.SUCCESS; // 純裝飾的 NPC：吃掉右鍵，但不做事
        }

        ShopDef shop = shops.get(def.shop());
        if (shop == null) {
            FortressDuel.LOGGER.warn("NPC {} references shop '{}' which is not defined in shops.yml", def.id(), def.shop());
            player.sendSystemMessage(Msg.warn("這個商人的店還沒開張（設定裡找不到商店）。"));
            return InteractionResult.FAIL;
        }

        if (economy.walletOf(player) == null) {
            player.sendSystemMessage(Msg.warn("你不在對戰中，沒辦法交易。"));
            return InteractionResult.FAIL;
        }

        ShopMenu.open(player, shop, economy, weapons);
        return InteractionResult.SUCCESS;
    }

    public ConfigManager config() {
        return config;
    }
}
