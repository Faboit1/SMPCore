package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.api.SettingsView;
import net.siftvanilla.siftcore.api.SettingsView.Result;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.feature.settings.SettingsConfig.CategoryOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsApiTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID GONE = UUID.randomUUID();

    @TempDir
    Path dir;
    private SettingsDb db;
    private AuditLog audit;
    private final Map<String, CategoryOverride> overrides = new ConcurrentHashMap<>();
    private SettingsApi api;

    @BeforeEach
    void open() throws Exception {
        this.db = new SettingsDb(this.dir);
        this.audit = new AuditLog(this.db.database);
        this.api = new SettingsApi(this.db.settings, this.db.lang, this.audit, () -> Map.copyOf(this.overrides),
            SettingsDb.LOGGER);
    }

    /** The settings audit rows of a player, oldest first, once {@code count} of them are written. */
    private List<String> audited(UUID player, int count) throws Exception {
        long until = System.currentTimeMillis() + 5_000;
        while (true) {
            List<AuditLog.Entry> rows = this.audit.recent("settings.", player.toString(), 50).get(5, TimeUnit.SECONDS);
            if (rows.size() >= count || System.currentTimeMillis() > until) {
                List<String> lines = new ArrayList<>();
                for (AuditLog.Entry row : rows.reversed()) {
                    lines.add(row.actor() + " " + row.action() + " " + row.details());
                }
                return lines;
            }
            Thread.sleep(20);
        }
    }

    @AfterEach
    void close() {
        this.db.close();
    }

    @Test
    void settingsAndGroupsAreDescribedInPlainText() {
        SettingsView.SettingInfo volume = this.api.setting("sound-volume").orElseThrow();
        assertEquals(SettingsView.Type.NUMBER, volume.type());
        assertEquals("sound", volume.category());
        assertEquals("SiftCore volume", volume.label());
        assertEquals("100", volume.defaultValue());
        assertEquals(List.of(0L, 100L, 10L), List.of(volume.min(), volume.max(), volume.step()));
        assertEquals("%", volume.unit());
        SettingsView.SettingInfo channel = this.api.setting("feedback-channel").orElseThrow();
        assertEquals(SettingsView.Type.CHOICE, channel.type());
        assertEquals(List.of("actionbar", "chat", "both"), channel.options());
        assertEquals(SettingsView.Type.TOGGLE, this.api.setting("sound-notify").orElseThrow().type());
        assertEquals("siftcore.stats.hide", this.api.setting("hide-from-leaderboards").orElseThrow().permission());
        assertTrue(this.api.setting("nothing").isEmpty());
        assertEquals(this.db.settings.registry().byId().size(), this.api.settings().size());
        assertEquals("sound", this.api.categories().stream().filter(c -> c.id().equals("sound")).findFirst().orElseThrow().id());
        assertEquals("Sounds", this.api.categories().stream().filter(c -> c.id().equals("sound")).findFirst().orElseThrow().label());
        this.db.settings.overrides(new Overrides(Map.of("sound-volume", "60"), Map.of("quiet-in-combat", "true"), Set.of("sound-clicks")));
        assertEquals("60", this.api.setting("sound-volume").orElseThrow().defaultValue(), "the server's default");
        assertTrue(this.api.setting("quiet-in-combat").orElseThrow().locked());
        assertTrue(this.api.setting("sound-clicks").orElseThrow().hidden());
    }

    @Test
    void changesGoThroughTheRegistry() throws Exception {
        this.db.join(ALEX);
        assertEquals(Result.CHANGED, this.api.set(ALEX, "sound-volume", "40"));
        assertEquals("40", this.api.value(ALEX, "sound-volume"));
        assertEquals(Result.UNCHANGED, this.api.set(ALEX, "sound-volume", "40"));
        assertEquals(Result.INVALID, this.api.set(ALEX, "sound-volume", "45"));
        assertEquals(Result.UNCHANGED, this.api.set(ALEX, "feedback-channel", "Above the hotbar"), "the label of the current option");
        assertEquals(Result.CHANGED, this.api.set(ALEX, "feedback-channel", "Chat", "OtherPlugin"), "an option label works too");
        assertEquals("chat", this.api.value(ALEX, "feedback-channel"));
        assertEquals(Result.CHANGED, this.api.set(ALEX, "sound-notify", "off"));
        assertEquals(Result.UNKNOWN, this.api.set(ALEX, "nothing", "1"));
        assertNull(this.api.value(ALEX, "nothing"));
        assertEquals(Map.of("sound-volume", "40", "feedback-channel", "chat", "sound-notify", "false"),
            this.api.stored(ALEX).get(5, TimeUnit.SECONDS));
        this.db.insert(ALEX, "auction-sort", "price");
        assertFalse(this.api.stored(ALEX).get(5, TimeUnit.SECONDS).containsKey("auction-sort"), "only settings, not remembered UI state");
    }

    @Test
    void lockedAndHiddenSettingsRefuse() throws Exception {
        this.db.join(ALEX);
        this.db.settings.overrides(new Overrides(Map.of(), Map.of("quiet-in-combat", "true"), Set.of("sound-clicks")));
        assertEquals(Result.LOCKED, this.api.set(ALEX, "quiet-in-combat", "false"));
        assertEquals(Result.NOT_ALLOWED, this.api.set(ALEX, "sound-clicks", "false"));
        assertEquals(Result.LOCKED, this.api.reset(ALEX, "quiet-in-combat"));
        assertEquals(Result.NOT_ALLOWED, this.api.reset(ALEX, "sound-clicks"));
        assertEquals(Result.UNKNOWN, this.api.reset(ALEX, "nothing"));
    }

    @Test
    void resetPutsTheDefaultBack() throws Exception {
        this.db.join(ALEX);
        this.api.set(ALEX, "sound-volume", "20");
        assertEquals("20", this.db.row(ALEX, "sound-volume"));
        assertEquals(Result.CHANGED, this.api.reset(ALEX, "sound-volume"));
        assertEquals("100", this.api.value(ALEX, "sound-volume"));
        assertNull(this.db.row(ALEX, "sound-volume"), "the row is gone");
        assertEquals(Result.UNCHANGED, this.api.reset(ALEX, "sound-volume"));
    }

    @Test
    void offlinePlayersAreWrittenToTheTable() throws Exception {
        assertEquals(Result.CHANGED, this.api.set(GONE, "sound-volume", "70"));
        assertEquals("70", this.db.row(GONE, "sound-volume"));
        assertEquals("100", this.api.value(GONE, "sound-volume"), "values of players who are not online read as the default");
        assertEquals(Map.of("sound-volume", "70"), this.api.stored(GONE).get(5, TimeUnit.SECONDS));
        assertEquals(Result.CHANGED, this.api.reset(GONE, "sound-volume"));
        assertNull(this.db.row(GONE, "sound-volume"));
    }

    @Test
    void groupsComeInTheDialogsOrderWithTheServersIcons() {
        List<String> builtIn = this.api.categories().stream().map(SettingsView.CategoryInfo::id).toList();
        assertEquals("sound", builtIn.getFirst(), "built in, Sounds comes first of the shared groups: " + builtIn);
        assertEquals("display", this.api.categories().get(builtIn.indexOf("display")).icon());
        this.overrides.put("display", new CategoryOverride(5, "star"));
        List<SettingsView.CategoryInfo> moved = this.api.categories();
        assertEquals("display", moved.getFirst().id(), "the server moved Display to the top: " + moved);
        assertEquals(5, moved.getFirst().order());
        assertEquals("star", moved.getFirst().icon(), "the server's icon");
        assertEquals(builtIn.stream().filter(id -> !id.equals("display")).toList(),
            moved.stream().skip(1).map(SettingsView.CategoryInfo::id).toList(), "the others keep their built-in order");
        assertEquals("display", this.api.settings().getFirst().category(), "settings follow the groups' order");
        assertEquals(this.db.settings.registry().byId().size(), this.api.settings().size());
    }

    @Test
    void changesAndResetsAreAuditedWithTheValueBefore() throws Exception {
        this.db.join(ALEX);
        assertEquals(Result.CHANGED, this.api.set(ALEX, "sound-volume", "40", "OtherPlugin"));
        assertEquals(Result.UNCHANGED, this.api.set(ALEX, "sound-volume", "40", "OtherPlugin"));
        assertEquals(Result.INVALID, this.api.set(ALEX, "sound-volume", "45"));
        assertEquals(Result.CHANGED, this.api.reset(ALEX, "sound-volume"));
        assertEquals(Result.UNCHANGED, this.api.reset(ALEX, "sound-volume"));
        assertEquals(List.of("api:OtherPlugin settings.set sound-volume: 100 -> 40", "api settings.reset sound-volume: 40 -> 100"),
            audited(ALEX, 2), "only real changes are written");

        assertEquals(Result.CHANGED, this.api.set(GONE, "sound-volume", "70"));
        assertEquals(Result.CHANGED, this.api.set(GONE, "sound-volume", "30"));
        assertEquals(Result.CHANGED, this.api.reset(GONE, "sound-volume"));
        assertEquals(Result.CHANGED, this.api.reset(GONE, "sound-volume"), "reported as changed for an offline player");
        assertEquals(List.of("api settings.set sound-volume: 100 -> 70", "api settings.set sound-volume: 70 -> 30",
            "api settings.reset sound-volume: 30 -> 100"), audited(GONE, 3), "an offline player's stored value is read first");
    }

    @Test
    void auditActorsFitTheColumn() {
        assertEquals("api", SettingsApi.auditActor(null));
        assertEquals("api", SettingsApi.auditActor(" "));
        assertEquals("api", SettingsApi.auditActor("api"));
        assertEquals("api:OtherPlugin", SettingsApi.auditActor("OtherPlugin"));
        assertEquals(SettingsApi.ACTOR_LENGTH, SettingsApi.auditActor("x".repeat(100)).length());
    }

    @Test
    void anOldIdsRowCountsAsTheSettingsAndGoesWithAChange() throws Exception {
        // tpa-friends is not registered here, so its rows belong to friends-tpa (as once TPA reads the choice).
        UUID setBack = UUID.randomUUID();
        this.db.insert(setBack, "tpa-friends", "true");
        assertEquals(Map.of("friends-tpa", "all"), this.api.stored(setBack).get(5, TimeUnit.SECONDS), "the old switch reads as all");
        assertEquals(Result.CHANGED, this.api.set(setBack, "friends-tpa", "nobody"), "back to the default");
        assertNull(this.db.row(setBack, "tpa-friends"), "the old row went with the change");
        assertNull(this.db.row(setBack, "friends-tpa"), "the default needs no row");
        this.db.join(setBack);
        assertEquals(AutoAccept.NOBODY, this.db.settings.get(setBack, SharedSettings.FRIENDS_TPA), "the old row did not move back over it");
        assertEquals(List.of("api settings.set friends-tpa: all -> nobody"), audited(setBack, 1));

        UUID reset = UUID.randomUUID();
        this.db.insert(reset, "tpa-friends", "true");
        assertEquals(Result.CHANGED, this.api.reset(reset, "friends-tpa"));
        assertNull(this.db.row(reset, "tpa-friends"));
        this.db.join(reset);
        assertEquals(AutoAccept.NOBODY, this.db.settings.get(reset, SharedSettings.FRIENDS_TPA), "a reset sticks too");

        UUID kept = UUID.randomUUID();
        this.db.insert(kept, "tpa-friends", "true");
        this.db.settings.overrides(new Overrides(Map.of(), Map.of("friends-tpa", "nobody"), Set.of()));
        assertEquals(Result.LOCKED, this.api.set(kept, "friends-tpa", "all"));
        assertEquals(Result.LOCKED, this.api.reset(kept, "friends-tpa"));
        assertEquals("true", this.db.row(kept, "tpa-friends"), "a refused change leaves the old row alone");
    }

    @Test
    void resultsMatchTheRegistryResults() {
        for (SetResult result : SetResult.values()) {
            assertEquals(result.name(), SettingsApi.result(result).name());
        }
        assertEquals(SetResult.values().length, Result.values().length);
    }
}
