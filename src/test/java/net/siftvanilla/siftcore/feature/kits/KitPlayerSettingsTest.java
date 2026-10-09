package net.siftvanilla.siftcore.feature.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.feature.crates.CratesTestAccess;
import net.siftvanilla.siftcore.feature.sell.WorthTable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The kit settings: their places in Crates &amp; kits (with the crate settings), the legacy values of kit-reminders,
 * config-dependent offering, the trash protection threshold in kits.yml and the pure deciders.
 */
class KitPlayerSettingsTest {

    private static KitsSettings config(boolean reminders, long protectWorth) {
        return new KitsSettings(true, reminders, false, List.of(),
            new KitsSettings.Perks(EnumSet.noneOf(Perk.class), true, Set.of(), protectWorth));
    }

    private static PlayerSettings registered(AtomicReference<KitsSettings> config, AtomicBoolean prices) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        CratesTestAccess.registerShipped(settings);
        KitPlayerSettings.register(settings, config::get, prices::get, player -> { });
        return settings;
    }

    private static List<String> ids(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.CRATES.id()).stream().map(Registry.Entry::id).toList();
    }

    private static List<String> offered(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.CRATES.id()).stream().filter(Registry.Entry::offered)
            .map(Registry.Entry::id).toList();
    }

    @Test
    void cratesAndKitsShareTheirGroupInTheCatalogOrder() {
        PlayerSettings settings = registered(new AtomicReference<>(config(true, 10_000)), new AtomicBoolean(true));
        List<String> expected = List.of("crate-receipt", "kit-reminders", "crate-key-reminder", "keyall-countdown", "crate-quick-open",
            "trash-protect", "crate-bulk-amount", "kit-reminder-when", "kit-auto-equip", "trash-confirm");
        assertEquals(expected, ids(settings));
        assertEquals(expected, offered(settings), "all offered with the shipped config");
        assertTrue(expected.size() >= SettingCategories.MIN_SETTINGS && expected.size() <= SettingCategories.MAX_SETTINGS);
        for (Registry.Entry<?> entry : settings.registry().in(SettingCategories.CRATES.id())) {
            if (entry.setting() instanceof Choice<?> choice) {
                assertTrue(choice.options().size() <= Choice.MAX_OPTIONS, entry.id());
            }
        }
    }

    @Test
    void kitAndTrashSettingsNeedTheirPermissions() {
        assertEquals(KitsFeature.PERMISSION_USE, KitPlayerSettings.REMINDERS.permission());
        assertEquals(KitsFeature.PERMISSION_USE, KitPlayerSettings.REMINDER_WHEN.permission());
        assertEquals(KitsFeature.PERMISSION_USE, KitPlayerSettings.AUTO_EQUIP.permission());
        assertEquals("siftcore.perk.trash", KitPlayerSettings.TRASH_PROTECT.permission());
        assertEquals("siftcore.perk.trash", KitPlayerSettings.TRASH_MODE.permission());
        assertEquals(Perk.TRASH.node(), KitPlayerSettings.TRASH_MODE.permission());
        PlayerSettings settings = registered(new AtomicReference<>(config(true, 10_000)), new AtomicBoolean(true));
        Registry.Entry<?> trash = settings.registry().entry("trash-confirm");
        assertFalse(settings.visible(trash, permission -> !permission.equals("siftcore.perk.trash")), "hidden without the perk");
        assertTrue(settings.visible(trash, permission -> true));
    }

    @Test
    void kitRemindersWasASwitchAndOldRowsStillRead() {
        Choice<AlertStyle> reminders = KitPlayerSettings.REMINDERS;
        assertEquals(List.of("chat", "actionbar", "title", "off"), reminders.optionIds());
        assertEquals(AlertStyle.CHAT, reminders.defaultValue());
        assertEquals(AlertStyle.CHAT, reminders.decodeOrNull("true"));
        assertEquals(AlertStyle.OFF, reminders.decodeOrNull("false"));
        assertEquals(AlertStyle.TITLE, reminders.decodeOrNull("Title"));
        assertNull(reminders.decodeOrNull("loud"));
        assertEquals(List.of("join-and-ready", "join", "ready"), KitPlayerSettings.REMINDER_WHEN.optionIds());
        assertEquals(List.of("gear", "valuables", "off"), KitPlayerSettings.TRASH_PROTECT.optionIds());
        assertEquals(KitPlayerSettings.TrashProtect.GEAR, KitPlayerSettings.TRASH_PROTECT.defaultValue());
        assertEquals(List.of("delete-on-close", "delete-button"), KitPlayerSettings.TRASH_MODE.optionIds());
        assertFalse(KitPlayerSettings.AUTO_EQUIP.defaultOn());
    }

    @Test
    void reminderSettingsFollowTheServerAndValuablesNeedPrices() {
        AtomicReference<KitsSettings> config = new AtomicReference<>(config(false, 10_000));
        AtomicBoolean prices = new AtomicBoolean(true);
        PlayerSettings settings = registered(config, prices);
        assertFalse(offered(settings).contains("kit-reminders"), "no reminders on the server");
        assertFalse(offered(settings).contains("kit-reminder-when"));
        config.set(config(true, 10_000));
        assertTrue(offered(settings).contains("kit-reminders"));

        @SuppressWarnings("unchecked")
        Registry.Entry<KitPlayerSettings.TrashProtect> protect =
            (Registry.Entry<KitPlayerSettings.TrashProtect>) settings.registry().entry("trash-protect");
        assertEquals(List.of("gear", "valuables", "off"), options(settings, protect));
        config.set(config(true, 0));
        assertEquals(List.of("gear", "off"), options(settings, protect), "a threshold of 0 turns valuables off");
        config.set(config(true, 10_000));
        prices.set(false);
        assertEquals(List.of("gear", "off"), options(settings, protect), "nothing is priced without selling");
    }

    @Test
    void valuablesNeedAWorthTableThatPricesSomething() {
        assertFalse(KitsFeature.pricesItems(WorthLookup.NONE), "no sell prices at all");
        assertFalse(KitsFeature.pricesItems(null));
        assertFalse(KitsFeature.priced(WorthTable.EMPTY), "the sell feature with an empty worth table prices nothing");
        assertFalse(KitsFeature.priced(null));
        assertTrue(KitsFeature.priced(new WorthTable(Map.of("minecraft:diamond", new WorthTable.Entry(100, WorthTable.Origin.BASE, null)),
            Set.of(), Map.of(), Map.of())));
        WorthLookup other = new WorthLookup() {
            @Override
            public long unitPrice(ItemStack item) {
                return 5;
            }

            @Override
            public double multiplier(Player player) {
                return 1.0;
            }
        };
        assertTrue(KitsFeature.pricesItems(other), "another plugin's prices are taken to price items");
    }

    @Test
    void joinRemindersSayBothInOneLineWhereOnlyOneLineShows() {
        assertEquals(List.of(KitPlayerSettings.JoinLine.READY, KitPlayerSettings.JoinLine.WAITING),
            KitPlayerSettings.joinLines(AlertStyle.CHAT, true, true), "chat keeps both clickable lines");
        assertEquals(List.of(KitPlayerSettings.JoinLine.READY_AND_WAITING), KitPlayerSettings.joinLines(AlertStyle.ACTIONBAR, true, true),
            "a second action bar line would replace the first at once");
        assertEquals(List.of(KitPlayerSettings.JoinLine.READY_AND_WAITING), KitPlayerSettings.joinLines(AlertStyle.TITLE, true, true));
        assertEquals(List.of(KitPlayerSettings.JoinLine.READY), KitPlayerSettings.joinLines(AlertStyle.ACTIONBAR, true, false));
        assertEquals(List.of(KitPlayerSettings.JoinLine.WAITING), KitPlayerSettings.joinLines(AlertStyle.TITLE, false, true));
        assertEquals(List.of(), KitPlayerSettings.joinLines(AlertStyle.CHAT, false, false));
        assertEquals(List.of(), KitPlayerSettings.joinLines(AlertStyle.OFF, true, true));
    }

    private static <T> List<String> options(PlayerSettings settings, Registry.Entry<T> entry) {
        return settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    @Test
    void theReminderTimerRunsOnlyForReadyReminders() {
        assertTrue(KitPlayerSettings.timer(AlertStyle.CHAT, KitPlayerSettings.ReminderWhen.JOIN_AND_READY));
        assertTrue(KitPlayerSettings.timer(AlertStyle.TITLE, KitPlayerSettings.ReminderWhen.READY));
        assertFalse(KitPlayerSettings.timer(AlertStyle.CHAT, KitPlayerSettings.ReminderWhen.JOIN), "join only needs no timer");
        assertFalse(KitPlayerSettings.timer(AlertStyle.OFF, KitPlayerSettings.ReminderWhen.JOIN_AND_READY), "no reminders, no timer");
        assertTrue(KitPlayerSettings.ReminderWhen.JOIN_AND_READY.onJoin());
        assertTrue(KitPlayerSettings.ReminderWhen.JOIN.onJoin());
        assertFalse(KitPlayerSettings.ReminderWhen.READY.onJoin());
        assertFalse(KitPlayerSettings.ReminderWhen.JOIN.whenReady());
    }

    @Test
    void trashProtectionKeepsGearAndValuables() {
        assertTrue(KitPlayerSettings.gear("minecraft:diamond_sword", true, false), "enchanted");
        assertTrue(KitPlayerSettings.gear("minecraft:dirt", false, true), "renamed");
        assertTrue(KitPlayerSettings.gear("minecraft:shulker_box", false, false));
        assertTrue(KitPlayerSettings.gear("minecraft:red_shulker_box", false, false));
        assertTrue(KitPlayerSettings.gear("minecraft:spawner", false, false));
        assertTrue(KitPlayerSettings.gear("minecraft:trial_key", false, false));
        assertTrue(KitPlayerSettings.gear("minecraft:ominous_trial_key", false, false));
        assertFalse(KitPlayerSettings.gear("minecraft:diamond_sword", false, false), "a plain sword is not gear");
        assertFalse(KitPlayerSettings.gear("minecraft:cobblestone", false, false));

        assertTrue(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.GEAR, true, 0, 10_000));
        assertFalse(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.GEAR, false, 1_000_000, 10_000), "gear ignores worth");
        assertTrue(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.VALUABLES, false, 10_000, 10_000), "worth at the threshold");
        assertFalse(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.VALUABLES, false, 9_999, 10_000));
        assertTrue(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.VALUABLES, true, 0, 10_000));
        assertFalse(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.VALUABLES, false, 1_000_000, 0), "no threshold, no valuables");
        assertFalse(KitPlayerSettings.protects(KitPlayerSettings.TrashProtect.OFF, true, 1_000_000, 10_000), "off deletes everything");
    }

    @Test
    void autoEquipFillsOnlyEmptySlotsWithUncursedArmour() {
        List<String> kit = List.of("minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:diamond_sword",
            "minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:elytra", "minecraft:iron_helmet", "minecraft:turtle_helmet");
        List<KitPlayerSettings.Piece> pieces = KitPlayerSettings.pieces(kit);
        assertEquals(java.util.Arrays.asList(KitPlayerSettings.Piece.HEAD, KitPlayerSettings.Piece.CHEST, null, KitPlayerSettings.Piece.LEGS,
            KitPlayerSettings.Piece.FEET, null, KitPlayerSettings.Piece.HEAD, KitPlayerSettings.Piece.HEAD), pieces);
        List<Boolean> clean = new ArrayList<>(List.of(false, false, false, false, false, false, false, false));

        Map<Integer, KitPlayerSettings.Piece> all = KitPlayerSettings.equip(pieces, clean, Set.of());
        assertEquals(Map.of(0, KitPlayerSettings.Piece.HEAD, 1, KitPlayerSettings.Piece.CHEST, 3, KitPlayerSettings.Piece.LEGS,
            4, KitPlayerSettings.Piece.FEET), all, "one piece per slot, the first one; elytra and swords stay in the inventory");

        Map<Integer, KitPlayerSettings.Piece> worn = KitPlayerSettings.equip(pieces, clean,
            Set.of(KitPlayerSettings.Piece.HEAD, KitPlayerSettings.Piece.FEET));
        assertEquals(Map.of(1, KitPlayerSettings.Piece.CHEST, 3, KitPlayerSettings.Piece.LEGS), worn, "worn armour is never replaced");

        List<Boolean> cursedHelmet = new ArrayList<>(clean);
        cursedHelmet.set(0, true);
        Map<Integer, KitPlayerSettings.Piece> cursed = KitPlayerSettings.equip(pieces, cursedHelmet, Set.of());
        assertEquals(KitPlayerSettings.Piece.HEAD, cursed.get(6), "the cursed helmet is skipped, the next helmet goes on");
        assertFalse(cursed.containsKey(0));
        assertEquals(Map.of(), KitPlayerSettings.equip(List.of(), List.of(), Set.of()));
    }

    @Test
    void protectWorthIsReadFromKitsYml() throws Exception {
        assertEquals(10_000, parse(yaml -> { }).perks().protectWorth(), "the shipped threshold");
        assertEquals(10_000, parse(yaml -> yaml.set("perks.trash", null)).perks().protectWorth(), "the default when kits.yml has none");
        assertEquals(250_000, parse(yaml -> yaml.set("perks.trash.protect-worth", "250k")).perks().protectWorth());
        assertEquals(0, parse(yaml -> yaml.set("perks.trash.protect-worth", 0)).perks().protectWorth());
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings bad = parse(yaml -> {
            yaml.set("perks.trash.protect-worth", "lots");
            yaml.set("perks.trash.keep", true);
        }, problems);
        assertEquals(KitsSettings.DEFAULT_PROTECT_WORTH, bad.perks().protectWorth());
        assertEquals(Set.of("perks.trash.protect-worth", "perks.trash.keep"),
            Set.copyOf(problems.stream().map(ConfigProblem::path).toList()));
    }

    private static KitsSettings parse(java.util.function.Consumer<YamlConfiguration> change) throws Exception {
        List<ConfigProblem> problems = new ArrayList<>();
        KitsSettings settings = parse(change, problems);
        assertEquals(List.of(), problems);
        return settings;
    }

    /** Parses the shipped kits.yml after {@code change}. */
    private static KitsSettings parse(java.util.function.Consumer<YamlConfiguration> change, List<ConfigProblem> problems)
        throws Exception {
        YamlConfiguration yaml = KitsSettingsTest.bundled();
        change.accept(yaml);
        ConfigReader reader = new ConfigReader("features/kits.yml", yaml);
        KitsSettings settings = KitsSettings.parse(reader, KitsSettingsTest.CATALOG, MoneyFormat.defaults());
        problems.addAll(reader.problems());
        return settings;
    }
}
