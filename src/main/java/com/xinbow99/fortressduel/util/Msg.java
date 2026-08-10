package com.xinbow99.fortressduel.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 訊息樣式。整個 mod 對玩家講話都走這裡，避免每個地方各自挑顏色。 */
public final class Msg {

    private Msg() {}

    private static final Component PREFIX =
            Component.literal("[要塞對戰] ").withStyle(ChatFormatting.GOLD);

    public static MutableComponent info(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent good(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.GREEN));
    }

    public static MutableComponent warn(String text) {
        return Component.empty().append(PREFIX).append(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    /** 沒有前綴的純色文字，用在動作列（action bar）與 boss 血條標題。 */
    public static MutableComponent plain(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }
}
