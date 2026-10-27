package net.siftvanilla.siftcore.feature.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The one combat timer: once a second, on an async thread, it shows "In combat 12s" to every tagged player where they
 * want it ({@link TimerDisplay}), tells players whose tag ran out that they are no longer in combat (in the style of
 * their {@code combat-end-notice}), and drops old hits and kill pairs. It touches no world state: it reads the tag
 * map, sends packets and hands boss bar changes to the players' threads.
 */
final class CombatTimer {

    private static final long PAIR_PRUNE_EVERY_MILLIS = 60_000L;

    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final TagTicker ticker;
    private final HitLog<ItemStack> hits;
    private final RecentPairs pairs;
    private final Messenger messenger;
    private final PlayerSettings prefs;
    private final TimerDisplay display;
    private volatile long lastTick;
    private long lastPairPrune;

    CombatTimer(Setting<CombatSettings> settings, CombatTags tags, TagTicker ticker, HitLog<ItemStack> hits, RecentPairs pairs,
                Messenger messenger, PlayerSettings prefs, TimerDisplay display) {
        this.settings = settings;
        this.tags = tags;
        this.ticker = ticker;
        this.hits = hits;
        this.pairs = pairs;
        this.messenger = messenger;
        this.prefs = prefs;
        this.display = display;
    }

    /** When the timer last ran (epoch millis), 0 before the first run. */
    long lastTick() {
        return this.lastTick;
    }

    void tick() {
        long now = System.currentTimeMillis();
        this.lastTick = now;
        CombatSettings s = this.settings.get();
        Map<UUID, CombatTags.Tag> snapshot = this.tags.snapshot();
        Map<UUID, Long> untils = new HashMap<>(snapshot.size() * 2);
        snapshot.forEach((player, tag) -> untils.put(player, tag.until()));
        TagTicker.Update update = this.ticker.tick(untils, now);
        for (TagTicker.Show show : update.shows()) {
            Player player = Bukkit.getPlayer(show.player());
            if (player == null) {
                continue;
            }
            if (s.actionBar()) {
                this.display.show(player, show.secondsLeft(), s.tagDuration());
            } else {
                // The server turned the timer off (tag.action-bar: false), perhaps while this player was in combat.
                this.display.hide(player);
            }
        }
        for (UUID ended : update.ended()) {
            Player player = Bukkit.getPlayer(ended);
            if (player == null) {
                continue;
            }
            this.display.hide(player);
            if (!player.isDead()) {
                AlertStyle style = this.prefs.get(ended, CombatFeature.END_NOTICE);
                MessageKey notice = endNotice(style);
                if (notice != null) {
                    this.messenger.alert(player, style, false, notice);
                }
            }
        }
        for (Map.Entry<UUID, CombatTags.Tag> entry : snapshot.entrySet()) {
            if (entry.getValue().until() <= now) {
                // Drops the expired entry unless the player was tagged again meanwhile.
                this.tags.get(entry.getKey());
            }
        }
        this.hits.prune(now - s.tagDuration().toMillis());
        if (now - this.lastPairPrune >= PAIR_PRUNE_EVERY_MILLIS) {
            this.lastPairPrune = now;
            this.pairs.prune(now - CombatSettings.MAX_PAIR_COOLDOWN.toMillis());
        }
    }

    /** The "no longer in combat" line in a {@code combat-end-notice} style: short for a title, null when it is off. */
    static MessageKey endNotice(AlertStyle style) {
        return switch (style) {
            case OFF -> null;
            case TITLE -> CombatMessages.TAG_ENDED_TITLE;
            default -> CombatMessages.TAG_ENDED;
        };
    }
}
