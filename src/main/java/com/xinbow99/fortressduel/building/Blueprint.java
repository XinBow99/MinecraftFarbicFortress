package com.xinbow99.fortressduel.building;

import com.xinbow99.fortressduel.battle.Arena;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.battle.Side;
import com.xinbow99.fortressduel.util.DuelItems;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.util.Region;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 建築師賣的圖紙：右鍵地面，整棟直接長出來。
 *
 * <p>它解決的是一件很實際的事——**蓋牆是這個遊戲裡最無聊的部分**。玩家買建材、一格一格
 * 擺、擺完發現高度不夠再補一排，而那段時間裡場上什麼都沒有發生。圖紙把那件事變成一次
 * 點擊，錢照付、位置照選，只是不用再手動堆。
 *
 * <h2>為什麼不是新的方塊或新的物品</h2>
 * <p>本體是一張**原版的紙**，要蓋哪一棟記在 {@link DataComponents#CUSTOM_DATA} 裡——跟彈藥
 * 設計、光碟、對戰標記同一個機制。客戶端不裝 mod 也看得到它、拿得動它、丟得掉它。
 *
 * <h2>放置的四道關卡</h2>
 * <p>每一道都對應一種實際會發生的難看結果，而且**任何一道沒過就不消耗圖紙**——
 * 花了幾百塊買的東西不能因為站錯位置就蒸發：
 * <ul>
 *   <li>要在對戰中——沒有競技場就沒有還原快照，蓋下去會永久留在世界上</li>
 *   <li>整棟都在場內、都不碰框線——框線是場地本身，蓋穿了不會被還原</li>
 *   <li>整棟都在**自己的半場**——這是給你自己用的工事，不是拿去堵對方家門的</li>
 *   <li>每一格都先 {@link Arena#recordBefore} 過——對戰結束時整棟要跟著地形一起消失</li>
 * </ul>
 */
public final class Blueprint {

    /** 存在 CUSTOM_DATA 裡的鍵。加前綴避免跟別的 mod 撞名。 */
    private static final String TAG = "fortressduel_blueprint";
    private static final String BUILDING_KEY = "building";

    private Blueprint() {
    }

    /**
     * 一張圖紙。長得像紙，名字與說明都從藍圖本身算出來。
     *
     * <p>**同一種圖紙會自己疊在一起**：兩張的元件逐一相同（同樣的 CUSTOM_DATA、同樣的名字、
     * 同樣的說明），而原版的堆疊條件就是元件相同。買三張就是一疊 3，連續蓋三棟不用回去
     * 翻背包。不同種的不會疊——它們的 building id 不一樣，那正是我們要的。
     */
    public static ItemStack create(BuildingDef def) {
        ItemStack stack = new ItemStack(Items.PAPER);

        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag tag = new CompoundTag();
            tag.putString(BUILDING_KEY, def.id());
            root.put(TAG, tag);
        });

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(def.displayName() + " 圖紙").withStyle(ChatFormatting.AQUA));

        List<Component> lore = new ArrayList<>();
        lore.add(line(String.format("%d × %d × %d 格", def.width(), def.height(), def.depth())));
        lore.add(line("右鍵地面：整棟直接蓋起來"));
        lore.add(Component.literal("蓋在你點的那一格上方，只能蓋在自己半場")
                .withStyle(ChatFormatting.DARK_GRAY));
        stack.set(DataComponents.LORE, new ItemLore(lore));

        return DuelItems.issue(stack);
    }

    /** 這疊東西是不是圖紙；是的話回傳它要蓋哪一棟。 */
    public static Optional<String> read(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();

        return data.copyTag().getCompound(TAG)
                .flatMap(tag -> tag.getString(BUILDING_KEY))
                .filter(id -> !id.isBlank());
    }

    /**
     * 右鍵地面就蓋。
     *
     * <p>掛 {@link UseBlockCallback}：這是「對著方塊用一個物品」，跟拿方塊蓋東西是同一個
     * 動作，玩家不用學新規則。回 {@code SUCCESS} 攔下原版後續處理（紙本來就沒有右鍵行為，
     * 但攔掉才不會在失敗時把手上的紙放到快捷列的其他行為裡）。
     */
    public static void register(DuelManager duels, BuildingPlacer buildings) {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            String buildingId = read(stack).orElse(null);
            if (buildingId == null) return InteractionResult.PASS;

            place(sp, duels, buildings, buildingId, stack, hit.getBlockPos().above());
            return InteractionResult.SUCCESS;
        });
    }

    private static void place(ServerPlayer player, DuelManager duels, BuildingPlacer buildings,
                              String buildingId, ItemStack stack, BlockPos base) {
        BuildingDef def = buildings.byId(buildingId);
        if (def == null) {
            player.sendSystemMessage(Msg.warn("這張圖紙壞了（buildings.yml 裡沒有 " + buildingId + "）。"));
            return;
        }

        Duel duel = duels.duelOf(player);
        if (duel == null) {
            // 沒有對戰就沒有還原快照，蓋下去會永久留在世界上
            player.sendSystemMessage(Msg.warn("要在對戰中才蓋得起來。"));
            return;
        }

        Arena arena = duel.arena();
        Side side = duel.sideOf(player.getUUID());
        String problem = check(arena, duel, side, def, base);
        if (problem != null) {
            player.sendSystemMessage(Msg.warn(problem));
            return;
        }

        // 藍圖自己的 offset 是給「相對核心擺」用的，圖紙是玩家自己選點，所以要抵消掉，
        // 讓原點正好落在他指的那一格（跟 JobManager 擺節點是同一個理由）
        BlockPos anchor = base.offset(-def.offsetX(), -def.offsetY(), -def.offsetZ());
        buildings.place(arena.level(), def, anchor, false, arena::recordBefore);

        stack.shrink(1);
        arena.level().playSound(null, base, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8f, 1.2f);
        player.sendSystemMessage(Msg.good(def.displayName() + " 蓋好了。"));
    }

    /** @return 蓋不了的原因；null ＝ 可以蓋 */
    private static String check(Arena arena, Duel duel, Side side, BuildingDef def, BlockPos base) {
        Region region = arena.region();

        for (int y = 0; y < def.height(); y++) {
            for (int z = 0; z < def.depth(); z++) {
                for (int x = 0; x < def.width(); x++) {
                    BlockPos pos = base.offset(x, y, z);
                    if (!region.contains(pos) || region.isShell(pos)) {
                        return "蓋不下——這棟會超出場地邊界。往裡面站一點再試。";
                    }
                    if (side != null && arena.zoneAt(Vec3.atCenterOf(pos)) != duel.zoneOf(side)) {
                        return "蓋不下——這棟會伸出你的半場。";
                    }
                }
            }
        }
        return null;
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }
}
