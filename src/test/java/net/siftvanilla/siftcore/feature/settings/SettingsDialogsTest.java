package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.PingSound;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings dialogs as buttons: the group list, a switch flipping at once, a choice moving to its next option, a
 * number picked on a slider, tooltips, locked settings, refused changes, stale buttons, resets, the changed settings
 * and search, all against a real settings store and the shipped text.
 */
class SettingsDialogsTest {

    @TempDir
    Path dir;
    private SettingsDb db;
    private PlayerSettings settings;
    private final List<View> shown = new CopyOnWriteArrayList<>();
    private SettingsDialogs dialogs;
    private Fakes.FakePlayer alex;
    private final Palette palette = Palette.defaults();

    @BeforeEach
    void open() throws Exception {
        this.db = new SettingsDb(this.dir);
        this.settings = this.db.settings;
        // The Sounds group shows all three kinds once a feature plays the mention sound (chat does).
        this.settings.reads(SharedSettings.SOUND_MENTION);
        this.dialogs = new SettingsDialogs(this.settings, this.db.lang, new Templates(this.db.lang), new Messenger(this.db.lang, new Sounds()),
            (player, view) -> this.shown.add(view), () -> SettingsConfig.DEFAULTS);
        this.alex = new Fakes.FakePlayer("Alex");
        this.db.join(this.alex.id);
    }

    @AfterEach
    void close() {
        this.db.close();
    }

    private View last() {
        assertFalse(this.shown.isEmpty(), "a dialog was shown");
        return this.shown.getLast();
    }

    private static String plain(Component text) {
        return text == null ? "" : TextStyle.plain(text);
    }

    private static Button button(View view, String labelStart) {
        for (Button button : view.allButtons()) {
            if (plain(button.label()).startsWith(labelStart)) {
                return button;
            }
        }
        throw new AssertionError("no button '" + labelStart + "' in " + labels(view));
    }

    private static boolean has(View view, String labelStart) {
        return view.allButtons().stream().anyMatch(button -> plain(button.label()).startsWith(labelStart));
    }

    private static List<String> labels(View view) {
        return view.allButtons().stream().map(button -> plain(button.label())).toList();
    }

    /** Clicks a button of the last dialog like the router would, with these input values. */
    private void click(String labelStart, Map<String, Object> values) {
        View view = last();
        Button button = button(view, labelStart);
        assertNotNull(button.handler(), labelStart + " does something");
        button.handler().handle(new Click(this.alex.player, new FormValues(values), view));
    }

    private void click(String labelStart) {
        click(labelStart, Map.of());
    }

    /** The colour of the value after "Label: " on a button (the last coloured part of its label). */
    private static TextColor valueColor(Button button) {
        TextColor color = null;
        for (Component part : flatten(button.label())) {
            if (part.color() != null && !plain(part).isBlank()) {
                color = part.color();
            }
        }
        return color;
    }

    private static List<Component> flatten(Component component) {
        List<Component> all = new ArrayList<>();
        all.add(component);
        for (Component child : component.children()) {
            all.addAll(flatten(child));
        }
        return all;
    }

    private final class Click implements Submission {
        private final Player player;
        private final FormValues values;
        private final View view;

        Click(Player player, FormValues values, View view) {
            this.player = player;
            this.values = values;
            this.view = view;
        }

        @Override
        public Player player() {
            return this.player;
        }

        @Override
        public FormValues values() {
            return this.values;
        }

        @Override
        public View view() {
            return this.view;
        }

        @Override
        public void show(View next) {
            SettingsDialogsTest.this.shown.add(next);
        }

        @Override
        public void error(Component message) {
            SettingsDialogsTest.this.shown.add(this.view.withError(message, this.values));
        }

        @Override
        public void close() {
        }
    }

    @Test
    void theGroupListIsAColouredButtonPerGroupWithNothingAbove() {
        this.dialogs.open(this.alex.player, null);
        View list = last();
        assertEquals("Settings", plain(list.title()));
        assertTrue(list.body().isEmpty() && list.inputs().isEmpty(), "buttons only");
        assertEquals(2, list.columns());
        Button sounds = button(list, "Sounds");
        assertEquals(SettingCategories.SOUND.color(), valueColor(sounds), "each group in its colour");
        assertTrue(plain(sounds.tooltip()).startsWith("Volume and which sounds and pings play"), plain(sounds.tooltip()));
        assertTrue(plain(sounds.tooltip()).contains("6 settings, 0 changed"), plain(sounds.tooltip()));
        assertTrue(has(list, "Search settings") && has(list, "Changed settings (0)"), labels(list).toString());
        assertEquals("Close", plain(list.exit().label()));
    }

    @Test
    void aSwitchFlipsAtOnceAndThePageShowsItsNewState() throws Exception {
        assertTrue(this.dialogs.openGroup(this.alex.player, "sound", null));
        View page = last();
        assertEquals("Sounds settings", plain(page.title()));
        assertTrue(page.body().isEmpty() && page.inputs().isEmpty(), "no intro, no inputs: " + page.body());
        assertEquals(1, page.columns());
        Button pings = button(page, "Notification pings: ON");
        assertEquals(this.palette.on(), valueColor(pings), "ON in green");
        click("Notification pings");
        assertFalse(this.settings.get(this.alex.id, SharedSettings.SOUND_NOTIFY), "stored at once");
        assertEquals("false", this.db.row(this.alex.id, "sound-notify"));
        View again = last();
        assertEquals("Sounds settings", plain(again.title()), "the same page again");
        assertEquals(this.palette.off(), valueColor(button(again, "Notification pings: OFF")), "OFF in red");
        assertTrue(this.alex.chat.isEmpty() && this.alex.actionBar.isEmpty(), "no message for a click: " + this.alex.chat + this.alex.actionBar);
        click("Notification pings");
        assertTrue(this.settings.get(this.alex.id, SharedSettings.SOUND_NOTIFY));
        assertNull(this.db.row(this.alex.id, "sound-notify"), "the default deletes the row");
    }

    @Test
    void aChoiceMovesToItsNextOptionAndWrapsAround() throws Exception {
        this.dialogs.openGroup(this.alex.player, "sound", null);
        Button mention = button(last(), "Mention sound: Default");
        assertEquals(this.palette.accent(), valueColor(mention), "a choice in the accent colour");
        click("Mention sound");
        assertEquals(PingSound.BELL, this.settings.get(this.alex.id, SharedSettings.SOUND_MENTION));
        assertTrue(has(last(), "Mention sound: Bell"), labels(last()).toString());
        click("Mention sound");
        click("Mention sound");
        click("Mention sound");
        assertEquals(this.palette.off(), valueColor(button(last(), "Mention sound: Off")), "Off reads in the off colour");
        click("Mention sound");
        assertEquals(PingSound.DEFAULT, this.settings.get(this.alex.id, SharedSettings.SOUND_MENTION), "back to the first");
        assertNull(this.db.row(this.alex.id, "sound-mention"));
    }

    @Test
    void aNumberOpensASliderWhoseDoneStoresItAndComesBack() throws Exception {
        this.dialogs.openGroup(this.alex.player, "sound", null);
        click("Sound volume: 100%");
        View slider = last();
        assertEquals("Sound volume", plain(slider.title()));
        Input.Range range = (Input.Range) slider.inputs().getFirst();
        assertEquals("sound_volume", range.key());
        assertEquals(List.of(0L, 100L, 10L, 100L), List.of(range.min(), range.max(), range.step(), range.initial()));
        assertEquals("Sound volume (%)", plain(range.label()));
        assertEquals(List.of("Done", "Back"), labels(slider));
        click("Back");
        assertEquals(100L, this.settings.get(this.alex.id, SharedSettings.SOUND_VOLUME), "Back changes nothing");
        click("Sound volume");
        click("Done", Map.of("sound_volume", 60L));
        assertEquals(60L, this.settings.get(this.alex.id, SharedSettings.SOUND_VOLUME));
        assertEquals("Sounds settings", plain(last().title()));
        assertTrue(has(last(), "Sound volume: 60%"), labels(last()).toString());
    }

    @Test
    void tooltipsSayWhatASettingDoesItsOptionsDefaultAndWhatAClickDoes() {
        this.dialogs.openGroup(this.alex.player, "sound", null);
        String mention = plain(button(last(), "Mention sound").tooltip());
        assertEquals(List.of("The sound when someone mentions you. Default follows Notification pings.", "• Default", "• Bell", "• Pling",
            "• Chime", "• Off", "Default: Default", "Click for the next choice."), mention.lines().toList());
        String volume = plain(button(last(), "Sound volume").tooltip());
        assertTrue(volume.contains("From 0% to 100%, in steps of 10%") && volume.contains("Default: 100%")
            && volume.endsWith("Click to change it."), volume);
        String pings = plain(button(last(), "Notification pings").tooltip());
        assertTrue(pings.contains("Default: ON") && pings.endsWith("Click to switch it."), pings);
    }

    @Test
    void noPlayerFacingTextNamesThePlugin() {
        this.dialogs.open(this.alex.player, null);
        List<String> texts = new ArrayList<>();
        for (String group : List.of("chat", "social", "announcements", "sound", "teleport", "economy", "market", "combat", "display",
            "privacy", "afk", "crates", "spawners", "staff")) {
            if (this.dialogs.openGroup(this.alex.player, group, null)) {
                for (Button button : last().allButtons()) {
                    texts.add(plain(button.label()) + " " + plain(button.tooltip()));
                }
            }
        }
        assertFalse(texts.isEmpty());
        for (String text : texts) {
            assertFalse(text.contains("SiftCore"), text);
        }
    }

    @Test
    void aLockedSettingShowsItsValueGreyedAndAClickChangesNothing() {
        this.settings.overrides(new Overrides(Map.of(), Map.of("sound-errors", "false"), Set.of()));
        this.dialogs.openGroup(this.alex.player, "sound", null);
        Button errors = button(last(), "Error sounds: OFF");
        assertEquals(this.palette.secondary(), valueColor(errors), "greyed");
        assertTrue(plain(errors.tooltip()).contains("Set by the server."), plain(errors.tooltip()));
        click("Error sounds");
        assertFalse(this.settings.get(this.alex.id, SharedSettings.SOUND_ERRORS));
        assertTrue(has(last(), "Error sounds: OFF"));
    }

    @Test
    void aRefusedChangeShowsInRedOnThePage() {
        this.settings.listener(change -> !change.entry().id().equals("sound-success"));
        this.dialogs.openGroup(this.alex.player, "sound", null);
        click("Success chimes");
        View page = last();
        assertTrue(this.settings.get(this.alex.id, SharedSettings.SOUND_SUCCESS), "still on");
        assertTrue(has(page, "Success chimes: ON"));
        assertTrue(page.body().stream().anyMatch(body -> body instanceof Body.Text text && text.error()
            && plain(text.text()).equals("Success chimes couldn't be changed.")), page.body().toString());
    }

    @Test
    void aStaleButtonDoesWhatItShowed() {
        this.dialogs.openGroup(this.alex.player, "sound", null);
        View page = last();
        // While the page is open, a command turns click sounds off; the player clicks "Menu click sounds: ON".
        this.settings.set(this.alex.id, SharedSettings.SOUND_CLICKS, false, Change.feature());
        button(page, "Menu click sounds: ON").handler().handle(new Click(this.alex.player, FormValues.EMPTY, page));
        assertFalse(this.settings.get(this.alex.id, SharedSettings.SOUND_CLICKS), "the player asked for OFF and gets OFF");
        assertTrue(has(last(), "Menu click sounds: OFF"));
    }

    @Test
    void resettingAGroupAsksFirstAndPutsItBack() throws Exception {
        this.dialogs.openGroup(this.alex.player, "sound", null);
        assertFalse(has(last(), "Reset this group"), "nothing to reset yet");
        click("Notification pings");
        click("Mention sound");
        Button reset = button(last(), "Reset this group");
        assertTrue(plain(reset.tooltip()).contains("2 settings"), plain(reset.tooltip()));
        click("Reset this group");
        View confirm = last();
        assertEquals(View.Kind.CONFIRM, confirm.kind());
        assertEquals("Reset Sounds settings?", plain(confirm.title()));
        assertEquals("2 settings go back to their defaults.", plain(((Body.Text) confirm.body().getFirst()).text()));
        String names = plain(button(confirm, "Reset").tooltip());
        assertTrue(names.contains("Notification pings: off to on") && names.contains("Mention sound: Bell to Default"), names);
        click("Cancel");
        assertTrue(has(last(), "Notification pings: OFF"), "Cancel keeps them");
        click("Reset this group");
        click("Reset");
        assertTrue(has(last(), "Notification pings: ON") && has(last(), "Mention sound: Default") && !has(last(), "Reset this group"),
            labels(last()).toString());
        assertNull(this.db.row(this.alex.id, "sound-notify"));
        assertEquals("Reset 2 settings to their defaults.", plain(this.alex.actionBar.getLast()));
    }

    @Test
    void theChangedSettingsKeepASettingFlippedBackUntilTheyOpenAgain() {
        this.settings.set(this.alex.id, SharedSettings.SOUND_NOTIFY, false, Change.feature());
        this.settings.set(this.alex.id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.CHAT, Change.feature());
        this.dialogs.open(this.alex.player, null);
        assertTrue(plain(button(last(), "Changed settings (2)").tooltip()).contains("You changed 2 of"));
        click("Changed settings");
        View changed = last();
        assertEquals("Changed settings", plain(changed.title()));
        assertEquals(List.of("Notification pings: OFF", "Quick results and errors: Chat", "Reset everything", "Back"), labels(changed),
            "in dialog order (Sounds, then Display)");
        assertTrue(plain(button(changed, "Notification pings").tooltip()).startsWith("Sounds"), "the tooltip names the group");
        click("Notification pings");
        assertTrue(has(last(), "Notification pings: ON"), "flipped back to the default, still listed: " + labels(last()));
        click("Reset everything");
        click("Reset");
        assertEquals(AlertStyle.ACTIONBAR, this.settings.get(this.alex.id, SharedSettings.FEEDBACK_CHANNEL));
        assertEquals("Settings", plain(last().title()), "back to the list once nothing is left");
    }

    @Test
    void nothingChangedSaysSoInOneLine() {
        this.dialogs.openChanged(this.alex.player, null);
        View changed = last();
        assertEquals(List.of("Back"), labels(changed));
        assertEquals("You use the defaults for every setting.", plain(((Body.Text) changed.body().getFirst()).text()));
    }

    @Test
    void searchResultsAreTheSameButtons() {
        this.dialogs.openSearch(this.alex.player, "volume", null);
        View results = last();
        assertEquals("Search: volume", plain(results.title()));
        assertTrue(results.body().isEmpty());
        assertTrue(plain(button(results, "Sound volume: 100%").tooltip()).startsWith("Sounds"), "results name their group");
        click("Sound volume");
        click("Done", Map.of("sound_volume", 30L));
        assertEquals("Search: volume", plain(last().title()), "back on the results");
        assertTrue(has(last(), "Sound volume: 30%"));
        click("Back");
        assertEquals("Search settings", plain(last().title()), "Back returns to the form");
        this.dialogs.openSearch(this.alex.player, "zzzqqq", null);
        assertTrue(plain(((Body.Text) last().body().getFirst()).text()).contains("No setting matches zzzqqq"));
    }
}
