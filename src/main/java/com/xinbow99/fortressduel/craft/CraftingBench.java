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
 * <h2>一格疊幾個就算幾個</h2>
 * 放九個火藥在同一格，那份設計就記著九個火藥，而那九個會被整疊吃掉。所以一次合成的上限
 * 是九疊，不是九個。
 *
 * <p>守恆**不是**靠「一格只算一個」維持的，是靠**算的規則與扣的規則是同一條**：
 * {@link #offerFor} 讀 {@code getCount()}，{@link #consume} 就扣 {@code getCount()}。
 * 只要有一邊看數量、另一邊不看，就會變成複製（算得多扣得少）或蒸發（反過來）。
 *
 * <h2>為什麼一次只產出一個</h2>
 * 產出一疊的話會變成無限複製：一份設計做出 8 發、每發都記著整個向量，把那 8 發丟回去
 * 就是 8 倍的材料。所以產物永遠是**一個**，它記著的就是這次吃掉的那些。
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

    /**
     * 結果格裡放的那一份是我們算出來的工作台。
     *
     * <p>看起來多餘——產物身上就帶著材料向量，直接問它不就好了？**不行，而且那正是
     * shift 拿走會噴東西的原因**：原版 shift 取物是先把結果格那疊搬進背包、再呼叫
     * {@code onTake}，傳進來的已經是一個被搬空的殼；而 {@code ItemStack.getComponents()}
     * 對空堆疊一律回傳 {@code EMPTY}，於是那份設計的向量在那一刻是讀不到的。
     *
     * <p>讀不到就等於「這不是我們的東西」，原版的 {@code onTake} 就接手了：它每格只扣 1、
     * 扣完觸發重算、我們又補一份新的設計進結果格，而 shift 的迴圈只比對物品種類
     * （{@code isSameItem} 不看 component），看到格子又滿了就再跑一輪。背包塞爆之後
     * 原版在 {@code quickMoveStack} 結尾 {@code player.drop} 把剩下的丟到地上——那就是
     * 「一堆東西爆出來」。
     *
     * <p>所以這裡記的是**當初的判斷**，而不是事後再推一次。判斷只做一次、在
     * {@code CraftingMenuMixin} 決定要不要接手的那一刻，之後誰也改不動它。
     *
     * <p>用弱參照的鍵：工作台選單關掉之後這裡不該是它活著的唯一理由。
     */
    private static final java.util.Set<Container> authored =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /**
     * 正在扣材料。
     *
     * <p>{@link #consume} 一格一格扣，而每扣一格容器都會通知選單重算一次——那幾次重算看到的
     * 是**扣到一半的格子**，會算出一份不完整的設計塞回結果格。最後一次（格子空了）會自己
     * 清掉，所以不擋也不會錯，但中間那幾份是白算的，而且會在原版處理點擊的過程中反覆
     * 送封包給客戶端。扣材料要嘛整件做完、要嘛沒發生。
     */
    private static boolean consuming = false;

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
                vector = vector.plus(existing, stack.getCount());
                continue;
            }

            // 音樂家的光碟：把那首歌記進向量。它不推任何一條軸，純粹是開火時放什麼。
            //
            // 跟材料同一條規則（照數量算、照數量扣），不特別處理——而原版唱片的堆疊上限
            // 本來就是 1，所以實際上一格永遠就是一張
            SongDisc.Song song = SongDisc.read(stack).orElse(null);
            if (song != null) {
                vector = vector.plus(AmmoVector.songKey(song.sound()), stack.getCount());
                continue;
            }

            MaterialRegistry.MaterialDef material =
                    config.materials().byItem(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (material == null) return Offer.PASS;   // 不認得 → 交給原版

            vector = vector.plus(material.id(), stack.getCount());
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
     * 記下結果格裡這一份是不是我們算的。只有 {@code CraftingMenuMixin} 該呼叫。
     *
     * <p>要**兩邊都記**：不是我們的時候也要清掉。少了清除，玩家把設計拿走之後再擺一份
     * 真正的原版配方進去，那個舊旗標會讓我們把原版的產物當成自己的去扣材料。
     */
    public static void rememberResult(Container grid, boolean ours) {
        if (ours) {
            authored.add(grid);
        } else {
            authored.remove(grid);
        }
    }

    /**
     * 現在正在扣材料嗎。扣的過程中不要重算結果格，見 {@link #consuming}。
     */
    public static boolean busy() {
        return consuming;
    }

    /**
     * 玩家拿走的這一份是不是我們的設計。
     *
     * <p>先看手上那一份、讀不到才回頭問旗標：一般點擊拿走時傳進來的是完好的產物，直接
     * 看它最準；shift 拿走時它已經被搬空了（見 {@link #authored}），只剩旗標可信。
     */
    public static boolean resultWasOurs(Container grid, ItemStack taken) {
        return isOurs(taken) || authored.contains(grid);
    }

    /**
     * 拿走產物之後把材料吃掉：**整疊吃掉**，跟 {@link #offerFor} 算的時候一樣。
     *
     * <p>要自己做是因為原版那條路要走配方物件，而我們沒有註冊任何配方。
     */
    public static void consume(Container grid) {
        consuming = true;
        try {
            consumeAll(grid);
        } finally {
            consuming = false;
        }
        // 格子空了，結果格就不該再留著東西。扣的過程中重算被擋掉了，這裡補一次
        rememberResult(grid, false);
    }

    private static void consumeAll(Container grid) {
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;

            // **整疊吃掉**，跟 offerFor 那邊「整疊都算進去」是同一條規則。
            // 兩邊一起看數量才守恆——只要有一邊看、另一邊不看，就會變成複製或蒸發。
            //
            // 光碟也一樣吃掉。它在右鍵播放那條路上仍然是買斷的（放完可以再放、對戰結束
            // 不收回），但合進彈藥是**另一回事**：那份設計會永久帶著這首歌、量產出來的
            // 每一發都帶著，所以那張光碟是被用掉的。豁免它只會讓工作台多一條要記的例外
            grid.removeItem(i, stack.getCount());
        }
    }
}
