package dev.bedwarscompanion.capture;

import java.lang.reflect.Field;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Reads vanilla's displayed footer without installing upstream's Mixin stack. */
final class TabFooterReader {
    private Field footer;
    private boolean unavailable;
    String read(GuiPlayerTabOverlay overlay) {
        if (unavailable || overlay == null) return null;
        try {
            if (footer == null) footer = ReflectionHelper.findField(GuiPlayerTabOverlay.class, "footer", "field_175255_h");
            IChatComponent value = (IChatComponent) footer.get(overlay);
            return value == null ? null : value.getFormattedText();
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            return null;
        }
    }
}
