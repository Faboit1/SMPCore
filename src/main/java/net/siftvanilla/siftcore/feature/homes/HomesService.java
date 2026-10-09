package net.siftvanilla.siftcore.feature.homes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Setting, using and deleting homes, and the homes dialogs. Every method runs on the acting player's thread. */
final class HomesService {

    static final String LIMIT_PREFIX = "siftcore.homes";
    static final String BYPASS_COOLDOWN = "siftcore.bypass.cooldown";
    private static final String COOLDOWN_KEY = "homes:teleport";
    private static final int PAGE_SIZE = 8;

    /** A message to show for an action: the key, its arguments and whether the action worked. */
    record Result(boolean ok, MessageKey key, Arg... args) {
    }

    private final Services services;
    private final Setting<HomesSettings> settings;
    private final HomeStore store;
    private final SpawnArea spawn;
    private final CombatStatus combat;

    HomesService(Services services, Setting<HomesSettings> settings, HomeStore store, SpawnArea spawn, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.store = store;
        this.spawn = spawn;
        this.combat = combat;
    }

    HomeStore store() {
        return this.store;
    }

    /** How many homes the player may have (rank nodes {@code siftcore.homes.<n>}, highest wins). */
    int limit(Player player) {
        return Limits.highest(player, LIMIT_PREFIX, this.settings.get().defaultLimit());
    }

    private Arg limitArg(int limit) {
        return limit == Limits.UNLIMITED ? Arg.text("limit", this.services.lang().plain(HomesMessages.UNLIMITED)) : Arg.number("limit", limit);
    }

    private void tell(Player player, Result result) {
        this.services.messenger().send(player, result.key(), result.args());
    }

    // ------------------------------------------------------------------ set

    /** /sethome [name]. */
    void setHome(Player player, String input) {
        tell(player, trySet(player, input));
    }

    /** Sets a home where the player stands: /sethome and the "Set a home here" form, refused in combat either way. */
    private Result trySet(Player player, String input) {
        if (this.combat.tagged(player.getUniqueId())) {
            return new Result(false, HomesMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(player.getUniqueId())));
        }
        Optional<String> name = HomeNames.normalize(input);
        if (name.isEmpty()) {
            return new Result(false, HomesMessages.INVALID_NAME);
        }
        Location here = player.getLocation();
        if (this.settings.get().disabled(here.getWorld().getName())) {
            return new Result(false, HomesMessages.WORLD_DISABLED);
        }
        if (this.spawn.contains(here)) {
            return new Result(false, HomesMessages.IN_SPAWN);
        }
        int limit = limit(player);
        HomeStore.SetResult result = this.store.set(player.getUniqueId(), Home.at(name.get(), here, System.currentTimeMillis()), limit);
        return switch (result.outcome()) {
            case CREATED -> new Result(true, HomesMessages.SET, Arg.text("name", name.get()), Arg.number("count", result.count()), limitArg(limit));
            case MOVED -> new Result(true, HomesMessages.MOVED, Arg.text("name", name.get()));
            case LIMIT -> new Result(false, HomesMessages.LIMIT, Arg.number("count", result.count()), limitArg(limit));
            case NOT_LOADED -> new Result(false, HomesMessages.LOADING);
        };
    }

    // ------------------------------------------------------------------ teleport

    /** /home with no name: the only home, or the list when there are several. */
    void home(Player player) {
        Optional<Map<String, Home>> homes = this.store.homes(player.getUniqueId());
        if (homes.isEmpty()) {
            this.services.messenger().send(player, HomesMessages.LOADING);
        } else if (homes.get().isEmpty()) {
            this.services.messenger().send(player, HomesMessages.NONE);
        } else if (homes.get().size() == 1) {
            teleport(player, homes.get().keySet().iterator().next());
        } else {
            openList(player, 1, null);
        }
    }

    /** /home <name>: warmup, then teleport to wherever that home is when the warmup ends, if it still looks safe. */
    void teleport(Player player, String input) {
        teleport(player, input, true);
    }

    /**
     * @param checkSafety false after the player confirmed a home that looks unsafe ("Teleport anyway")
     */
    private void teleport(Player player, String input, boolean checkSafety) {
        UUID id = player.getUniqueId();
        String name = HomeNames.normalize(input).orElse(null);
        if (!this.store.isLoaded(id)) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Home home = name == null ? null : this.store.get(id, name).orElse(null);
        if (home == null) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", input));
            return;
        }
        Result usable = usable(home);
        if (!usable.ok()) {
            tell(player, usable);
            return;
        }
        Duration left = this.services.cooldowns().remaining(id, COOLDOWN_KEY);
        if (!left.isZero() && !player.hasPermission(BYPASS_COOLDOWN)) {
            this.services.messenger().send(player, CoreMessages.COOLDOWN, Arg.time("time", left));
            return;
        }
        this.services.teleports().teleport(player, "home", this.settings.get().warmup(), () -> {
            Home current = this.store.get(id, name).orElse(null);
            if (current == null) {
                this.services.messenger().send(player, HomesMessages.GONE, Arg.text("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Result check = usable(current);
            if (!check.ok()) {
                tell(player, check);
                return CompletableFuture.completedFuture(null);
            }
            Location location = current.toLocation();
            return checkSafety ? safe(player, current, location) : CompletableFuture.completedFuture(location);
        }, ok -> {
            if (ok) {
                this.services.cooldowns().start(id, COOLDOWN_KEY, this.settings.get().cooldown());
                this.services.messenger().send(player, HomesMessages.TELEPORTED, Arg.text("name", name));
            }
        });
    }

    /**
     * The home's location when its two blocks look safe; otherwise null, and the player is asked whether to go
     * anyway. The chunk is loaded without generating and read on the thread that owns it.
     */
    private CompletableFuture<Location> safe(Player player, Home home, Location location) {
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        CompletableFuture<Location> result = new CompletableFuture<>();
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                result.complete(location);
                return;
            }
            this.services.scheduler().region(world, chunkX, chunkZ, () -> {
                Block feet = world.getBlockAt(location);
                HomeSafety.Danger danger = HomeSafety.danger(kind(feet), kind(feet.getRelative(BlockFace.UP)));
                if (danger == HomeSafety.Danger.NONE) {
                    result.complete(location);
                    return;
                }
                result.complete(null);
                this.services.scheduler().entity(player, () -> confirmUnsafe(player, home, danger), null);
            });
        });
        return result;
    }

    /** What a block means for a player standing in it. Region thread. */
    private static HomeSafety.Kind kind(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA) {
            return HomeSafety.Kind.LAVA;
        }
        if (Tag.FIRE.isTagged(type) || (Tag.CAMPFIRES.isTagged(type) && block.getBlockData() instanceof Lightable lit && lit.isLit())) {
            return HomeSafety.Kind.FIRE;
        }
        return block.isSuffocating() ? HomeSafety.Kind.FULL : HomeSafety.Kind.CLEAR;
    }

    /** "Home X looks unsafe: lava. Teleport anyway?" The answer starts a new teleport (with its warmup). */
    private void confirmUnsafe(Player player, Home home, HomeSafety.Danger danger) {
        Lang lang = this.services.lang();
        MessageKey reason = switch (danger) {
            case LAVA -> HomesMessages.UNSAFE_LAVA;
            case FIRE -> HomesMessages.UNSAFE_FIRE;
            default -> HomesMessages.UNSAFE_BLOCKED;
        };
        this.services.dialogs().show(player, this.services.templates().confirm(lang.get(HomesMessages.UNSAFE_TITLE),
            List.of(lang.get(HomesMessages.UNSAFE_BODY, Arg.text("name", home.name()), Arg.component("reason", lang.get(reason))),
                lang.get(HomesMessages.UNSAFE_QUESTION)),
            lang.get(HomesMessages.UNSAFE_GO), lang.get(CoreMessages.UI_CANCEL),
            yes -> teleport(yes.player(), home.name(), false),
            null));
    }

    private Result usable(Home home) {
        if (this.settings.get().disabled(home.world())) {
            return new Result(false, HomesMessages.HOME_WORLD_DISABLED, Arg.text("name", home.name()));
        }
        if (Bukkit.getWorld(home.world()) == null) {
            return new Result(false, HomesMessages.WORLD_MISSING, Arg.text("name", home.name()));
        }
        return new Result(true, HomesMessages.TELEPORTED, Arg.text("name", home.name()));
    }

    // ------------------------------------------------------------------ delete

    /** Deletes one of the player's own homes (after the confirmation). */
    private void delete(Player player, String name) {
        UUID id = player.getUniqueId();
        if (this.store.get(id, name).isEmpty()) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", name));
            return;
        }
        this.store.delete(id, name);
        this.services.messenger().send(player, HomesMessages.DELETED, Arg.text("name", name));
    }

    /** /delhome <name>: asks first. {@code after} runs after either answer (null closes the dialog). */
    void confirmDelete(Player player, String input, Runnable after) {
        String name = HomeNames.normalize(input).orElse(null);
        if (!this.store.isLoaded(player.getUniqueId())) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Home home = name == null ? null : this.store.get(player.getUniqueId(), name).orElse(null);
        if (home == null) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", input));
            return;
        }
        Lang lang = this.services.lang();
        this.services.dialogs().show(player, this.services.templates().confirm(lang.get(HomesMessages.DELETE_TITLE),
            lang.lines(HomesMessages.DELETE_BODY, Arg.text("name", home.name()), Arg.text("world", home.world()),
                Arg.number("x", home.blockX()), Arg.number("y", home.blockY()), Arg.number("z", home.blockZ())),
            lang.get(HomesMessages.DELETE_BUTTON), lang.get(CoreMessages.UI_CANCEL),
            yes -> {
                delete(yes.player(), home.name());
                if (after != null) {
                    after.run();
                }
            },
            no -> {
                if (after != null) {
                    after.run();
                }
            }));
    }

    // ------------------------------------------------------------------ dialogs

    /** The homes list: a teleport and a delete button per home, paged, plus "set a home here". */
    void openList(Player player, int page, Button.Handler back) {
        Optional<Map<String, Home>> loaded = this.store.homes(player.getUniqueId());
        if (loaded.isEmpty()) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Lang lang = this.services.lang();
        List<Home> all = new ArrayList<>(loaded.get().values());
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.clamp(page, 1, pages);
        List<Home> slice = all.subList((current - 1) * PAGE_SIZE, Math.min(all.size(), current * PAGE_SIZE));
        int limit = limit(player);
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(HomesMessages.LIST_HEADER, Arg.number("count", all.size()), limitArg(limit)));
        if (all.isEmpty()) {
            lines.add(lang.get(HomesMessages.LIST_EMPTY));
        }
        for (Home home : slice) {
            lines.add(line(home));
        }
        if (pages > 1) {
            lines.add(lang.get(HomesMessages.LIST_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        List<Button> buttons = new ArrayList<>();
        for (Home home : slice) {
            String name = home.name();
            buttons.add(Button.of(Component.text(name), lang.get(HomesMessages.LIST_TELEPORT_TOOLTIP, Arg.text("name", name)),
                s -> teleport(s.player(), name)).width(150));
            buttons.add(Button.of(lang.get(HomesMessages.LIST_DELETE), lang.get(HomesMessages.LIST_DELETE_TOOLTIP, Arg.text("name", name)),
                s -> confirmDelete(s.player(), name, () -> openList(s.player(), current, back))).width(150));
        }
        if (current > 1) {
            buttons.add(Button.of(lang.get(HomesMessages.LIST_PREVIOUS), s -> openList(s.player(), current - 1, back)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(HomesMessages.LIST_NEXT), s -> openList(s.player(), current + 1, back)).width(150));
        }
        buttons.add(Button.of(lang.get(HomesMessages.LIST_SET_HERE), s -> openSetForm(s.player(), back)).width(150));
        this.services.dialogs().show(player, this.services.templates().list(lang.get(HomesMessages.LIST_TITLE), lines, buttons, 2, back));
    }

    private Component line(Home home) {
        return this.services.lang().get(HomesMessages.LIST_LINE, Arg.text("name", home.name()), Arg.text("world", home.world()),
            Arg.number("x", home.blockX()), Arg.number("y", home.blockY()), Arg.number("z", home.blockZ()));
    }

    /** A form to name a new home where the player stands. */
    void openSetForm(Player player, Button.Handler back) {
        Lang lang = this.services.lang();
        String suggestion = HomeNames.suggest(this.store.homes(player.getUniqueId()).orElse(Map.of()));
        this.services.dialogs().show(player, this.services.templates().form(lang.get(HomesMessages.FORM_TITLE),
            lang.lines(HomesMessages.FORM_BODY),
            List.of(Templates.text("name", lang.get(HomesMessages.FORM_NAME), suggestion, HomeNames.MAX_LENGTH)),
            lang.get(HomesMessages.FORM_SUBMIT),
            submission -> {
                Result result = trySet(submission.player(), submission.values().text("name"));
                if (!result.ok()) {
                    submission.error(lang.get(result.key(), result.args()));
                    return;
                }
                tell(submission.player(), result);
                openList(submission.player(), 1, back);
            },
            submission -> openList(submission.player(), 1, back)));
    }

    // ------------------------------------------------------------------ staff

    /** Staff: another player's homes (online or offline) with teleport and delete buttons. */
    void openOther(Player staff, UUID target, String targetName, int page) {
        this.store.fetch(target).whenComplete((homes, error) -> this.services.scheduler().entity(staff, () -> {
            if (error != null) {
                this.services.messenger().send(staff, CoreMessages.ACTION_FAILED);
                return;
            }
            showOther(staff, target, targetName, homes, page);
        }, null));
    }

    private void showOther(Player staff, UUID target, String targetName, Map<String, Home> homes, int page) {
        Lang lang = this.services.lang();
        List<Home> all = new ArrayList<>(homes.values());
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.clamp(page, 1, pages);
        List<Home> slice = all.subList((current - 1) * PAGE_SIZE, Math.min(all.size(), current * PAGE_SIZE));
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(HomesMessages.OTHER_HEADER, Arg.text("name", targetName), Arg.number("count", all.size())));
        for (Home home : slice) {
            lines.add(line(home));
        }
        if (pages > 1) {
            lines.add(lang.get(HomesMessages.LIST_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        List<Button> buttons = new ArrayList<>();
        for (Home home : slice) {
            buttons.add(Button.of(Component.text(home.name()), lang.get(HomesMessages.LIST_TELEPORT_TOOLTIP, Arg.text("name", home.name())),
                s -> this.services.teleports().teleport(s.player(), "home-staff", Duration.ZERO, () -> {
                    Location location = home.toLocation();
                    if (location == null) {
                        this.services.messenger().send(s.player(), HomesMessages.WORLD_MISSING, Arg.text("name", home.name()));
                    }
                    return CompletableFuture.completedFuture(location);
                }, ok -> {
                    // A staff member visiting a player's base is written down like invsee and whois are.
                    if (ok) {
                        this.services.audit().record(s.player().getUniqueId().toString(), "homes.teleport", target.toString(),
                            staffTeleportDetails(home));
                    }
                })).width(150));
            buttons.add(Button.of(lang.get(HomesMessages.LIST_DELETE), lang.get(HomesMessages.LIST_DELETE_TOOLTIP, Arg.text("name", home.name())),
                s -> confirmOtherDelete(s.player(), target, targetName, home)).width(150));
        }
        if (current > 1) {
            buttons.add(Button.of(lang.get(HomesMessages.LIST_PREVIOUS), s -> openOther(s.player(), target, targetName, current - 1)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(HomesMessages.LIST_NEXT), s -> openOther(s.player(), target, targetName, current + 1)).width(150));
        }
        this.services.dialogs().show(staff, this.services.templates().list(lang.get(HomesMessages.OTHER_TITLE, Arg.text("name", targetName)),
            lines, buttons, 2, null));
    }

    /** The audit details of a staff teleport to a home: its name, world and block position. */
    static String staffTeleportDetails(Home home) {
        return home.name() + " " + home.world() + " " + home.blockX() + "," + home.blockY() + "," + home.blockZ();
    }

    private void confirmOtherDelete(Player staff, UUID target, String targetName, Home home) {
        Lang lang = this.services.lang();
        this.services.dialogs().show(staff, this.services.templates().confirm(lang.get(HomesMessages.DELETE_TITLE),
            lang.lines(HomesMessages.DELETE_BODY, Arg.text("name", home.name()), Arg.text("world", home.world()),
                Arg.number("x", home.blockX()), Arg.number("y", home.blockY()), Arg.number("z", home.blockZ())),
            lang.get(HomesMessages.DELETE_BUTTON), lang.get(CoreMessages.UI_CANCEL),
            yes -> deleteOther(yes.player(), target, targetName, home.name(), () -> openOther(yes.player(), target, targetName, 1)),
            no -> openOther(no.player(), target, targetName, 1)));
    }

    /** Staff: deletes another player's home (works offline too). {@code then} runs on the sender's thread afterwards. */
    void deleteOther(CommandSender sender, UUID target, String targetName, String input, Runnable then) {
        String name = HomeNames.normalize(input).orElse(input);
        this.store.delete(target, name).whenComplete((deleted, error) -> {
            Runnable report = () -> {
                if (error != null) {
                    this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                } else if (Boolean.TRUE.equals(deleted)) {
                    this.services.messenger().chat(sender, HomesMessages.ADMIN_DELETED, Arg.text("home", name), Arg.text("name", targetName));
                    this.services.audit().record(sender instanceof Player p ? p.getUniqueId().toString() : "console", "homes.delete",
                        target.toString(), name);
                } else {
                    this.services.messenger().send(sender, HomesMessages.NOT_FOUND, Arg.text("name", name));
                }
                if (then != null) {
                    then.run();
                }
            };
            if (sender instanceof Player player) {
                this.services.scheduler().entity(player, report, null);
            } else {
                report.run();
            }
        });
    }

    /** Staff and console: another player's homes as chat lines. */
    void listOther(CommandSender sender, UUID target, String targetName) {
        this.store.fetch(target).whenComplete((homes, error) -> {
            if (error != null) {
                this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            if (homes.isEmpty()) {
                this.services.messenger().chat(sender, HomesMessages.ADMIN_EMPTY, Arg.text("name", targetName));
                return;
            }
            this.services.messenger().chat(sender, HomesMessages.ADMIN_HEADER, Arg.text("name", targetName), Arg.number("count", homes.size()));
            for (Home home : homes.values()) {
                this.services.messenger().chat(sender, HomesMessages.ADMIN_LINE, Arg.text("home", home.name()), Arg.text("world", home.world()),
                    Arg.number("x", home.blockX()), Arg.number("y", home.blockY()), Arg.number("z", home.blockZ()));
            }
        });
    }
}
