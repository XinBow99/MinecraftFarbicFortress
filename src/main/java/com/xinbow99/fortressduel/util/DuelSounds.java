package com.xinbow99.fortressduel.util;

import com.xinbow99.fortressduel.FortressDuel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * 模組自己的音效。
 *
 * <p>走的是**原版的音效系統**，沒有另外播音檔的東西：音檔（OGG）放在
 * {@code assets/fortress-duel/sounds/}，由 {@code sounds.json} 宣告，這裡只是把同一個 id
 * 註冊成 {@link SoundEvent}，讓伺服器端可以像 {@code SoundEvents.NOTE_BLOCK_PLING} 那樣播它。
 *
 * <p>因此**客戶端要裝這個模組才聽得到**——模組的 assets 就是客戶端的資源包，沒裝的人
 * 收到的是一個他查不到的音效 id，不會出錯，只是沒有聲音。
 */
public final class DuelSounds {

    /** 商店裡那顆音樂按鈕放的曲子。 */
    public static final Holder.Reference<SoundEvent> CHINESE = register("chinese");

    private DuelSounds() {
    }

    /**
     * 讓 class 被載入、上面的常數跟著註冊進去。
     *
     * <p>註冊表在伺服器凍結它之前就要寫完，所以這件事必須發生在 {@code onInitialize} 裡，
     * 不能等到第一次有人點商店才做。
     */
    public static void register() {
    }

    private static Holder.Reference<SoundEvent> register(String path) {
        Identifier id = FortressDuel.id(path);
        return Registry.registerForHolder(BuiltInRegistries.SOUND_EVENT, id,
                SoundEvent.createVariableRangeEvent(id));
    }
}
