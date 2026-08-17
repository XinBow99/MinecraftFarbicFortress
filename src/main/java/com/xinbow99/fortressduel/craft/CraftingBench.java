package com.xinbow99.fortressduel.craft;

import com.xinbow99.fortressduel.core.ConfigManager;
import com.xinbow99.fortressduel.npc.SongDisc;
import com.xinbow99.fortressduel.util.Msg;
import com.xinbow99.fortressduel.weapon.WeaponDef;
import com.xinbow99.fortressduel.weapon.WeaponItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 原版工作台上的彈藥組合。
 *
 * <p>玩家把材料（或已經做好的原型）擺進 3×3，結果格就會出現一份新的設計。沒有固定的擺法：
 * 位置無所謂，**只有「總共放了幾個哪種材料」有意義**，因為設計的身分就是那個向量。
 *
 * <h2>為什麼不註冊自訂配方型別</h2>
 * 這個專案的前提是**客戶端不裝 mod**。註冊新的 recipe type 之後，伺服器同步配方時客戶端
 * 無法反序列化，等於強制所有人裝 mod。改成 mixin 進選單自己算結果，客戶端看到的只是
 * 「結果格裡出現了一個物品」——純物品同步，原版客戶端完全無感。
 *
 * <h2>為什麼一次只產出一個</h2>
 * 產出一疊的話會變成無限複製：9 個材料做出 8 發、每發都記著「9 個材料」，把那 8 發丟回去
 * 就是 72 個材料。而原版的合成**每一格只消耗 1 個**，所以「一次最多 9 個材料 → 一個產物」
 * 天生就是守恆的。要突破 9 格的上限就得先做兩個原型再合起來——那是一個看得見的工程，
 * 不是漏洞。
 *
 * <p>數量交給軍火商量產（用錢買），工作台只負責**設計**。
 */
public final class CraftingBench {

    /** 至少要放幾個材料才算一份設計。1 個的話等於把單一材料變成彈藥，那不是組合。 */
    private static final int MIN_MATERIALS = 2;

    /**
     * 一份設計最多帶幾份音效。
     *
     * <p>純粹是效能與耳朵：九格全塞光碟等於九軌同時播，加上每次開火九個封包。三首已經
     * 夠混出玩家想要的那種吵，再多只是糊成一片。
     */
    private static final int MAX_SONGS = 3;

    /**
     * 標記「這是工作台做出來的原型」。
     *
     * <p>只有原型可以**當材料丟回工作台**；軍火商量產出來的那一疊不行。
     *
     * <p>這條分界是防套利的：量產的價格是照材料數線性算的，如果量產品也能當材料，
     * 玩家就能買一批便宜的成品拆回去當高階材料用，繞過原本的材料成本。分開之後
     * 「工作台做設計、軍火商做彈藥」不只是流程上的說法，它在經濟上也是兩種東西。
     */
    private static final String PROTOTYPE_TAG = "fortressduel_prototype";

    /**
     * 已經看過操作提示的人。
     *
     * <p>整套流程有兩步是**沒有視覺入口**的：取名要打指令、量產要拿去給軍火商。工作台
     * 只會給你一個東西，不講的話玩家會以為做完就結束了。只講第一次——之後每合成一次
     * 都洗一遍就變成噪音了。
     */
    private static final java.util.Set<java.util.UUID> hinted = new java.util.HashSet<>();

    /** 玩家 → 最後一次跟他講過的失敗原因。見 {@link #explain}。 */
    private static final java.util.Map<java.util.UUID, String> lastProblem = new java.util.HashMap<>();

    private static ConfigManager config;

    private CraftingBench() {
    }

    /** Mixin 織進原版類別，沒有建構子可以注入，只能走這道靜態橋（跟 BowHooks 同一個做法）。 */
    public static void install(ConfigManager config) {
        CraftingBench.config = config;
    }

    /**
     * 這個格子的判定結果。
     *
     * <p>分成三種而不是「有／沒有」，是因為**失敗有兩種完全不同的意思**：
     *
     * <ul>
     *   <li>{@link Offer#PASS} — 格子裡有我們不認得的東西，這是一次原版合成。要**安靜地**
     *       放行，玩家在對戰之外照常做工作台、做梯子，不該被我們洗訊息。</li>
     *   <li>{@code problem} — 全部都是我們認得的東西，但組不起來。這種一定要講：它跟成功
     *       之間的差別玩家看不見，畫面上兩者都只是「結果格空著」。</li>
     * </ul>
     *
     * <p>少了這個分別就是遞迴合成那個坑的根源——把量產彈藥丟回工作台跟把石頭丟回工作台，
     * 在舊的寫法裡是同一個回傳值，於是「這個不能回收」看起來就跟「壞掉了」一模一樣。
     */
    public record Offer(ItemStack result, String problem) {

        /** 交給原版，不出聲。 */
        static final Offer PASS = new Offer(ItemStack.EMPTY, null);

        static Offer made(ItemStack design) {
            return new Offer(design, null);
        }

        static Offer problem(String message) {
            return new Offer(ItemStack.EMPTY, message);
        }
    }

    /**
     * 這個格子裡的東西組得出什麼。
     *
     * <p>只要格子裡有**任何一個我們不認得的東西**就直接放行——玩家在對戰之外仍然要能
     * 正常合成，而工作台是共用的。
     */
    public static Offer offerFor(Container grid) {
        if (config == null) return Offer.PASS;

        AmmoVector vector = AmmoVector.EMPTY;
        int slotsUsed = 0;

        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;
            slotsUsed++;

            // 已經做好的**原型**：把它整個向量加進來。「拿成品當材料」就是這一行
            AmmoVector existing = AmmoVector.read(stack).orElse(null);
            if (existing != null) {
                // 帶著向量卻沒有原型標記 ＝ 軍火商量產的那一疊。這是刻意擋掉的
                // （見 PROTOTYPE_TAG），但它跟原型長得一模一樣，所以一定要說出來
                if (!isPrototype(stack)) {
                    return Offer.problem("軍火商量產的彈藥不能當材料回收——只有工作台做出來的設計圖可以。");
                }
                vector = vector.plus(existing);
                continue;
            }

            // 音樂家的光碟：把那首歌記進向量。它不推任何一條軸，純粹是開火時放什麼
            SongDisc.Song song = SongDisc.read(stack).orElse(null);
            if (song != null) {
                vector = vector.plus(AmmoVector.songKey(song.sound()), 1);
                continue;
            }

            MaterialRegistry.MaterialDef material =
                    config.materials().byItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (material == null) return Offer.PASS;   // 不認得 → 交給原版

            vector = vector.plus(material.id(), 1);
        }

        if (slotsUsed == 0) return Offer.PASS;
        if (vector.total() < MIN_MATERIALS) {
            // 光碟不算數：它不推任何一條軸，光碟加光碟組不出一發子彈
            return Offer.problem("一份設計至少要放兩個材料（光碟不算，它只決定開火的聲音）。");
        }
        if (vector.songCount() > MAX_SONGS) {
            return Offer.problem("一份設計最多帶 " + MAX_SONGS + " 首歌，這裡有 "
                    + vector.songCount() + " 首。");
        }

        WeaponDef weapon = config.designs().toWeapon(vector);
        ItemStack design = WeaponItems.createDesignAmmo(vector, weapon, 1);
        markPrototype(design);
        return Offer.made(design);
    }

    /**
     * 把組不起來的原因講給玩家聽，但**同一句話不連著講第二次**。
     *
     * <p>格子每動一下就重算一次，而玩家是一格一格擺的——不擋的話「至少要放兩格」會在擺
     * 第一格的當下洗出一整串。只記最後講過的那一句：換成別的原因、或成功做出東西之後
     * （見 {@link #forgetProblem}）就會重新開口。
     */
    public static void explain(Player player, String problem) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (problem.equals(lastProblem.get(sp.getUUID()))) return;

        lastProblem.put(sp.getUUID(), problem);
        sp.sendSystemMessage(Msg.warn(problem));
    }

    /** 成功做出東西了；下次再撞到同樣的問題要重新講一次。 */
    public static void forgetProblem(Player player) {
        if (player instanceof ServerPlayer sp) {
            lastProblem.remove(sp.getUUID());
        }
    }

    /** 把這疊東西標成原型（可以當材料再組）。 */
    public static void markPrototype(ItemStack stack) {
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean(PROTOTYPE_TAG, true));
    }

    public static boolean isPrototype(ItemStack stack) {
        net.minecraft.world.item.component.CustomData data =
                stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(PROTOTYPE_TAG).orElse(false);
    }

    /**
     * 這一份產物是不是我們算出來的。
     *
     * <p>用「身上有沒有材料向量」判斷而不是比對物品：原版的合成結果永遠不帶這個標記，
     * 所以這個判斷不會誤判到任何一條原版配方。
     */
    public static boolean isOurs(ItemStack result) {
        return AmmoVector.read(result).isPresent();
    }

    /** 第一次做出設計時，把接下來的兩步講一次。 */
    public static void hintOnce(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!hinted.add(sp.getUUID())) return;

        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.good("做出一份設計了。接下來："));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.info(
                "  1. 拿在主手打 /duel name <名稱> 給它取個名字（可略過）"));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.info(
                "  2. 拿著它右鍵軍火商登記，他就會開始量產，架上會多一格"));
        sp.sendSystemMessage(com.xinbow99.fortressduel.util.Msg.plain(
                "  設計圖登記完還留在你手上，可以丟回工作台當材料再組；"
                        + "但量產出來的彈藥不能回收。",
                net.minecraft.ChatFormatting.DARK_GRAY));
    }

    /**
     * 拿走產物之後把材料吃掉：**每一格各消耗 1 個**，跟原版合成一樣。
     *
     * <p>要自己做是因為原版那條路要走配方物件，而我們沒有註冊任何配方。
     */
    public static void consume(Container grid) {
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;

            // 光碟不吃掉。它是買斷制的（見 SongDisc：不會用掉、對戰結束也不收回），
            // 而合成本來就不該把那個承諾收回去。
            //
            // 這不會破壞「每格消耗 1」那條守恆：那條規則存在的目的是防材料複製，而光碟
            // 不提供任何數值、也拆不回材料——豁免它換不到任何東西，只省下一趟回去點歌
            if (SongDisc.read(stack).isPresent()) continue;

            grid.removeItem(i, 1);
        }
    }
}
