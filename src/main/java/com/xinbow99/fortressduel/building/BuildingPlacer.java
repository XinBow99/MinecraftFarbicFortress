package com.xinbow99.fortressduel.building;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.npc.NpcDef;
import com.xinbow99.fortressduel.npc.NpcManager;
import com.xinbow99.fortressduel.util.YamlConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 依藍圖把建築蓋出來，並把裡面的 NPC 放好。
 *
 * <p>蓋之前每一格都會先交給 {@code beforePlace} 記錄原狀，這樣對戰結束才還原得回去——
 * 快照的擁有者是 {@link com.xinbow99.fortressduel.battle.Arena}，這裡只負責通知它。
 */
public final class BuildingPlacer {

    private final NpcManager npcs;
    private volatile Map<String, BuildingDef> buildings = Map.of();

    public BuildingPlacer(NpcManager npcs) {
        this.npcs = npcs;
    }

    public void load(YamlConfig cfg) {
        Map<String, BuildingDef> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("buildings").entrySet()) {
            loaded.put(e.getKey(), BuildingDef.from(e.getKey(), e.getValue()));
        }
        this.buildings = Map.copyOf(loaded);
    }

    public BuildingDef byId(String id) {
        return buildings.get(id);
    }

    public Collection<BuildingDef> all() {
        return buildings.values();
    }

    public int size() {
        return buildings.size();
    }

    /**
     * 在某一側蓋一棟建築。
     *
     * @param anchor      這一側的核心位置，藍圖的 offset 是相對它算的
     * @param mirrorZ     南半場要把 Z 方向鏡射，兩邊的建築才會對稱地面向中場
     * @param beforePlace 覆寫每一格之前呼叫，用來做還原快照
     */
    public void place(ServerLevel level, BuildingDef def, BlockPos anchor, boolean mirrorZ,
                      Consumer<BlockPos> beforePlace) {
        BlockPos origin = anchor.offset(
                def.offsetX(),
                def.offsetY(),
                mirrorZ ? -def.offsetZ() : def.offsetZ());

        List<List<String>> layers = def.layers();
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    String blockId = def.palette().get(row.charAt(x));
                    // 調色盤沒有的字元＝「這一格不要動」，可以只描述牆讓地形穿過去
                    if (blockId == null) continue;

                    BlockState state = state(blockId);
                    if (state == null) continue;

                    BlockPos pos = origin.offset(x, y, mirrorZ ? -z : z);
                    beforePlace.accept(pos);
                    level.setBlock(pos, state, 2);
                }
            }
        }

        placeNpc(level, def, origin, mirrorZ);
    }

    private void placeNpc(ServerLevel level, BuildingDef def, BlockPos origin, boolean mirrorZ) {
        if (def.npc().isEmpty()) return;

        NpcDef npcDef = npcs.npc(def.npc());
        if (npcDef == null) {
            FortressDuel.LOGGER.warn("建築 {} 指定的 NPC '{}' 不在 npcs.yml 裡", def.id(), def.npc());
            return;
        }

        BlockPos pos = origin.offset(
                def.npcOffsetX(),
                def.npcOffsetY(),
                mirrorZ ? -def.npcOffsetZ() : def.npcOffsetZ());
        // 兩邊的商人都面向場中央，玩家走進店裡就正對著他
        npcs.spawn(level, npcDef, pos, mirrorZ ? 0f : 180f);
    }

    private BlockState state(String id) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
        if (block == null) {
            FortressDuel.LOGGER.warn("藍圖裡的方塊 '{}' 不存在，那一格保持原狀", id);
            return null;
        }
        return block.defaultBlockState();
    }
}
