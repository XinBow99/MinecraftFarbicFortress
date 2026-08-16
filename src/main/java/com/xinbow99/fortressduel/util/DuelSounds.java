package com.xinbow99.fortressduel.util;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * 模組自己的音效。
 *
 * <p>音檔（OGG）放在 {@code assets/fortress-duel/sounds/}，由 {@code sounds.json} 宣告，
 * 播的時候走的是**原版的音效系統**——就是那個能播任何資源包音效的路。
 *
 * <p><b>刻意不註冊進 {@code BuiltInRegistries.SOUND_EVENT}。</b> 音效註冊表是會同步給客戶端的，
 * 多一筆客戶端沒有的資料會讓沒裝這個模組的人**連不進來**（Fabric 的登記表同步會直接把他擋掉，
 * 畫面上就是叫他去裝模組）。這個模組的其他東西都是純伺服器端的，不該為了一顆點歌按鈕
 * 變成「所有人都必須裝」。
 *
 * <p>所以這裡用 {@link Holder#direct}：音效 id 是**直接寫在封包裡**送過去的，不查表。
 * 客戶端拿到 id 之後去自己的資源包裡找——找得到就播，找不到就安靜，兩種情況都不會斷線。
 * 也就是說想聽到聲音的人裝這個模組（模組的 assets 就是資源包）或裝伺服器資源包就行，
 * 不想裝的人照樣玩。
 */
public final class DuelSounds {

    private DuelSounds() {
    }

    /**
     * 把設定檔寫的音效 id 包成可以直接送出去的音效。
     *
     * @return id 不合法就回 {@code null}
     */
    public static Holder<SoundEvent> byId(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : Holder.direct(SoundEvent.createVariableRangeEvent(parsed));
    }
}
