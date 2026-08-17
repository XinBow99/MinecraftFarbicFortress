package com.xinbow99.fortressduel.util;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

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

    /**
     * 在世界上某一點放一個音效，走的是**直送封包**那條路。
     *
     * <p>為什麼不用 {@code level.playSound}：那條要先去 {@code BuiltInRegistries.SOUND_EVENT}
     * 查表，而這個模組的歌**刻意沒有註冊進去**（理由見類別註解），所以查出來永遠是 null，
     * 結果是安靜地沒有聲音——最難查的那一種。直送封包對原版音效與模組音效都成立，
     * 所以開火音效統一走這裡，不用分兩條路。
     *
     * <p>座標是世界座標，衰減由客戶端自己算，所以聽得出來是從哪個方向打過來的。
     * {@code radius} 只決定「送給誰」——送給太遠的人只是浪費一個封包，不會有聲音。
     */
    public static void playAt(ServerLevel level, Vec3 pos, Identifier sound, SoundSource source,
                              float volume, float pitch, double radius) {
        Holder<SoundEvent> holder = Holder.direct(SoundEvent.createVariableRangeEvent(sound));
        double radiusSqr = radius * radius;
        long seed = level.getRandom().nextLong();

        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(pos) > radiusSqr) continue;
            player.connection.send(new ClientboundSoundPacket(holder, source,
                    pos.x, pos.y, pos.z, volume, pitch, seed));
        }
    }

    /**
     * 把某個音效切掉。
     *
     * <p>**這是按「id ＋ 頻道」停的，不是按單一次播放停的。** 同一首歌同時有兩次在放的時候，
     * 先到期的那一個會把另一個也一起切掉——原版的停止封包只吃 id 與頻道，沒有辦法只停自己
     * 那一次。
     *
     * <p>正因為這樣，開火音效走 {@code PLAYERS} 而光碟走 {@code RECORDS}：不分開的話，
     * 隨便誰開一槍就會把你正在放的那首歌掐掉。
     *
     * <p>送給整個世界的人：沒在聽的人收到也不會發生任何事，而要算「剛才是誰收到了」得多記
     * 一份名單，那份名單在玩家中途離線時就對不起來了。
     */
    public static void stop(ServerLevel level, Identifier sound, SoundSource source) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundStopSoundPacket(sound, source));
        }
    }
}
