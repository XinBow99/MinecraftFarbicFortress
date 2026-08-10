package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.core.DuelEvents;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.YamlConfig;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
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
import net.minecraft.world.entity.Mob;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NPC 的生成與互動，以及 npcs.yml / shops.yml 兩張表。
 *
 * <p>NPC 是「站著不動的介面」：關掉 AI、無敵、不會被推走，右鍵就開店。對戰結束時一併移除——
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
        DuelEvents.END.register((duel, result) -> removeAll(duel.arena().level()));
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
            FortressDuel.LOGGER.warn("NPC {} 指定的實體 '{}' 不存在", def.id(), def.entity());
            return null;
        }

        Entity entity = type.spawn(level, pos, EntitySpawnReason.EVENT);
        if (entity == null) return null;

        entity.setCustomName(Component.literal(def.displayName()));
        entity.setCustomNameVisible(def.nameVisible());
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);

        if (entity instanceof Mob mob) {
            // 關掉 AI 才會站在原地：不然村民會自己跑去睡覺、被怪追著跑
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }

        spawned.put(entity.getUUID(), def);
        return entity;
    }

    /** 對戰結束時把這個世界上所有本 mod 生成的 NPC 移除。 */
    private void removeAll(ServerLevel level) {
        for (UUID id : Map.copyOf(spawned).keySet()) {
            Entity entity = level.getEntityInAnyDimension(id);
            if (entity != null) {
                entity.discard();
            }
            spawned.remove(id);
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
            FortressDuel.LOGGER.warn("NPC {} 指向的商店 '{}' 不在 shops.yml 裡", def.id(), def.shop());
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
