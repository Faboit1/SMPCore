package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled teams config, and the worlds where team homes are turned off. */
class TeamsSettingsTest {

    private static final MoneyFormat MONEY = MoneyFormat.defaults();
    private static final Set<String> WORLDS = Set.of("world", "world_nether", "world_the_end");

    @Test
    void theBundledConfigParsesWithTeamHomesEverywhere() {
        ConfigReader reader = new ConfigReader("features/teams.yml", Fakes.yaml("features/teams.yml"));
        TeamsSettings settings = TeamsSettings.parse(reader, MONEY, WORLDS::contains);
        assertEquals(List.of(), reader.problems());
        assertEquals(Set.of(), settings.homeDisabledWorlds());
        assertFalse(settings.homeDisabled("world"));
        assertEquals(0, settings.createCost(), "starting a team is free: the owner removed every fee");
        assertEquals(100, settings.listSize(), "All teams shows the 100 biggest teams, no pages");
    }

    @Test
    void aCostCanStillBeSet() {
        YamlConfiguration yaml = Fakes.yaml("features/teams.yml");
        yaml.set("create.cost", "50k");
        yaml.set("list-size", 5);
        ConfigReader reader = new ConfigReader("features/teams.yml", yaml);
        TeamsSettings settings = TeamsSettings.parse(reader, MONEY, WORLDS::contains);
        assertEquals(50_000, settings.createCost());
        assertEquals(100, settings.listSize(), "below 10 is refused and the default kept");
        assertEquals(1, reader.problems().size(), reader.problems().toString());
    }

    @Test
    void theMemberDialogOffersWhatTheRolesAllow() {
        TeamMenus.MemberActions ownerOnMember = TeamMenus.actionsFor(TeamRole.OWNER, TeamRole.MEMBER);
        assertTrue(ownerOnMember.promote() && ownerOnMember.kick() && ownerOnMember.transfer());
        assertFalse(ownerOnMember.demote(), "a member can't be made a member");
        TeamMenus.MemberActions ownerOnAdmin = TeamMenus.actionsFor(TeamRole.OWNER, TeamRole.ADMIN);
        assertTrue(ownerOnAdmin.demote() && ownerOnAdmin.kick() && ownerOnAdmin.transfer());
        assertFalse(ownerOnAdmin.promote());
        TeamMenus.MemberActions adminOnMember = TeamMenus.actionsFor(TeamRole.ADMIN, TeamRole.MEMBER);
        assertTrue(adminOnMember.kick(), "admins remove members");
        assertFalse(adminOnMember.promote() || adminOnMember.demote() || adminOnMember.transfer(), "only the owner changes roles");
        assertFalse(TeamMenus.actionsFor(TeamRole.ADMIN, TeamRole.ADMIN).any(), "admins can't act on admins");
        assertFalse(TeamMenus.actionsFor(TeamRole.MEMBER, TeamRole.MEMBER).any(), "members manage nobody");
        assertFalse(TeamMenus.actionsFor(TeamRole.ADMIN, TeamRole.OWNER).any());
        assertFalse(TeamMenus.actionsFor(null, TeamRole.MEMBER).any(), "someone outside the team manages nobody");
    }

    @Test
    void disabledWorldsAreReadAndUnknownWorldsReported() {
        YamlConfiguration yaml = Fakes.yaml("features/teams.yml");
        yaml.set("home.disabled-worlds", List.of("world_the_end", "events"));
        ConfigReader reader = new ConfigReader("features/teams.yml", yaml);
        TeamsSettings settings = TeamsSettings.parse(reader, MONEY, WORLDS::contains);
        assertTrue(settings.homeDisabled("world_the_end"));
        assertFalse(settings.homeDisabled("world"));
        assertEquals(1, reader.problems().size(), "events is not a loaded world: " + reader.problems());

        yaml.set("home.disabled-worlds", null);
        ConfigReader older = new ConfigReader("features/teams.yml", yaml);
        assertEquals(Set.of(), TeamsSettings.parse(older, MONEY, WORLDS::contains).homeDisabledWorlds());
        assertEquals(List.of(), older.problems(), "an older teams.yml without the key is fine");
    }
}
