package com.jacky8399.elevator.utils;

import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.sign.SignSide;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class TextUtils {
    public static final int SIGN_MAX_WIDTH = 90;
    public static final int HANGING_SIGN_MAX_WIDTH = 60;
    public static final @NotNull LegacyComponentSerializer LEGACY = BukkitComponentSerializer.legacy();

    public static int measureWidth(Component component) {
        if (component == Component.empty()) return 0;
        record Entry(Component component, boolean bold) {}
        Deque<Entry> stack = new ArrayDeque<>();
        stack.addFirst(new Entry(component, false));
        Entry entry;
        int length = 0;
        while ((entry = stack.pollFirst()) != null) {
            Component visiting = entry.component;
            TextDecoration.State boldState = visiting.style().decoration(TextDecoration.BOLD);
            boolean bold = boldState != TextDecoration.State.NOT_SET ? boldState == TextDecoration.State.TRUE : entry.bold;
            if (visiting instanceof TextComponent textComponent) {
                for (char c : textComponent.content().toCharArray()) {
                    length += BitmapGlyphInfo.getBitmapGlyphInfo(c).width(bold) + 1;
                }
            }

            // push children onto stack
            for (Component child : visiting.children()) {
                stack.addFirst(new Entry(child, bold));
            }
        }
        return length;
    }

    private static final int SPACE_WIDTH = 4;
    public static Component toCentered(int maxWidth, @NotNull Component prefix, @NotNull Component content, @NotNull Component suffix) {
        // this is crazy
        int prefixWidth = measureWidth(prefix), contentWidth = measureWidth(content), suffixWidth = measureWidth(suffix);
        int padding = (maxWidth - contentWidth) / 2;
        int prefixRepeat = (padding - prefixWidth) / SPACE_WIDTH;
        int suffixRepeat = (padding - suffixWidth) / SPACE_WIDTH;
        return Component.textOfChildren(
                prefix,
                prefixRepeat <= 0 ? Component.empty() : Component.text(" ".repeat(prefixRepeat)),
                content,
                suffixRepeat <= 0 ? Component.empty() : Component.text(" ".repeat(suffixRepeat)),
                suffix
        );
    }

    public static void setLines(SignSide signSide, List<Component> components) {
        for (int i = 0; i < Math.min(4, components.size()); i++)
            signSide.setLine(i, LEGACY.serialize(components.get(i)));
    }
}
