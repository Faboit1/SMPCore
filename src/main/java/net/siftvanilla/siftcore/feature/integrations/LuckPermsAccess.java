package net.siftvanilla.siftcore.feature.integrations;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.integration.luckperms.LuckPermsHook;

/** Store rank grants through LuckPerms. Only constructed after LuckPerms was found. */
final class LuckPermsAccess implements RankAccess {

    private final LuckPermsHook hook;

    LuckPermsAccess(LuckPermsHook hook) {
        this.hook = hook;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean groupExists(String group) {
        return this.hook.groupExists(group);
    }

    @Override
    public CompletableFuture<Held> held(UUID player, String group) {
        return this.hook.hold(player, group).thenApply(hold -> new Held(hold.permanent(), hold.expiry()));
    }

    @Override
    public CompletableFuture<Boolean> ensure(UUID player, String group, Instant until) {
        return this.hook.ensure(player, group, until);
    }

    @Override
    public CompletableFuture<Boolean> limit(UUID player, String group, boolean removePermanent, boolean cutTimed, Instant cutTo) {
        return this.hook.limit(player, group, removePermanent, cutTimed, cutTo);
    }
}
