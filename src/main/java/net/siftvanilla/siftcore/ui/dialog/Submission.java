package net.siftvanilla.siftcore.ui.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** A validated button click. Handlers run on the player's thread. */
public interface Submission {

    Player player();

    /** Validated input values of the view that was submitted. */
    FormValues values();

    /** The view that was submitted. */
    View view();

    /** Shows another view (replacing this one). */
    void show(View next);

    /** Re-opens the submitted view with an error line, keeping what the player typed. */
    void error(Component message);

    /** Closes the dialog. If a handler neither shows nor closes, the router closes it. */
    void close();
}
