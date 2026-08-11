package com.xinbow99.fortressduel.util;

import com.xinbow99.fortressduel.FortressDuel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 開場把玩家原本的背包整份寄放起來，打完再原封不動還他。
 *
 * <p>為什麼要寄放：**這個遊戲不傳送玩家**，競技場是就地框在他們站的位置上的，所以玩家是帶著
 * 自己在主世界的家當進場的。那份家當會直接影響對戰——身上本來就有一組鑽石裝、一堆黑曜石、
 * 甚至一把附魔弓的人，跟剛上線的人打的不是同一場遊戲。清空是為了讓雙方從同一條線出發。
 *
 * <p>{@link DuelItems} 解的是反方向的問題（對戰**發**的東西不能帶回主世界），兩者互補：
 * 進場清空自己的、離場收回發的，兩邊的物資因此完全不流通。
 *
 * <h2>為什麼寫進檔案而不是放在記憶體</h2>
 *
 * <p>寄放期間玩家身上是空的，那份家當只存在於我們手上——所以任何一條「東西沒還就結束」的路徑
 * 都等於把玩家的存檔洗掉。記憶體撐不住的情況有三種，而且都不罕見：中途登出（{@code finish}
 * 那一輪碰不到離線的人）、伺服器關掉、伺服器當掉。寫成檔案之後這三種都只是「下次上線才還」。
 *
 * <p>存放位置是存檔資料夾裡的 {@code fortress-duel-stash/<uuid>.dat}，跟世界存檔同生共死——
 * 換世界不會撿到另一個世界的背包。
 *
 * <p><b>寄放與清空的順序是刻意的</b>：一定先確認整份都寫進檔案了才動背包。編碼失敗或寫檔失敗
 * 就整份放棄、背包原封不動——寧可這一場不清空（頂多不公平），也不能清了卻沒存下來。
 */
public final class InventoryStash {

    private static final String DIR = "fortress-duel-stash";
    private static final String SLOTS = "slots";
    private static final String SLOT = "slot";
    private static final String ITEM = "item";

    private InventoryStash() {
    }

    /**
     * 把整個背包（含副手與盔甲欄）收走存檔。
     *
     * @return 收走了幾疊；0 ＝ 本來就是空的，或者存檔失敗所以什麼都沒動
     */
    public static int take(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return 0;

        Inventory inventory = player.getInventory();
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag slots = new ListTag();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;

            Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (encoded == null) {
                // 一疊編不出來就整份放棄。只跳過它的話，等一下清空背包時那疊會被直接消滅
                FortressDuel.LOGGER.error("Could not serialise {} in slot {} of {}'s inventory, "
                                + "leaving their inventory untouched",
                        stack, slot, player.getGameProfile().name());
                return 0;
            }

            CompoundTag entry = new CompoundTag();
            entry.putInt(SLOT, slot);
            entry.put(ITEM, encoded);
            slots.add(entry);
        }

        if (slots.isEmpty()) return 0;

        CompoundTag root = new CompoundTag();
        root.put(SLOTS, slots);

        Path file = fileFor(server, player.getUUID());
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            FortressDuel.LOGGER.error("Could not stash {}'s inventory, leaving it untouched",
                    player.getGameProfile().name(), e);
            return 0;
        }

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        player.containerMenu.broadcastChanges();
        return slots.size();
    }

    /** 這個人有沒有東西寄放著。 */
    public static boolean has(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server != null && Files.isRegularFile(fileFor(server, player.getUUID()));
    }

    /**
     * 把寄放的東西還回去，盡量放回原本的格子。
     *
     * <p>原格子被佔住時（對戰期間撿到的東西）改用 {@code placeItemBackInInventory}——它會找空格，
     * 真的滿了就掉在腳邊。寧可掉一地也不覆蓋，覆蓋等於用他的家當換掉他剛撿的東西。
     *
     * <p>只有在**確定東西都回到玩家身上**之後才刪檔。反過來的話，還到一半出事就兩邊都沒有了。
     *
     * @return 還了幾疊；0 ＝ 沒有東西寄放著
     */
    public static int returnTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return 0;

        Path file = fileFor(server, player.getUUID());
        if (!Files.isRegularFile(file)) return 0;

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            // 檔案留著不刪：壞掉的存檔至少還能手動撈，刪了就真的沒了
            FortressDuel.LOGGER.error("Could not read {}'s stashed inventory from {}",
                    player.getGameProfile().name(), file, e);
            return 0;
        }

        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Inventory inventory = player.getInventory();
        int restored = 0;

        for (CompoundTag entry : root.getListOrEmpty(SLOTS).compoundStream().toList()) {
            ItemStack stack = ItemStack.CODEC
                    .parse(ops, entry.getCompoundOrEmpty(ITEM))
                    .result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) continue;

            int slot = entry.getIntOr(SLOT, -1);
            if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack);
            } else {
                inventory.placeItemBackInInventory(stack);
            }
            restored++;
        }

        player.containerMenu.broadcastChanges();

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 東西已經還了。檔案沒刪掉的話下次上線會再還一次（憑空多一份），所以這是要修的錯誤
            FortressDuel.LOGGER.error("Returned {}'s stashed inventory but could not delete {}",
                    player.getGameProfile().name(), file, e);
        }
        return restored;
    }

    private static Path fileFor(MinecraftServer server, UUID id) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIR).resolve(id + ".dat");
    }
}
