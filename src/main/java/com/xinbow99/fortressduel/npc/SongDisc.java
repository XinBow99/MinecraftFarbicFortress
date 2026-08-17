package com.xinbow99.fortressduel.npc;

import com.xinbow99.fortressduel.battle.Duel;
import com.xinbow99.fortressduel.battle.DuelManager;
import com.xinbow99.fortressduel.util.DuelSounds;
import com.xinbow99.fortressduel.util.Msg;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 音樂家賣的光碟：拿在手上右鍵就放一首，**全場都聽得到**。
 *
 * <p>比起在櫃子上直接點播，光碟的差別是**時機由你決定**。歌本身不影響勝負，但「什麼時候放」
 * 影響得了氣氛——衝進對面院子的那一刻按下去，跟站在商店前面點一首，是兩件事。所以買的是
 * 一個隨身的按鈕，不是一次播放。
 *
 * <p>買到就是你的，**不會用掉、對戰結束也不收回**（沒有打 {@code DuelItems} 的標記）。
 * 一次性的話玩家會捨不得用，而捨不得用的道具等於不存在；而它給不了任何戰鬥優勢，
 * 留著也不破壞平衡。
 *
 * <h2>為什麼不用原版唱片機那條路</h2>
 * 光碟長得像原版唱片，但 {@link DataComponents#JUKEBOX_PLAYABLE} 是被拔掉的——留著的話塞進
 * 唱片機會放出**原版那一首**，而不是它上面寫的那一首，那是最難查的一種錯。拔掉之後唱片機
 * 不收它，右鍵唱片機會落回下面的播放路徑，照樣放對的歌。
 */
public final class SongDisc {

    /** 存在 CUSTOM_DATA 裡的鍵。加前綴避免跟別的 mod 撞名。 */
    private static final String TAG = "fortressduel_song";
    private static final String SOUND_KEY = "sound";
    private static final String LENGTH_KEY = "length";

    /** 一張光碟記著的東西：放哪個音效、放多久。 */
    public record Song(String sound, int lengthSeconds) {
    }

    /**
     * 音效 id → 歌名。
     *
     * <p>合進彈藥的音效在向量裡只留得下 id（那是設計的身分，必須穩定），但彈藥的說明要給
     * 人看——沒有這張表，玩家看到的是 {@code fortress-duel:xue_hua_piao_piao} 而不是「雪花飄飄」。
     *
     * <p>由 {@link SongShop#from} 在載入曲目時填，所以它跟著 songs.yml 走，
     * {@code /duel reload} 之後也是對的。
     */
    private static volatile java.util.Map<String, String> names = java.util.Map.of();

    public static void installNames(java.util.Map<String, String> soundToName) {
        names = java.util.Map.copyOf(soundToName);
    }

    /** 這個音效的歌名；不認得就回傳 id 本身（總比顯示空白好）。 */
    public static String nameOf(String sound) {
        return names.getOrDefault(sound, sound);
    }

    private SongDisc() {
    }

    /**
     * 右鍵播放。
     *
     * <p>只掛 {@link UseItemCallback} 就夠了，不另外掛方塊那條：對著工作台按右鍵時原版的
     * 方塊互動會先贏（該開的還是開得起來），對著地面按則會落回物品使用——跟丟雪球
     * 完全一樣的判定，玩家不用學新規則。
     */
    public static void register(DuelManager duels) {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            // 這個事件兩端都會跑，判斷玩家的型別就等於把客戶端那一次濾掉了
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            Song song = read(stack).orElse(null);
            if (song == null) return InteractionResult.PASS;

            play(sp, duels, stack, song);
            // 一律 SUCCESS：失敗（不在對戰中、上一首還沒放完）也不該讓這個右鍵掉回原版行為，
            // 那會變成「放不出歌的時候光碟忽然可以塞進唱片機」
            return InteractionResult.SUCCESS;
        });
    }

    private static void play(ServerPlayer player, DuelManager duels, ItemStack stack, Song song) {
        Holder<SoundEvent> sound = DuelSounds.byId(song.sound());
        if (sound == null) {
            player.sendSystemMessage(Msg.warn("這張光碟壞了（音效 id 不合法：" + song.sound() + "）"));
            return;
        }

        Duel duel = duels.duelOf(player);
        if (duel == null) {
            duels.notify(player, Msg.plain("要在對戰中才放得出來。", ChatFormatting.GRAY));
            return;
        }

        // 一次只放一首，跟櫃子上點播共用同一條規則——不擋的話連點會疊出好幾軌同一首歌
        if (!duel.playMusic(sound, song.lengthSeconds() * 20)) {
            duels.notify(player, Msg.plain("這首還沒放完。", ChatFormatting.GRAY));
            return;
        }
        duels.notify(player, Msg.plain("♪ " + name(stack), ChatFormatting.LIGHT_PURPLE));
    }

    /** 做一張光碟。圖示、名字、歌都照商品那一格寫的來。 */
    public static ItemStack create(ShopEntry entry) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM
                .getOptional(Identifier.parse(entry.item()))
                .orElse(Items.MUSIC_DISC_13));

        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag song = new CompoundTag();
            song.putString(SOUND_KEY, entry.sound());
            song.putInt(LENGTH_KEY, entry.lengthSeconds());
            root.put(TAG, song);
        });

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(entry.displayName()).withStyle(ChatFormatting.LIGHT_PURPLE));

        List<Component> lore = new ArrayList<>();
        if (!entry.lore().isEmpty()) {
            lore.add(Component.literal(entry.lore()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lore.add(Component.literal("右鍵播放，全場都聽得到").withStyle(ChatFormatting.GRAY));
        lore.add(Component.literal("放得完就能再放一次，不會用掉").withStyle(ChatFormatting.DARK_GRAY));
        stack.set(DataComponents.LORE, new ItemLore(lore));

        // 見類別註解：留著的話唱片機會放成原版那一首
        stack.remove(DataComponents.JUKEBOX_PLAYABLE);
        return stack;
    }

    /** 這疊東西是不是一張光碟；不是就回空的。 */
    public static Optional<Song> read(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();

        return data.copyTag().getCompound(TAG).flatMap(tag -> tag.getString(SOUND_KEY)
                .filter(sound -> !sound.isBlank())
                .map(sound -> new Song(sound, Math.max(1, tag.getIntOr(LENGTH_KEY, 10)))));
    }

    /** 這張光碟上寫的歌名（給訊息用）。 */
    private static String name(ItemStack stack) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        return custom == null ? "光碟" : custom.getString();
    }

    /** 這個人身上有沒有這一張了。重複買同一首沒有意義——它不會用掉。 */
    public static boolean owns(ServerPlayer player, String sound) {
        for (ItemStack stack : player.getInventory()) {
            if (read(stack).filter(song -> song.sound().equals(sound)).isPresent()) return true;
        }
        return false;
    }
}
