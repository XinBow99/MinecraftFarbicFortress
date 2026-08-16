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
import net.minecraft.world.level.block.state.properties.Property;

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

    /**
     * 極簡開場不蓋任何建築，但軍火商還是要有人放——那條路徑直接走 {@link NpcManager}，
     * 不必為了一個 NPC 生一棟房子出來。
     */
    public NpcManager npcs() {
        return npcs;
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
            FortressDuel.LOGGER.warn("Building {} references NPC '{}' which is not defined in npcs.yml", def.id(), def.npc());
            return;
        }

        BlockPos pos = origin.offset(
                def.npcOffsetX(),
                def.npcOffsetY(),
                mirrorZ ? -def.npcOffsetZ() : def.npcOffsetZ());
        // 兩邊的商人都面向場中央，玩家走進店裡就正對著他
        npcs.spawn(level, npcDef, pos, mirrorZ ? 0f : 180f);
    }

    /**
     * 調色盤的一格 → 方塊狀態。支援 {@code minecraft:wheat[age=7]} 這種帶屬性的寫法。
     *
     * <p>屬性是必要的而不是裝飾：{@code minecraft:wheat} 的預設狀態是**剛發芽的秧苗**，
     * 一整片稻田長那樣看起來像沒種成功。而藍圖是設定檔，「成熟的麥子」不該需要改 Java 才寫得出來。
     *
     * <p>認不得的屬性或值只跳過那一項、保留其餘的——藍圖是手寫的，一個打錯的屬性
     * 讓整格消失的話，玩家看到的是「牆破了一個洞」而不是「我打錯字了」。
     */
    private BlockState state(String id) {
        String blockId = id;
        String properties = null;

        int bracket = id.indexOf('[');
        if (bracket >= 0 && id.endsWith("]")) {
            blockId = id.substring(0, bracket);
            properties = id.substring(bracket + 1, id.length() - 1);
        }

        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId)).orElse(null);
        if (block == null) {
            FortressDuel.LOGGER.warn("Block '{}' in the blueprint does not exist, leaving that cell untouched", id);
            return null;
        }

        BlockState state = block.defaultBlockState();
        if (properties == null || properties.isBlank()) return state;

        for (String pair : properties.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                FortressDuel.LOGGER.warn("Blueprint block '{}' has property '{}' without a value, skipping it", id, pair);
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();

            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property == null) {
                FortressDuel.LOGGER.warn("Blueprint block '{}' has no property '{}', skipping it", blockId, key);
                continue;
            }
            state = withValue(state, property, value, id);
        }
        return state;
    }

    /** 把字串套進一個屬性。獨立成泛型方法，才有辦法讓值的型別跟屬性對上。 */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property,
                                                                 String value, String id) {
        return property.getValue(value)
                .map(parsed -> state.setValue(property, parsed))
                .orElseGet(() -> {
                    FortressDuel.LOGGER.warn("Blueprint block '{}' cannot take '{}' for property '{}', skipping it",
                            id, value, property.getName());
                    return state;
                });
    }
}
