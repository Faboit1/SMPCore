package net.siftvanilla.siftcore.ui.dialog;

import java.util.Map;
import org.bukkit.entity.Player;

/** Renders {@link View}s for clients that cannot show Java dialogs (Bedrock via Floodgate forms). */
public interface FormBridge {

    boolean handles(Player player);

    /**
     * Shows the view. When the player answers, {@code response} receives the index into {@link View#allButtons()}
     * and the raw input values (String for text and choice ids, Boolean for toggles, Float for ranges).
     */
    void show(Player player, View view, Response response);

    /** A form answer. */
    @FunctionalInterface
    interface Response {
        void answer(int buttonIndex, Map<String, Object> values);
    }
}
