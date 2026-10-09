package net.siftvanilla.siftcore.feature.crates;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.CrateOpenEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.Handoffs;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Opening a crate. One key is spent and the drawn reward paid out in a single economy transaction: the key count,
 * money or shards (ledger source {@code crate_reward}), keys of another crate, the reward items (into the claim box),
 * the reward's console commands ({@link RewardCommands}) and the crate log row are applied and stored together, or not
 * at all. Only after that transaction is stored are the items claimed into the inventory (when they fit), the command
 * rewards run (or, if the server stops first, at the next start) and the win announced.
 * <p>
 * Everything is checked again inside the transaction (the player still has a key, the crate still exists), so
 * double clicks, two menus at once or a reload in between can never spend a key twice or pay a reward without one.
 */
final class CrateOpener {

    /** Ledger kind of money and shards paid by crates (counted as earnings by the stats feature). */
    static final String LEDGER_KIND = "crate_reward";
    private static final String CRATE_GONE = "crate_gone";

    /** How an opening ended. Delivered on the player's thread. */
    sealed interface Result permits Won, Refused {
    }

    /**
     * The reward was stored and handed over.
     *
     * @param inClaimBox stacks that did not fit and wait in the claim box
     * @param keysLeft   keys of this crate left after the opening
     */
    record Won(Crate crate, Reward reward, Rarity rarity, int inClaimBox, int keysLeft) implements Result {
    }

    /**
     * What several openings in a row won ({@link #openMany}).
     *
     * @param crate     the crate (null only when it no longer exists and nothing was opened)
     * @param wins      every opening, in order
     * @param stoppedBy why the openings stopped before the asked number, or null when they did not (or simply ran out
     *                  of keys after at least one)
     * @param keysLeft  keys of this crate left afterwards
     */
    record Batch(Crate crate, List<Won> wins, Refused stoppedBy, int keysLeft) {
        Batch {
            wins = List.copyOf(wins);
        }

        /** Stacks that went to the claim box over all openings. */
        int inClaimBox() {
            int total = 0;
            for (Won won : this.wins) {
                total += won.inClaimBox();
            }
            return total;
        }
    }

    /** Nothing happened (no key was spent), for this reason. */
    record Refused(MessageKey key, List<Arg> args) implements Result {
        Refused(MessageKey key, Arg... args) {
            this(key, List.of(args));
        }

        Arg[] argArray() {
            return this.args.toArray(new Arg[0]);
        }
    }

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final KeyService keys;
    private final RewardItems items;
    private final Handouts handouts;
    private final RewardCommands commands;
    private final CrateLog log;
    private final CrateText text;
    private final VanishStatus vanish;
    private final CombatStatus combat;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    /**
     * @param vanish        vanished winners are never announced
     * @param combat        players in combat can't open crates (when {@code block-in-combat} is on)
     */
    CrateOpener(Services services, Setting<CratesSettings> settings, KeyService keys, RewardItems items, Handouts handouts,
                RewardCommands commands, CrateLog log, CrateText text, VanishStatus vanish, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.keys = keys;
        this.items = items;
        this.handouts = handouts;
        this.commands = commands;
        this.log = log;
        this.text = text;
        this.vanish = vanish;
        this.combat = combat;
    }

    /**
     * The receipt of several openings in a row (a single opening already sent its own), plus the reason they stopped
     * early, if they did. The receipt shows where the player's Crate win receipt setting says: the whole list in chat,
     * how many and the rarest reward above the hotbar, or nothing. Rewards that went to the claim box are always told
     * in chat. The stop reason goes where refusals go, except in chat when the receipt took the action bar (it would
     * replace the receipt there at once). Player's thread.
     */
    void receipt(Player player, Batch batch) {
        // The list of rewards is built here (after the openings were stored, outside any click or command) and sent
        // inside the receipt: built for the player, so money rewards read in their money format.
        this.services.lang().viewing(player, () -> sendReceipt(player, batch));
    }

    private void sendReceipt(Player player, Batch batch) {
        AlertStyle shown = AlertStyle.OFF;
        if (batch.wins().size() > 1) {
            Arg count = Arg.number("count", batch.wins().size());
            Arg name = Arg.text("name", batch.crate().name());
            AlertStyle style = this.services.settings().get(player, CratePlayerSettings.RECEIPT);
            if (batch.inClaimBox() > 0 || style == AlertStyle.CHAT) {
                Component rewards = Component.join(JoinConfiguration.newlines(), this.text.wins(batch.wins()));
                this.services.messenger().send(player, batch.inClaimBox() > 0 ? CratesMessages.BATCH_WON_CLAIM_BOX : CratesMessages.BATCH_WON,
                    count, name, Arg.component("rewards", rewards));
                shown = AlertStyle.CHAT;
            } else {
                this.services.messenger().alert(player, style, CratesMessages.BATCH_WON_SHORT, count, name,
                    this.text.rewardArg("reward", rarest(batch).reward()));
                shown = style;
            }
        } else if (batch.wins().size() == 1) {
            Won won = batch.wins().getFirst();
            shown = wonLine(player, won.crate(), won.reward(), won.inClaimBox());
        }
        Refused stopped = batch.stoppedBy();
        if (stopped != null) {
            if (CratePlayerSettings.stopReasonInChat(shown)) {
                this.services.messenger().alert(player, AlertStyle.CHAT, stopped.key(), stopped.argArray());
            } else {
                report(player, stopped);
            }
        }
    }

    /** The rarest win of a batch (the first one of the rarest rarity won). */
    Won rarest(Batch batch) {
        List<Rarity> rarities = this.settings.get().rarities();
        Won rarest = batch.wins().getFirst();
        for (Won won : batch.wins()) {
            if (rarities.indexOf(won.rarity()) > rarities.indexOf(rarest.rarity())) {
                rarest = won;
            }
        }
        return rarest;
    }

    /**
     * The "You won" line of one opening, where the player's Crate win receipt setting says; a reward waiting in the
     * claim box is always told in chat (the player must learn where it is). Returns where it went. Player's thread.
     */
    private AlertStyle wonLine(Player player, Crate crate, Reward reward, int inClaimBox) {
        Arg rewardArg = this.text.rewardArg("reward", reward);
        Arg name = Arg.text("name", crate.name());
        if (inClaimBox > 0) {
            this.services.messenger().send(player, CratesMessages.WON_CLAIM_BOX, rewardArg, name);
            return AlertStyle.CHAT;
        }
        AlertStyle style = this.services.settings().get(player, CratePlayerSettings.RECEIPT);
        this.services.messenger().alert(player, style, CratesMessages.WON, rewardArg, name);
        return style;
    }

    /** Sends a refusal to the player on the action bar. */
    void report(Player player, Refused refused) {
        this.services.messenger().send(player, refused.key(), refused.argArray());
    }

    /**
     * Opens one key of a crate. Call on the player's thread. {@code done} runs exactly once on the player's thread
     * with the outcome (immediately for a refusal), unless the player leaves first.
     *
     * @param paced whether the open cooldown applies: true for commands and crate blocks, which a client can repeat
     *              at will; dialogs and the preview menu wait for each opening to finish anyway
     */
    void open(Player player, String crateId, boolean paced, Consumer<Result> done) {
        open(player, crateId, paced, true, done);
    }

    /**
     * Opens up to {@code count} keys of a crate one after another: each opening is stored and handed over before the
     * next one starts, so this is exactly {@code count} single openings (with every check, event, log row and
     * announcement), just without a receipt each. Call on the player's thread. {@code done} runs once on the player's
     * thread, unless the player leaves first. The openings stop early when a key can't be opened (combat, a plugin
     * cancelled it, storage failed); running out of keys after the first opening just ends them.
     *
     * @param paced whether the open cooldown applies to the first opening (commands and crate blocks)
     */
    void openMany(Player player, String crateId, int count, boolean paced, Consumer<Batch> done) {
        next(player, crateId, Math.max(1, count), paced, new ArrayList<>(), done);
    }

    private void next(Player player, String crateId, int left, boolean paced, List<Won> wins, Consumer<Batch> done) {
        open(player, crateId, paced, false, result -> {
            switch (result) {
                case Won won -> {
                    wins.add(won);
                    if (left > 1 && won.keysLeft() > 0 && player.isOnline()) {
                        next(player, crateId, left - 1, false, wins, done);
                    } else {
                        done.accept(new Batch(won.crate(), wins, null, won.keysLeft()));
                    }
                }
                case Refused refused -> {
                    Crate crate = this.settings.get().crate(crateId);
                    boolean ranOut = !wins.isEmpty() && refused.key() == CratesMessages.NO_KEYS;
                    Crate shown = crate != null ? crate : wins.isEmpty() ? null : wins.getLast().crate();
                    done.accept(new Batch(shown, wins, ranOut ? null : refused, this.keys.keys(player.getUniqueId(), crateId)));
                }
            }
        });
    }

    /**
     * @param receipt whether the player gets the "You won ..." line in chat (several openings in a row send one
     *                summary instead)
     */
    private void open(Player player, String crateId, boolean paced, boolean receipt, Consumer<Result> done) {
        CratesSettings settings = this.settings.get();
        Crate crate = settings.crate(crateId);
        UUID uuid = player.getUniqueId();
        if (crate == null) {
            done.accept(new Refused(CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId)));
            return;
        }
        Arg name = Arg.text("name", crate.name());
        if (this.keys.keys(uuid, crate.id()) < 1) {
            done.accept(new Refused(CratesMessages.NO_KEYS, name));
            return;
        }
        if (settings.blockInCombat() && this.combat.tagged(uuid)) {
            done.accept(new Refused(CratesMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(uuid))));
            return;
        }
        if (!this.services.ledger().available()) {
            done.accept(new Refused(CoreMessages.ECONOMY_UNAVAILABLE));
            return;
        }
        List<Reward> rewards = this.items.available(crate);
        if (rewards.isEmpty()) {
            done.accept(new Refused(CratesMessages.NOTHING_TO_WIN, name));
            return;
        }
        if (!this.opening.add(uuid)) {
            done.accept(new Refused(CratesMessages.OPENING));
            return;
        }
        Duration cooldown = settings.openCooldown();
        if (paced && !cooldown.isZero() && !player.hasPermission("siftcore.bypass.cooldown")) {
            Duration left = this.services.cooldowns().tryUse(uuid, "crates:open", cooldown);
            if (!left.isZero()) {
                this.opening.remove(uuid);
                done.accept(new Refused(CoreMessages.COOLDOWN, Arg.time("time", left)));
                return;
            }
        }
        boolean started = false;
        try {
            started = start(player, crate, rewards, settings, receipt, done);
        } finally {
            if (!started) {
                this.opening.remove(uuid);
            }
        }
    }

    /** Draws the reward and runs the transaction; returns false when it ended right away (done was called). */
    private boolean start(Player player, Crate crate, List<Reward> rewards, CratesSettings settings, boolean receipt,
                          Consumer<Result> done) {
        UUID uuid = player.getUniqueId();
        Arg name = Arg.text("name", crate.name());
        Reward reward = WeightedTable.of(rewards, Reward::weight).pick(ThreadLocalRandom.current());
        Rarity rarity = settings.rarity(reward.rarity());
        Optional<ItemStack> item = this.items.build(reward);
        boolean givesItems = reward.kind() instanceof Reward.Item || reward.kind() instanceof Reward.Spawner;
        if (givesItems && item.isEmpty()) {
            done.accept(new Refused(CratesMessages.NOTHING_TO_WIN, name));
            return false;
        }
        if (!new CrateOpenEvent(player, crate.id(), reward.id(), reward.display(), rarity.id()).callEvent()) {
            done.accept(new Refused(CratesMessages.OPEN_CANCELLED, name));
            return false;
        }

        String openId = UUID.randomUUID().toString();
        String ref = "crate:" + openId;
        String crateId = crate.id();
        LedgerTx.Builder tx = LedgerTx.builder().actor(uuid).note(crateId + " " + reward.id());
        boolean money = false;
        switch (reward.kind()) {
            case Reward.Money m -> {
                tx.source(uuid, Currency.MONEY, m.amount(), LEDGER_KIND, ref);
                money = true;
            }
            case Reward.Shards s -> {
                tx.source(uuid, Currency.SHARDS, s.amount(), LEDGER_KIND, ref);
                money = true;
            }
            default -> {
            }
        }
        if (!money) {
            // Nothing but keys and items moves: no economy event for a transaction without balances.
            tx.silent();
        }
        tx.check(() -> this.settings.get().crate(crateId) != null ? null : CRATE_GONE);
        this.keys.spend(tx, uuid, crateId, 1, KeyService.NO_KEYS);
        if (reward.kind() instanceof Reward.Keys k) {
            this.keys.grant(tx, uuid, k.crate(), k.amount(), null, uuid.toString());
        }
        item.ifPresent(stack -> this.services.deliveries().add(tx, uuid, Handouts.SOURCE, ref, stack));
        List<String> commands = reward.kind() instanceof Reward.Command command
            ? resolve(command.commands(), player.getName(), uuid) : List.of();
        long now = System.currentTimeMillis();
        this.commands.add(tx, ref, uuid, commands, now);
        this.log.add(tx, now, uuid, crateId, reward.id(), reward.display() + " | " + ref);

        TransactionResult result = this.services.ledger().execute(tx.build());
        switch (result.status()) {
            case SUCCESS -> {
                result.committed().whenComplete((ignored, error) -> {
                    if (error != null) {
                        // Storage failed: the key, the reward and the log row were all rolled back.
                        this.opening.remove(uuid);
                        this.services.scheduler().entity(player, () -> done.accept(new Refused(CratesMessages.OPEN_FAILED)), null);
                        return;
                    }
                    committed(player, crate, reward, rarity, ref, item.isPresent(), commands, receipt, done);
                });
                return true;
            }
            case REJECTED -> done.accept(switch (result.reason() == null ? "" : result.reason()) {
                case KeyService.NO_KEYS -> new Refused(CratesMessages.NO_KEYS, name);
                case KeyService.LIMIT -> new Refused(CratesMessages.KEY_LIMIT,
                    Arg.text("name", keyName(settings, reward)));
                case CRATE_GONE -> new Refused(CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId));
                default -> new Refused(CratesMessages.OPEN_FAILED);
            });
            case BALANCE_LIMIT -> done.accept(new Refused(CratesMessages.BALANCE_FULL));
            case CANCELLED -> done.accept(new Refused(CratesMessages.OPEN_CANCELLED, name));
            case UNAVAILABLE -> done.accept(new Refused(CoreMessages.ECONOMY_UNAVAILABLE));
            case INSUFFICIENT_FUNDS -> done.accept(new Refused(CratesMessages.OPEN_FAILED));
        }
        return false;
    }

    private static String keyName(CratesSettings settings, Reward reward) {
        if (reward.kind() instanceof Reward.Keys k) {
            Crate target = settings.crate(k.crate());
            return target == null ? k.crate() : target.name();
        }
        return "";
    }

    /** After the transaction is stored (on a storage thread): log, announce, run commands, hand the items over. */
    private void committed(Player player, Crate crate, Reward reward, Rarity rarity, String ref, boolean hasItems,
                           List<String> commands, boolean receipt, Consumer<Result> done) {
        UUID uuid = player.getUniqueId();
        if (rarity.audit()) {
            this.services.audit().record(uuid.toString(), "crates.reward", uuid.toString(),
                "crate=" + crate.id() + " reward=" + reward.id() + " rarity=" + rarity.id() + " display=" + reward.display() + " ref=" + ref);
        }
        if (rarity.announce() && !this.vanish.vanished(uuid)) {
            announce(player, crate, reward, rarity);
        }
        this.commands.run(ref, commands);
        // The player left (or the server is stopping) before this can run: the items stay in the claim box for them.
        Handoffs.onEntity(this.services.scheduler(), player, () -> {
            if (!hasItems) {
                finish(player, crate, reward, rarity, 0, receipt, done);
                return;
            }
            this.handouts.claim(player, ref, waiting -> finish(player, crate, reward, rarity, waiting, receipt, done));
        }, () -> this.opening.remove(uuid));
    }

    private void finish(Player player, Crate crate, Reward reward, Rarity rarity, int inClaimBox, boolean receipt,
                        Consumer<Result> done) {
        UUID uuid = player.getUniqueId();
        this.opening.remove(uuid);
        if (receipt) {
            wonLine(player, crate, reward, inClaimBox);
        }
        done.accept(new Won(crate, reward, rarity, inClaimBox, this.keys.keys(uuid, crate.id())));
    }

    /** Tells everyone else whose Crate win announcements let this win through (every win, or only the rarest rarity). */
    private void announce(Player winner, Crate crate, Reward reward, Rarity rarity) {
        Arg player = Arg.text("player", winner.getName());
        Arg rewardArg = this.text.rewardArg("reward", reward);
        Arg name = Arg.text("name", crate.name());
        List<Rarity> rarities = this.settings.get().rarities();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(winner.getUniqueId()) && CratePlayerSettings.showsWin(
                this.services.settings().get(online.getUniqueId(), CratePlayerSettings.WIN_ANNOUNCEMENTS), rarity, rarities)) {
                this.services.messenger().send(online, CratesMessages.ANNOUNCE, player, rewardArg, name);
            }
        }
        this.services.messenger().send(Bukkit.getConsoleSender(), CratesMessages.ANNOUNCE, player, rewardArg, name);
    }

    /** A command reward's console commands for one winner. */
    static List<String> resolve(List<String> templates, String playerName, UUID uuid) {
        List<String> resolved = new ArrayList<>(templates.size());
        for (String template : templates) {
            resolved.add(template.replace("%player%", playerName).replace("%uuid%", uuid.toString()));
        }
        return resolved;
    }

    /** Players whose opening has not finished yet. */
    int inProgress() {
        return this.opening.size();
    }

    /** Forgets a player who left (an opening that was still finishing can no longer reach them). */
    void forget(UUID player) {
        this.opening.remove(player);
    }

    Component rewardText(Reward reward) {
        return this.text.reward(reward);
    }
}
