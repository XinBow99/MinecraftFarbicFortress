package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.weapon.WeaponDef;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * 給一份自製彈藥一個看得出來是它的長相。
 *
 * <p>組合是無界的，所以外觀不可能一種一個物品。做法是**讓一個惰性的物品長成別的樣子**：
 * {@link DataComponents#ITEM_MODEL} 可以把任何一疊東西渲染成另一個物品的模型，而彈藥的
 * 本體始終是鐵粒——這樣就完全避開了「拿真的生物蛋當彈藥，右鍵地面會生出一隻怪」那類
 * 跟原版行為搶同一個操作的問題（這個專案當初不用箭當彈藥也是同一個理由）。
 *
 * <p>挑哪一顆蛋是**從材料向量算出來的**，不是每次隨機。同一個配方永遠同一顆蛋——不然玩家
 * 沒辦法把「這顆蛋」跟「這個手感」連起來，而那正是要外觀的全部意義。順帶一提這也讓
 * 「先做兩個原型再合起來」跟「一次擺滿九格」得到同一個外觀，因為向量是一樣的。
 *
 * <p>88 種生物蛋會撞號（配方比蛋多得多），但撞號只是外觀相同：名稱與 lore 上的實際數值
 * 會把它們分開。附魔光暈則是為了在一排原版物品裡一眼看出「這是自製的」。
 */
public final class AmmoLook {

    /**
     * 玩家自己取的名字，存在 CUSTOM_DATA 裡。
     *
     * <p>**刻意不放進 {@link AmmoVector}**：向量是這份設計的身分（決定數值、外觀、快取的鍵），
     * 而名字只是貼在上面的標籤。混在一起的話，同樣材料但取了不同名字的兩疊彈藥會被當成
     * 兩份不同的設計——長相不一樣、快取各存一份，而它們射出去明明是同一個東西。
     *
     * <p>放在物品上而不是只設 CUSTOM_NAME，是為了讓名字**跟著設計走**：之後軍火商量產時
     * 讀得到它，複製出來的每一疊都叫同一個名字。
     */
    private static final String NAME_TAG = "fortressduel_ammo_name";

    /** 玩家取的名字最長幾個字。太長會把物品欄的 tooltip 撐爆，而那會蓋掉數值。 */
    public static final int MAX_NAME = 24;

    /**
     * 彈藥的本體。挑的是一個**沒有任何右鍵行為、也沒有被任何武器或材料用掉**的物品。
     *
     * <p>後半條同樣重要：本體撞到某把武器的 item 的話，萬一哪天向量讀不到（存檔壞了、
     * 別的 mod 洗掉 component），這疊東西會安靜地退化成那把武器，而不是變成不能用的東西。
     * 安靜地變成別的武器比壞掉更難查。
     */
    private static final Identifier BASE_ITEM = Identifier.withDefaultNamespace("brick");

    /**
     * 材料表，只為了把 id 換成顯示名稱。
     *
     * <p>走靜態橋是因為這個類別是純呈現、被四五個地方直接呼叫，為了一行中文名把
     * MaterialRegistry 一路傳進每一個呼叫點不划算（跟 BowHooks／CraftingBench 同一個做法）。
     */
    private static volatile MaterialRegistry materials;

    /** 所有生物蛋的 id，排序過。排序是必要的：註冊表的順序不保證跨版本穩定，而外觀要穩定。 */
    private static volatile List<Identifier> eggs;

    private AmmoLook() {
    }

    public static void install(MaterialRegistry registry) {
        materials = registry;
    }

    public static Identifier baseItem() {
        return BASE_ITEM;
    }

    /**
     * 把外觀套到這疊彈藥上：模型、名稱、光暈、以及一份實際數值的說明。
     *
     * <p>lore 的每一行都是從 {@code weapon} 算出來的，沒有一個字是手寫的——這跟 shops.yml
     * 那條「血量、幾發打得破都由程式印上去」是同一個原則：手寫的數字會跟設定漂移，
     * 而且沒有人會發現。
     */
    public static void apply(ItemStack stack, AmmoVector vector, WeaponDef weapon) {
        stack.set(DataComponents.ITEM_MODEL, eggFor(vector));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        // 玩家取過名字就用他的，沒有就用「主材料 + 總數」推出來的那個
        String name = readName(stack).orElseGet(weapon::displayName);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(ChatFormatting.AQUA));

        List<Component> lines = new ArrayList<>();
        lines.add(line(String.format("傷害 %.1f ×%d 顆   濺射 %.1f 格",
                weapon.damage(), weapon.pellets(), weapon.splashRadius())));
        lines.add(line(String.format("初速 %.2f   重力 %.4f   45°射程 %.0f 格",
                weapon.projectileSpeed(), weapon.gravity(), weapon.maxRange())));
        lines.add(line(String.format("散佈 %.2f°   每秒 %.1f 發",
                weapon.spreadDegrees(), 20.0 / weapon.cooldownTicks())));
        lines.add(line("材料 " + materials(vector)));
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    /** 這疊彈藥被取過什麼名字。 */
    public static java.util.Optional<String> readName(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return java.util.Optional.empty();
        return data.copyTag().getString(NAME_TAG).filter(text -> !text.isBlank());
    }

    /** 取名。空字串 ＝ 改回程式推導的名字。 */
    public static void writeName(ItemStack stack, String name) {
        String trimmed = name == null ? "" : name.strip();
        net.minecraft.world.item.component.CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (trimmed.isEmpty()) {
                tag.remove(NAME_TAG);
            } else {
                tag.putString(NAME_TAG, trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed);
            }
        });
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    /** 材料清單。顯示中文名稱而不是 id——tooltip 是給玩家看的，不是給設定檔看的。 */
    private static String materials(AmmoVector vector) {
        MaterialRegistry registry = materials;
        StringBuilder sb = new StringBuilder();
        vector.counts().forEach((id, n) -> {
            if (!sb.isEmpty()) sb.append(' ');
            MaterialRegistry.MaterialDef def = registry == null ? null : registry.byId(id);
            sb.append(def == null ? id : def.displayName()).append('×').append(n);
        });
        return sb.toString();
    }

    /**
     * 這份設計長成哪一顆蛋。
     *
     * <p>用 {@link AmmoVector#key()} 的雜湊而不是亂數：外觀必須是配方的函數。
     * 取絕對值再取模——{@code hashCode} 可能是負的，而負的索引會直接丟例外。
     */
    private static Identifier eggFor(AmmoVector vector) {
        List<Identifier> all = eggs();
        if (all.isEmpty()) return BASE_ITEM;

        int index = Math.floorMod(vector.key().hashCode(), all.size());
        return all.get(index);
    }

    private static List<Identifier> eggs() {
        List<Identifier> cached = eggs;
        if (cached != null) return cached;

        List<Identifier> found = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getPath().endsWith("_spawn_egg")) {
                found.add(id);
            }
        }
        found.sort(Identifier::compareTo);
        eggs = List.copyOf(found);
        return eggs;
    }
}
