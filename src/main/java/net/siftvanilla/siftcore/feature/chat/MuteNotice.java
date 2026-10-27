package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.entity.Player;

/** Tells a muted player why their message didn't go out, with the reason and the time left. Thread-safe. */
final class MuteNotice {

    private MuteNotice() {
    }

    static void tell(Messenger messenger, Player player, MuteStatus.Mute mute) {
        String reason = mute.reason() == null || mute.reason().isBlank()
            ? messenger.lang().plain(ChatMessages.PM_NO_REASON) : ChatText.clean(mute.reason());
        if (mute.permanent()) {
            messenger.send(player, ChatMessages.PM_MUTED_PERMANENT, Arg.text("reason", reason));
            return;
        }
        long left = Math.max(1_000L, mute.until() - System.currentTimeMillis());
        messenger.send(player, ChatMessages.PM_MUTED, Arg.text("reason", reason), Arg.time("time", Duration.ofMillis(left)));
    }
}
