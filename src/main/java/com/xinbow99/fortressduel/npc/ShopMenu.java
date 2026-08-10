package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.economy.Wallet;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.AmmoPouch;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店介面。
 *
 * <p>用的是**原版的箱子介面型別**（{@code GENERIC_9x6}），只是把點擊行為換掉——所以客戶端不需要
 * 任何額外的畫面程式碼，玩家看到的就是一個普通的箱子。點一格 ＝ 買一次，物品永遠不會真的被拿走。
 */
public final class ShopMenu extends ChestMenu {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final ShopDef shop;
    private final List<ShopEntry> slotEntries;
    private final ServerPlayer player;
    private final EconomyManager economy;
    private final WeaponSystem weapons;

    private ShopMenu(int syncId, Inventory inventory, SimpleContainer container, ShopDef shop,
                     List<ShopEntry> slotEntries, ServerPlayer player,
                     EconomyManager economy, WeaponSystem weapons) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.shop = shop;
        this.slotEntries = slotEntries;
        this.player = player;
        this.economy = economy;
        this.weapons = weapons;
    }

    /** 開一間店給某個玩家看。 */
    public static void open(ServerPlayer player, ShopDef shop, EconomyManager economy, WeaponSystem weapons) {
        player.openMenu(new SimpleMenuProvider((syncId, inventory, owner) -> {
            SimpleContainer container = new SimpleContainer(SIZE);
            List<ShopEntry> slots = new ArrayList<>(java.util.Collections.nCopies(SIZE, null));

            int slot = 0;
            for (ShopEntry entry : shop.entries()) {
                if (slot >= SIZE) break;
                container.setItem(slot, icon(entry, player, economy, weapons));
                slots.set(slot, entry);
                slot++;
            }

            return new ShopMenu(syncId, inventory, container, shop, slots, player, economy, weapons);
        }, Component.literal(shop.title()).withStyle(ChatFormatting.DARK_GREEN)));
    }

    /** 商品的展示物品：圖示 + 名稱 + 價格與現況。 */
    private static ItemStack icon(ShopEntry entry, ServerPlayer player,
                                  EconomyManager economy, WeaponSystem weapons) {
        ItemStack stack = new ItemStack(resolveIcon(entry, weapons));
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(entry.displayName()).withStyle(ChatFormatting.YELLOW));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("價格 $" + entry.price()).withStyle(ChatFormatting.GOLD));

        switch (entry.type()) {
            case "ammo" -> {
                lore.add(Component.literal("補充 " + entry.amount() + " 發")
                        .withStyle(ChatFormatting.GRAY));
                WeaponDef weapon = weapons.byId(entry.weapon());
                if (weapon != null) {
                    // 直接把「你現在有幾發」畫在商品上，玩家不用退出去看 HUD 再決定要不要買
                    lore.add(Component.literal("目前 "
                                    + weapons.pouchOf(player).get(weapon.id())
                                    + "/" + weapon.ammoCapacity())
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            case "weapon" -> lore.add(Component.literal("附 " + entry.amount() + " 發子彈")
                    .withStyle(ChatFormatting.GRAY));
            default -> lore.add(Component.literal("數量 " + entry.amount())
                    .withStyle(ChatFormatting.GRAY));
        }

        if (!entry.lore().isEmpty()) {
            lore.add(Component.literal(entry.lore()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lore.add(Component.literal("點擊購買").withStyle(ChatFormatting.GREEN));

        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static Item resolveIcon(ShopEntry entry, WeaponSystem weapons) {
        String id = entry.item();
        if (id.isEmpty() && !entry.weapon().isEmpty()) {
            WeaponDef weapon = weapons.byId(entry.weapon());
            if (weapon != null) {
                id = weapon.item().toString();
            }
        }
        if (id.isEmpty()) return Items.PAPER;

        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        return item == null ? Items.PAPER : item;
    }

    // ---------- 互動 ----------

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player who) {
        // 商店是唯讀的展示櫃：所有會搬動物品的操作一律不執行，只把「點到哪一格」翻譯成購買。
        // 不擋的話玩家可以直接把展示用的圖示拿走
        if (slotId >= 0 && slotId < SIZE) {
            ShopEntry entry = slotEntries.get(slotId);
            if (entry != null) {
                buy(entry);
            }
            return;
        }
        // 下半部是玩家自己的背包，讓他照常整理
        super.clicked(slotId, button, input, who);
    }

    private void buy(ShopEntry entry) {
        Wallet wallet = economy.walletOf(player);
        if (wallet == null) {
            player.sendSystemMessage(Msg.warn("你目前沒有在對戰中，沒有錢包。"));
            return;
        }

        if (!wallet.canAfford(entry.price())) {
            deny("錢不夠（需要 $" + entry.price() + "，你有 $" + wallet.balance() + "）");
            return;
        }

        boolean delivered = switch (entry.type()) {
            case "weapon" -> giveWeapon(entry);
            case "ammo" -> giveAmmo(entry);
            case "item" -> giveItem(entry);
            default -> {
                FortressDuel.LOGGER.warn("Shop entry {} uses unknown type '{}'", entry.id(), entry.type());
                yield false;
            }
        };
        if (!delivered) return;

        wallet.spend(entry.price());
        player.sendSystemMessage(Msg.plain("購買 " + entry.displayName() + "  −$" + entry.price()
                + "  （餘額 $" + wallet.balance() + "）", ChatFormatting.GREEN), true);
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
        refresh();
    }

    private boolean giveWeapon(ShopEntry entry) {
        WeaponDef weapon = weapons.byId(entry.weapon());
        if (weapon == null) {
            deny("這件商品設定錯誤（找不到武器 " + entry.weapon() + "）");
            return false;
        }

        Item item = BuiltInRegistries.ITEM.getOptional(weapon.item()).orElse(null);
        if (item == null) {
            deny("這件商品設定錯誤（找不到物品 " + weapon.item() + "）");
            return false;
        }

        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(weapon.displayName()).withStyle(ChatFormatting.AQUA));
        player.getInventory().placeItemBackInInventory(stack);
        weapons.pouchOf(player).refill(weapon.id(), entry.amount(), weapon.ammoCapacity());
        return true;
    }

    private boolean giveAmmo(ShopEntry entry) {
        WeaponDef weapon = weapons.byId(entry.weapon());
        if (weapon == null) {
            deny("這件商品設定錯誤（找不到武器 " + entry.weapon() + "）");
            return false;
        }

        AmmoPouch pouch = weapons.pouchOf(player);
        int added = pouch.refill(weapon.id(), entry.amount(), weapon.ammoCapacity());
        if (added <= 0) {
            // 買滿了就不收錢——不然玩家會在沒有任何提示的情況下把錢丟進水裡
            deny(weapon.displayName() + " 的子彈已經滿了（" + weapon.ammoCapacity() + " 發）");
            return false;
        }
        return true;
    }

    private boolean giveItem(ShopEntry entry) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.item())).orElse(null);
        if (item == null) {
            deny("這件商品設定錯誤（找不到物品 " + entry.item() + "）");
            return false;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(item, entry.amount()));
        return true;
    }

    private void deny(String reason) {
        player.sendSystemMessage(Msg.plain(reason, ChatFormatting.RED), true);
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
    }

    /** 買完之後重畫：彈藥數之類的說明會變。 */
    private void refresh() {
        for (int slot = 0; slot < SIZE; slot++) {
            ShopEntry entry = slotEntries.get(slot);
            if (entry != null) {
                getSlot(slot).set(icon(entry, player, economy, weapons));
            }
        }
        broadcastChanges();
    }

    public ShopDef shop() {
        return shop;
    }
}
