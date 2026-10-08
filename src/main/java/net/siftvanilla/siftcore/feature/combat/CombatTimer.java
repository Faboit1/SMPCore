package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The one combat timer: once a second, on an async thread, it shows "In combat 12s" to every tagged player, tells
 * players whose tag ran out that they are no longer in combat, and drops old hits and kill pairs. It touches no world
 * state: it reads the tag map and only sends packets.
 */
final class CombatTimer {

    private static final long PAIR_PRUNE_EVERY_MILLIS = 60_000L;

    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final TagTicker ticker;
    private final HitLog<ItemStack> hits;
    private final RecentPairs pairs;
    private final Messenger messenger;
    private volatile long lastTick;
    private long lastPairPrune;

    CombatTimer(Setting<CombatSettings> settings, CombatTags tags, TagTicker ticker, HitLog<ItemStack> hits, RecentPairs pairs,
                Messenger messenger) {
        this.settings = settings;
        this.tags = tags;
        this.ticker = ticker;
        this.hits = hits;
        this.pairs = pairs;
        this.messenger = messenger;
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
        if (s.actionBar()) {
            for (TagTicker.Show show : update.shows()) {
                Player player = Bukkit.getPlayer(show.player());
                if (player != null) {
                    this.messenger.send(player, CombatMessages.TAG_ACTION_BAR, Arg.time("time", Duration.ofSeconds(show.secondsLeft())));
                }
            }
        }
        for (UUID ended : update.ended()) {
            Player player = Bukkit.getPlayer(ended);
            if (player != null && !player.isDead()) {
                this.messenger.send(player, CombatMessages.TAG_ENDED);
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
}
