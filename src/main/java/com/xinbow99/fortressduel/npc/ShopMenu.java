package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.FortressDuel;
import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.economy.EconomyManager;
import com.xinbow99.fortressduel.economy.Wallet;
import com.xinbow99.fortressduel.util.DuelItems;
import com.xinbow99.fortressduel.util.DuelSounds;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponItems;
import com.xinbow99.fortressduel.weapon.WeaponSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    /** {@code type: music} 的按鈕要靠它找到這一場，才放得到對手耳朵裡。 */
    private final DuelManager duels;

    private ShopMenu(int syncId, Inventory inventory, SimpleContainer container, ShopDef shop,
                     List<ShopEntry> slotEntries, ServerPlayer player,
                     EconomyManager economy, WeaponSystem weapons, DuelManager duels) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.shop = shop;
        this.slotEntries = slotEntries;
        this.player = player;
        this.economy = economy;
        this.weapons = weapons;
        this.duels = duels;
    }

    /** 開一間店給某個玩家看。 */
    public static void open(ServerPlayer player, ShopDef shop, EconomyManager economy,
                            WeaponSystem weapons, DuelManager duels) {
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

            return new ShopMenu(syncId, inventory, container, shop, slots, player, economy, weapons, duels);
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
            case "launcher" -> lore.add(Component.literal("主手拿它、副手放彈藥")
                    .withStyle(ChatFormatting.GRAY));
            case "ammo" -> {
                lore.add(Component.literal("補充 " + entry.amount() + " 發")
                        .withStyle(ChatFormatting.GRAY));
                WeaponDef weapon = weapons.byId(entry.weapon());
                if (weapon != null) {
                    // 直接把「你現在有幾發」畫在商品上，玩家不用關掉商店翻背包再決定要不要買
                    lore.add(Component.literal("目前 " + weapons.ammoCount(player, weapon) + " 發")
                            .withStyle(ChatFormatting.GRAY));
                    describeWeapon(lore, weapon, player, weapons);
                }
            }
            case "music" -> lore.add(Component.literal("全場都聽得到，包含對手")
                    .withStyle(ChatFormatting.GRAY));
            default -> {
                lore.add(Component.literal("數量 " + entry.amount())
                        .withStyle(ChatFormatting.GRAY));
                describeBuildingBlock(lore, entry, player, weapons);
            }
        }

        if (!entry.lore().isEmpty()) {
            lore.add(Component.literal(entry.lore()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lore.add(Component.literal(entry.type().equals("music") ? "點擊播放" : "點擊購買")
                .withStyle(ChatFormatting.GREEN));

        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /**
     * 建材的說明：血量，以及哪幾把武器打得動。
     *
     * <p>**從實際數值算出來，不是手寫的。** 手寫有兩個問題：改了 {@code block_hp_per_hardness}
     * 之後說明就對不上（而且沒有任何機制會提醒），新增一種建材忘了寫就完全沒有資訊。
     *
     * <p>顯示血量而不是原版硬度——硬度是「挖多久」的單位，在這個 mod 裡沒有直接意義；
     * 玩家真正要拿來比價的是「這格能挨幾發」。
     */
    private static void describeBuildingBlock(List<Component> lore, ShopEntry entry,
                                              ServerPlayer player, WeaponSystem weapons) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.item())).orElse(null);
        if (!(item instanceof BlockItem block)) return;

        BlockState state = block.getBlock().defaultBlockState();
        float hardness = state.getDestroySpeed(player.level(), BlockPos.ZERO);
        if (hardness < 0) return; // 基岩之類，本來就打不掉

        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock());
        float hp = weapons.blockHpOf(blockId, hardness);
        lore.add(Component.literal("血量 " + Math.round(hp) + " / 格")
                .withStyle(ChatFormatting.AQUA));

        // 每塊錢買到多少血量——「黑曜石貴 16 倍但硬 33 倍」這種比較，玩家自己在腦中算不出來
        if (entry.price() > 0 && entry.amount() > 0) {
            double perMoney = hp * entry.amount() / (double) entry.price();
            lore.add(Component.literal(String.format("每 $1 買到 %.1f 血量", perMoney))
                    .withStyle(ChatFormatting.AQUA));
        }

        // 由少排到多：玩家真正要知道的是「哪一把最快拆掉它」，那一把在剋制關係成立時
        // 會遙遙領先（穿甲彈打鐵塊 1 發、打別的要好幾發），排在最前面才看得出那件事
        String shots = weapons.allWeapons().stream()
                .map(weapon -> {
                    int n = weapons.shotsToBreak(weapon, blockId, hardness);
                    return n < 0 ? null : new Object[]{n, weapon.displayName() + " " + n + " 發"};
                })
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(a -> (int) a[0]))
                .map(a -> (String) a[1])
                .limit(4)
                .collect(java.util.stream.Collectors.joining("、"));
        if (!shots.isEmpty()) {
            lore.add(Component.literal(shots).withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** 武器的說明：傷害、射速，以及打石頭與黑曜石各要幾發。 */
    private static void describeWeapon(List<Component> lore, WeaponDef weapon,
                                       ServerPlayer player, WeaponSystem weapons) {
        if (weapon == null) return;

        String rate = String.format("%.1f", 20.0 / weapon.cooldownTicks());
        lore.add(Component.literal("傷害 " + Math.round(weapon.damage())
                        + "   每秒 " + rate + " 發"
                        + pelletText(weapon))
                .withStyle(ChatFormatting.AQUA));

        // 四種建材全列：穿甲彈這種「只剋一種材質」的武器，少列一種就看不出它剋的是誰
        String wood = shotsAgainst(weapon, Blocks.OAK_PLANKS, player, weapons);
        String stone = shotsAgainst(weapon, Blocks.STONE, player, weapons);
        String iron = shotsAgainst(weapon, Blocks.IRON_BLOCK, player, weapons);
        String obsidian = shotsAgainst(weapon, Blocks.OBSIDIAN, player, weapons);
        if (stone != null) {
            lore.add(Component.literal("木 " + wood + "   石 " + stone
                            + "   鐵 " + iron + "   黑曜石 " + obsidian + "  （發）")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }

        if (weapon.splashRadius() > 0) {
            lore.add(Component.literal("濺射半徑 " + weapon.splashRadius() + " 格")
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    /** 「一次 6~7 顆」那一段。單發武器不顯示——那是散彈才有的軸。 */
    private static String pelletText(WeaponDef weapon) {
        if (weapon.pelletsMax() <= 1) return "";
        return weapon.pelletsMax() > weapon.pellets()
                ? "   一次 " + weapon.pellets() + "~" + weapon.pelletsMax() + " 顆"
                : "   一次 " + weapon.pellets() + " 顆";
    }

    private static String shotsAgainst(WeaponDef weapon, Block block,
                                       ServerPlayer player, WeaponSystem weapons) {
        float hardness = block.defaultBlockState().getDestroySpeed(player.level(), BlockPos.ZERO);
        int shots = weapons.shotsToBreak(weapon, BuiltInRegistries.BLOCK.getKey(block), hardness);
        return shots < 0 ? null : String.valueOf(shots);
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

        // Shift + 左鍵（QUICK_MOVE）在背包那一半的意思是「把這疊搬到上面的容器」。
        // 上面的容器是開店時 new 出來的暫存 SimpleContainer，關掉視窗就被丟棄——
        // 也就是說不擋的話，玩家 Shift 點自己的鑽石就等於把鑽石刪掉
        if (input == ContainerInput.QUICK_MOVE) return;

        // 其餘（在自己背包裡搬東西、丟東西）照常
        super.clicked(slotId, button, input, who);
    }

    /**
     * 第二道防線：Shift + 左鍵最終都會走到這裡，直接回空表示「搬不動」。
     *
     * <p>{@link #clicked} 已經擋過一次了，但那是靠「認得 QUICK_MOVE 這個輸入型別」；
     * 這裡是靠「搬移這個動作本身不做事」。兩道防線擋的是不同層級，任何一條新的呼叫路徑
     * 只要經過搬移邏輯就會被這裡攔下。
     */
    @Override
    public ItemStack quickMoveStack(Player who, int slotId) {
        return ItemStack.EMPTY;
    }

    /**
     * 第三道防線：關閉視窗時，把不知怎麼跑進展示櫃的東西還給玩家。
     *
     * <p>正常情況下這裡永遠是空的。留著是因為「玩家的物品消失」這種 bug 事後補救不了——
     * 寧可多一段不會執行到的程式碼，也不要賭前兩道防線沒有漏洞。
     */
    @Override
    public void removed(Player who) {
        for (int slot = 0; slot < SIZE; slot++) {
            if (slotEntries.get(slot) != null) continue; // 展示用的圖示，本來就該留在容器裡

            ItemStack stray = getSlot(slot).getItem();
            if (!stray.isEmpty()) {
                FortressDuel.LOGGER.warn("Returning {} that ended up in shop {}", stray, shop.id());
                who.getInventory().placeItemBackInInventory(stray);
                getSlot(slot).set(ItemStack.EMPTY);
            }
        }
        super.removed(who);
    }

    private void buy(ShopEntry entry) {
        // 音樂按鈕不是商品：不用錢包、不扣錢，也不放購買音效（那會蓋在歌上面），
        // 所以在錢包檢查之前就結束
        if (entry.type().equals("music")) {
            playMusic(entry);
            return;
        }

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
            case "launcher" -> giveLauncher();
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

    /**
     * 給那把弓。
     *
     * <p>不能走 {@code type: item}：那條路是給建材用的，直接發原版物品，所以買到的會是一支
     * 名字叫「弓」的普通弓——櫃子上寫「發射器」、拿到手變成「弓」。發射器是武器系統的東西，
     * 要跟開場那把、{@code /duel give} 那把長得一模一樣，所以統一由 {@link WeaponItems#createBow}
     * 產生（它也順手打上「對戰發的」標記，結束時收得回來）。
     */
    private boolean giveLauncher() {
        player.getInventory().placeItemBackInInventory(WeaponItems.createBow());
        return true;
    }

    /**
     * 給彈藥。
     *
     * <p>彈藥是真的背包物品，所以沒有「彈藥袋滿了」這種狀態——上限由堆疊上限與背包空間決定。
     * 背包滿了的話 {@code placeItemBackInInventory} 會把剩下的丟在腳邊，跟原版一致。
     */
    private boolean giveAmmo(ShopEntry entry) {
        WeaponDef weapon = weapons.byId(entry.weapon());
        if (weapon == null) {
            deny("這件商品設定錯誤（找不到武器 " + entry.weapon() + "）");
            return false;
        }

        player.getInventory().placeItemBackInInventory(
                WeaponItems.createAmmo(weapon, entry.amount()));
        return true;
    }

    private boolean giveItem(ShopEntry entry) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.item())).orElse(null);
        if (item == null) {
            deny("這件商品設定錯誤（找不到物品 " + entry.item() + "）");
            return false;
        }
        ItemStack stack = new ItemStack(item, entry.amount());
        applyEnchantments(stack, entry);

        // 打上「對戰發的」標記。弓與彈藥是由 WeaponItems 產生的、那裡已經標了，只有這條
        // 直接發原版物品的路要自己標——不標的話買來的建材與工具會被帶回主世界，
        // 而那正是回收機制要擋的事（見 DuelItems）
        player.getInventory().placeItemBackInInventory(DuelItems.issue(stack));
        return true;
    }

    /**
     * 把 {@code enchantments} 寫進商品。
     *
     * <p>直接寫進物品而不是走附魔台：不需要經驗值，等級也不受原版上限限制。這個遊戲沒有
     * 經驗值系統，工具的強度是**用錢買的**，跟其他所有東西一樣。
     *
     * <p>附魔是資料驅動的註冊表（資料包可以增刪），所以查不到就跳過並留一行 log，不讓整筆
     * 購買失敗——錢已經要扣了，因為一個設定錯誤而什麼都拿不到是最糟的結果。
     */
    private void applyEnchantments(ItemStack stack, ShopEntry entry) {
        if (entry.enchantments().isEmpty()) return;

        Registry<Enchantment> registry = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT);

        for (Map.Entry<String, Integer> e : entry.enchantments().entrySet()) {
            Holder.Reference<Enchantment> enchantment =
                    registry.get(Identifier.parse(e.getKey())).orElse(null);
            if (enchantment == null) {
                FortressDuel.LOGGER.warn("Shop entry {} references enchantment '{}' which does not exist",
                        entry.id(), e.getKey());
                continue;
            }
            stack.enchant(enchantment, e.getValue());
        }
    }

    /**
     * 點一首歌，**場上所有人都聽得到**（見 {@link Duel#playMusic}）。
     *
     * <p>走的是原版的音效系統：音效 id 直接寫在封包裡送出去（見 {@link DuelSounds}——**不註冊**
     * 進音效登記表，那會害沒裝模組的人連不進來）。客戶端在自己的資源包裡找得到那個 id 就播，
     * 找不到就安靜；音檔在模組的 assets 裡，所以裝了模組的人聽得到。
     *
     * <p>一次只放一首：還在放的時候再點沒有作用，不然連點會疊出好幾軌同一首歌。
     */
    private void playMusic(ShopEntry entry) {
        Holder<SoundEvent> sound = DuelSounds.byId(entry.sound());
        if (sound == null) {
            deny("這件商品設定錯誤（音效 id 不合法：" + entry.sound() + "）");
            return;
        }

        Duel duel = duels.duelOf(player);
        if (duel == null) {
            deny("你目前沒有在對戰中。");
            return;
        }

        if (!duel.playMusic(sound, entry.lengthSeconds() * 20)) {
            deny("這首還沒放完。");
            return;
        }
        player.sendSystemMessage(
                Msg.plain("♪ " + entry.displayName(), ChatFormatting.LIGHT_PURPLE), true);
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
