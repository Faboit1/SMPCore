package net.siftvanilla.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** End-to-end scenarios of the teams feature (/team, /tc, team dialogs, friendly fire and the staff commands). */
final class TeamsScenarios {

    private TeamsScenarios() {
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            this.body.run(e2e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    private static Scenario of(String name, Body body) {
        return new Named(name, body);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("teams-create", TeamsScenarios::create));
        list.add(of("teams-cost-change", TeamsScenarios::costChange));
        list.add(of("teams-invite", TeamsScenarios::invite));
        list.add(of("teams-roles", TeamsScenarios::roles));
        list.add(of("teams-ownership", TeamsScenarios::ownership));
        list.add(of("teams-chat", TeamsScenarios::chat));
        list.add(of("teams-home", TeamsScenarios::home));
        list.add(of("teams-home-rules", TeamsScenarios::homeRules));
        list.add(of("teams-ignored-invite", TeamsScenarios::ignoredInvite));
        list.add(of("teams-friendly-fire", TeamsScenarios::friendlyFire));
        list.add(of("teams-staff", TeamsScenarios::staff));
        list.add(of("teams-menu", TeamsScenarios::menu));
        list.add(of("teams-settings", TeamsScenarios::settings));
        list.add(of("teams-login-alerts", TeamsScenarios::loginAlerts));
        list.add(of("teams-seen-privacy", TeamsScenarios::seenPrivacy));
        return list;
    }

    // ------------------------------------------------------------------ settings helpers

    /** Changes a player's setting as another plugin would (the API cause); the change must go through. */
    private static <T> void set(E2E e2e, String name, net.siftvanilla.siftcore.core.player.PlayerSetting<T> setting, T value) {
        var result = e2e.services().settings().set(e2e.uuid(name), setting, value,
            net.siftvanilla.siftcore.core.player.Change.api("e2e"));
        e2e.expect(result.succeeded(), name + ": " + setting.id() + " = " + value + " went through: " + result);
    }

    /** Changes a registered setting by id from typed text (as the API would), checking it went through. */
    private static void set(E2E e2e, String name, String setting, String value) {
        var result = e2e.services().settings().setParsed(e2e.uuid(name), setting, value,
            net.siftvanilla.siftcore.core.player.Change.api("e2e"));
        e2e.expect(result.succeeded(), name + ": " + setting + " = " + value + " went through: " + result);
    }

    /** Writes test values into features/teams.yml (key: value, matched anywhere), reloads, runs, and restores the file. */
    private static void withConfig(E2E e2e, Map<String, String> values, Body action) throws Exception {
        Path file = e2e.services().plugin().getDataFolder().toPath().resolve("features/teams.yml");
        String original = Files.readString(file);
        String changed = original;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^(\\s*" + java.util.regex.Pattern.quote(entry.getKey())
                + ":)\\s*\\S+").matcher(changed);
            e2e.expect(matcher.find(), "features/teams.yml has " + entry.getKey());
            changed = matcher.replaceFirst("$1 " + java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        }
        Files.writeString(file, changed);
        e2e.console("sift reload");
        try {
            action.run(e2e);
        } finally {
            Files.writeString(file, original);
            e2e.console("sift reload");
        }
    }

    /** Leaves and waits until the server let the player go. */
    private static void quit(E2E e2e, Bot bot) {
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(bot.name) == null, bot.name + " left");
    }

    // ------------------------------------------------------------------ helpers

    /** A placeholder value of an online player, "" when it resolves to nothing. */
    private static String placeholder(E2E e2e, String player, String name) {
        Player online = e2e.player(player);
        String value = e2e.services().placeholders().resolve(online, name);
        return value == null ? "" : value;
    }

    private static String teamOf(E2E e2e, String player) {
        return placeholder(e2e, player, "team_name");
    }

    /** Funds the bot with exactly the cost, starts a team through the command and its confirmation. */
    private static void startTeam(E2E e2e, Bot bot, String team) {
        e2e.console("eco set " + bot.name + " 50k");
        e2e.eventually(() -> e2e.money(bot.name) == 50_000, bot.name + " has $50,000");
        bot.command("team create " + team);
        Bot.SeenDialog confirm = e2e.dialog(bot, "Start a team");
        e2e.expect(confirm.bodyText().contains("$50,000"), "the cost in the confirmation: " + confirm.body());
        e2e.click(bot, "Start team");
        e2e.dialog(bot, "Team " + team);
        e2e.eventually(() -> team.equals(teamOf(e2e, bot.name)), bot.name + " owns " + team);
        e2e.expect(e2e.money(bot.name) == 0, "the cost was charged once: " + e2e.money(bot.name));
    }

    /**
     * Opens /team and waits for that fresh dialog. The bot's earlier dialog is forgotten first so it can't match:
     * the server handles a command after clicks sent later, so a stale dialog must never be clicked.
     */
    private static Bot.SeenDialog openTeamMenu(E2E e2e, Bot bot, String title) {
        bot.clearLogs();
        bot.command("team");
        return e2e.dialog(bot, title);
    }

    /** Puts a player in a team with the staff command (no invite, no cooldown). */
    private static void staffAdd(E2E e2e, String team, String player) {
        List<String> out = e2e.consoleOutput("team admin add " + team + " " + player);
        e2e.expect(out.stream().anyMatch(line -> line.contains("Added " + player + " to " + team)), "staff add confirmed: " + out);
        e2e.eventually(() -> team.equals(teamOf(e2e, player)), player + " is in " + team);
    }

    /** Health before and after a hit by {@code attacker}, measured on the victim's thread around the hit. */
    private static double[] hit(E2E e2e, String victim, String attacker) {
        Player source = e2e.player(attacker);
        return e2e.onPlayer(victim, () -> {
            Player target = e2e.player(victim);
            double before = target.getHealth();
            target.damage(2.0, source);
            return new double[] {before, target.getHealth()};
        });
    }

    // ------------------------------------------------------------------ scenarios

    static void create(E2E e2e) {
        String founder = e2e.name("Founder");
        String rival = e2e.name("Rival");
        String team = e2e.name("Alpha");
        Bot a = e2e.bot(founder);
        Bot b = e2e.bot(rival);

        e2e.step("the team menu without a team offers to start one and shows the cost");
        e2e.console("eco set " + founder + " 60k");
        e2e.eventually(() -> e2e.money(founder) == 60_000, "founder funded");
        Bot.SeenDialog none = openTeamMenu(e2e, a, "Team");
        e2e.expect(none.bodyText().contains("not in a team") && none.bodyText().contains("$50,000"), "no-team body: " + none.body());
        e2e.expect(none.button("Start a team") != null, "a start button: " + none.buttons());

        e2e.step("the name form refuses bad names and keeps the dialog open");
        e2e.click(a, "Start a team");
        e2e.dialog(a, "Start a team");
        e2e.click(a, "Continue", Map.of("name", "ab"));
        Bot.SeenDialog shortName = e2e.dialog(a, "Start a team");
        e2e.expect(shortName.bodyText().contains("3 to 16 characters"), "length error: " + shortName.body());
        e2e.click(a, "Continue", Map.of("name", "bad name"));
        Bot.SeenDialog badChars = e2e.dialog(a, "Start a team");
        e2e.expect(badChars.bodyText().contains("letters, numbers and _"), "character error: " + badChars.body());

        e2e.step("a valid name asks to confirm the cost, then creates the team and charges once");
        e2e.click(a, "Continue", Map.of("name", team));
        Bot.SeenDialog confirm = e2e.dialog(a, "Start a team");
        e2e.expect("confirm".equals(confirm.type()) && confirm.bodyText().contains(team) && confirm.bodyText().contains("$50,000"),
            "a confirmation with the name and cost: " + confirm.body());
        e2e.expect(e2e.money(founder) == 60_000, "nothing charged before confirming");
        e2e.click(a, "Start team");
        Bot.SeenDialog main = e2e.dialog(a, "Team " + team);
        e2e.expect(main.bodyText().contains(founder) && main.bodyText().contains("owner, online"), "the owner in the member list: " + main.body());
        e2e.eventually(() -> e2e.money(founder) == 10_000, "charged $50,000: " + e2e.money(founder));
        e2e.eventually(() -> a.chatContains("You created the team " + team + " for $50,000."), "a receipt in chat: " + a.chat());
        e2e.expect(team.equals(teamOf(e2e, founder)), "team_name placeholder");
        e2e.expect("owner".equals(placeholder(e2e, founder, "team_role")), "team_role placeholder");
        e2e.expect("1".equals(placeholder(e2e, founder, "team_members")), "team_members placeholder");
        e2e.expect("1".equals(placeholder(e2e, founder, "team_online")), "team_online placeholder");

        e2e.step("one team per player");
        a.clearLogs();
        e2e.console("eco give " + founder + " 50k");
        a.command("team create " + e2e.name("Second"));
        e2e.eventually(() -> a.actionBarContains("already in a team"), "already in a team: " + a.actionBar());

        e2e.step("not enough money charges nothing");
        e2e.console("eco set " + rival + " 10k");
        e2e.eventually(() -> e2e.money(rival) == 10_000, "rival funded");
        b.clearLogs();
        b.command("team create " + e2e.name("Poor"));
        e2e.eventually(() -> b.actionBarContains("You need $50,000"), "not enough money: " + b.actionBar());
        e2e.expect(e2e.money(rival) == 10_000, "nothing charged");

        e2e.step("names are unique ignoring case");
        e2e.console("eco set " + rival + " 200k");
        e2e.eventually(() -> e2e.money(rival) == 200_000, "rival funded again");
        b.clearLogs();
        b.command("team create " + team.toLowerCase());
        e2e.eventually(() -> b.actionBarContains("already a team called"), "name taken: " + b.actionBar());

        e2e.step("three instant clicks on the confirmation create one team and charge once");
        String quick = e2e.name("Quick");
        b.command("team create " + quick);
        e2e.dialog(b, "Start a team");
        b.clickButton("Start team", Map.of());
        b.clickButton("Start team", Map.of());
        b.clickButton("Start team", Map.of());
        e2e.eventually(() -> quick.equals(teamOf(e2e, rival)), "the team exists");
        e2e.sleep(1_500);
        e2e.expect(e2e.money(rival) == 150_000, "charged exactly once: " + e2e.money(rival));
    }

    static void costChange(E2E e2e) throws Exception {
        String name = e2e.name("Saver");
        String team = e2e.name("Juliet");
        Bot a = e2e.bot(name);
        Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/teams.yml");
        String original = Files.readString(config);
        e2e.expect(original.contains("cost: 50k"), "the test server uses the default cost");
        try {
            e2e.console("eco set " + name + " 100k");
            e2e.eventually(() -> e2e.money(name) == 100_000, "funded");
            a.command("team create " + team);
            Bot.SeenDialog confirm = e2e.dialog(a, "Start a team");
            e2e.expect(confirm.bodyText().contains("$50,000"), "the shown cost: " + confirm.body());

            e2e.step("a reload changes the cost while the confirmation is open: nothing is charged");
            Files.writeString(config, original.replace("cost: 50k", "cost: 60k"));
            e2e.console("sift reload");
            e2e.click(a, "Start team");
            Bot.SeenDialog again = e2e.dialog(a, "Start a team");
            e2e.expect("confirm".equals(again.type()) && again.bodyText().contains("$60,000") && again.bodyText().contains("just changed"),
                "the new cost and why: " + again.body());
            e2e.expect(e2e.money(name) == 100_000, "nothing charged: " + e2e.money(name));
            e2e.expect(teamOf(e2e, name).isEmpty(), "no team yet");

            e2e.step("confirming the new cost creates the team for it");
            e2e.click(a, "Start team");
            e2e.dialog(a, "Team " + team);
            e2e.eventually(() -> e2e.money(name) == 40_000, "charged the new cost: " + e2e.money(name));
        } finally {
            Files.writeString(config, original);
            e2e.console("sift reload");
        }
    }

    static void invite(E2E e2e) {
        String ownerName = e2e.name("Inviter");
        String guestName = e2e.name("Guest");
        String otherName = e2e.name("Shy");
        String thiefName = e2e.name("Thief");
        String team = e2e.name("Bravo");
        Bot owner = e2e.bot(ownerName);
        Bot guest = e2e.bot(guestName);
        Bot shy = e2e.bot(otherName);
        Bot thief = e2e.bot(thiefName);
        startTeam(e2e, owner, team);

        e2e.step("joining without an invite is refused");
        thief.clearLogs();
        thief.command("team join " + team);
        e2e.eventually(() -> thief.actionBarContains("You don't have an invite from " + team), "no invite: " + thief.actionBar());

        e2e.step("an invite arrives in chat with a dialog to answer it");
        owner.clearLogs();
        guest.clearLogs();
        owner.command("team invite " + guestName);
        e2e.eventually(() -> owner.actionBarContains("Invited " + guestName), "invite sent: " + owner.actionBar());
        e2e.eventually(() -> !guest.chatDialogs().isEmpty(), "a clickable invite in chat: " + guest.chat());
        e2e.expect(guest.chatContains(ownerName + " invited you to join " + team), "invite text: " + guest.chat());
        e2e.expect(guest.openChatDialog("invited you"), "the invite opens a dialog");
        Bot.SeenDialog answer = guest.dialog();
        e2e.expect(answer.title().contains("Team invite") && answer.bodyText().contains(team), "invite dialog: " + answer.body());
        e2e.expect(answer.button("Join") != null && answer.button("Decline") != null, "join and decline: " + answer.buttons());

        e2e.step("another player cannot use the invite's buttons");
        thief.rawClick(answer.button("Join").actionId(), new CompoundTag());
        e2e.sleep(800);
        e2e.expect(teamOf(e2e, thiefName).isEmpty(), "the thief joined nothing");
        e2e.expect(!thief.disconnected(), "the thief stays connected");

        e2e.step("the invitee joins");
        e2e.click(guest, "Join");
        e2e.eventually(() -> team.equals(teamOf(e2e, guestName)), "guest joined");
        e2e.eventually(() -> guest.chatContains("You joined " + team + "."), "join receipt: " + guest.chat());
        e2e.eventually(() -> owner.chatContains(guestName + " joined the team."), "the team is told: " + owner.chat());
        e2e.expect("2".equals(placeholder(e2e, ownerName, "team_members")), "two members");
        e2e.expect("member".equals(placeholder(e2e, guestName, "team_role")), "the guest is a member");

        e2e.step("a used invite cannot be used again");
        guest.rawClick(answer.button("Join").actionId(), new CompoundTag());
        guest.command("team join " + team);
        e2e.eventually(() -> guest.actionBarContains("already in a team") || guest.actionBarContains("don't have an invite"),
            "no second join: " + guest.actionBar());
        e2e.expect("2".equals(placeholder(e2e, ownerName, "team_members")), "still two members");

        e2e.step("declining tells both sides and keeps the player out");
        e2e.sleep(3_100);
        owner.clearLogs();
        owner.command("team invite " + otherName);
        e2e.eventually(() -> shy.openChatDialog("invited you"), "the second invite arrives");
        e2e.click(shy, "Decline");
        e2e.eventually(() -> shy.actionBarContains("You declined the invite from " + team), "decline receipt: " + shy.actionBar());
        e2e.eventually(() -> owner.actionBarContains(otherName + " declined your team invite"), "inviter told: " + owner.actionBar());
        e2e.expect(teamOf(e2e, otherName).isEmpty(), "the shy player stays out");
        shy.clearLogs();
        shy.command("team join " + team);
        e2e.eventually(() -> shy.actionBarContains("You don't have an invite from " + team), "the declined invite is gone: " + shy.actionBar());

        e2e.step("members cannot invite and nobody can invite a teammate twice");
        guest.clearLogs();
        guest.command("team invite " + thiefName);
        e2e.eventually(() -> guest.actionBarContains("Only the team owner and admins"), "members can't invite: " + guest.actionBar());
        e2e.sleep(3_100);
        owner.clearLogs();
        owner.command("team invite " + guestName);
        e2e.eventually(() -> owner.actionBarContains(guestName + " is already in your team"), "already a member: " + owner.actionBar());
    }

    static void roles(E2E e2e) {
        String ownerName = e2e.name("Boss");
        String adminName = e2e.name("Deputy");
        String memberName = e2e.name("Rookie");
        String extraName = e2e.name("Extra");
        String team = e2e.name("Charlie");
        Bot owner = e2e.bot(ownerName);
        Bot deputy = e2e.bot(adminName);
        Bot rookie = e2e.bot(memberName);
        e2e.bot(extraName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, adminName);
        staffAdd(e2e, team, memberName);

        e2e.step("members can't kick or promote");
        rookie.clearLogs();
        rookie.command("team kick " + adminName);
        e2e.eventually(() -> rookie.actionBarContains("Only the team owner and admins"), "members can't kick: " + rookie.actionBar());
        rookie.command("team promote " + adminName);
        e2e.eventually(() -> rookie.actionBarContains("Only the team owner can"), "members can't promote: " + rookie.actionBar());

        e2e.step("the owner promotes from the dialog picker");
        openTeamMenu(e2e, owner, "Team " + team);
        e2e.click(owner, "Make an admin");
        Bot.SeenDialog pick = e2e.dialog(owner, "Make an admin");
        e2e.expect(pick.inputs().containsKey("member"), "a member choice: " + pick.inputs());
        e2e.click(owner, "Make admin", Map.of("member", e2e.uuid(adminName).toString()));
        e2e.dialog(owner, "Team " + team);
        e2e.eventually(() -> "admin".equals(placeholder(e2e, adminName, "team_role")), "deputy is an admin");
        e2e.eventually(() -> rookie.chatContains(ownerName + " made " + adminName + " an admin."), "the team is told: " + rookie.chat());

        e2e.step("admins can kick members but not other admins, and can't promote");
        deputy.clearLogs();
        deputy.command("team promote " + memberName);
        e2e.eventually(() -> deputy.actionBarContains("Only the team owner can"), "admins can't promote: " + deputy.actionBar());
        deputy.command("team kick " + ownerName);
        e2e.eventually(() -> deputy.actionBarContains("You can't do that to " + ownerName), "admins can't kick the owner: " + deputy.actionBar());
        deputy.command("team kick " + memberName);
        e2e.eventually(() -> teamOf(e2e, memberName).isEmpty(), "the member was removed");
        e2e.eventually(() -> rookie.chatContains("You were removed from " + team + "."), "the member is told: " + rookie.chat());
        e2e.eventually(() -> owner.chatContains(adminName + " removed " + memberName + " from the team."), "the team is told: " + owner.chat());

        e2e.step("admins invite; the owner demotes and kicks from the dialogs");
        deputy.clearLogs();
        deputy.command("team invite " + extraName);
        e2e.eventually(() -> deputy.actionBarContains("Invited " + extraName), "admins can invite: " + deputy.actionBar());
        owner.command("team demote " + adminName);
        e2e.eventually(() -> "member".equals(placeholder(e2e, adminName, "team_role")), "deputy is a member again");
        openTeamMenu(e2e, owner, "Team " + team);
        e2e.click(owner, "Remove a member");
        e2e.dialog(owner, "Remove a member");
        e2e.click(owner, "Remove", Map.of("member", e2e.uuid(adminName).toString()));
        e2e.eventually(() -> teamOf(e2e, adminName).isEmpty(), "kicked from the picker");
        e2e.expect("1".equals(placeholder(e2e, ownerName, "team_members")), "only the owner is left");

        e2e.step("a forged choice outside the offered members changes nothing");
        staffAdd(e2e, team, memberName);
        openTeamMenu(e2e, owner, "Team " + team);
        e2e.click(owner, "Remove a member");
        e2e.dialog(owner, "Remove a member");
        e2e.click(owner, "Remove", Map.of("member", e2e.uuid(ownerName).toString()));
        e2e.sleep(500);
        e2e.expect(team.equals(teamOf(e2e, ownerName)) && team.equals(teamOf(e2e, memberName)), "nobody was removed");
    }

    static void ownership(E2E e2e) {
        String ownerName = e2e.name("Elder");
        String heirName = e2e.name("Heir");
        String leaverName = e2e.name("Leaver");
        String team = e2e.name("Delta");
        Bot owner = e2e.bot(ownerName);
        Bot heir = e2e.bot(heirName);
        Bot leaver = e2e.bot(leaverName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, heirName);
        staffAdd(e2e, team, leaverName);

        e2e.step("the owner can't leave");
        owner.clearLogs();
        owner.command("team leave");
        e2e.eventually(() -> owner.actionBarContains("You own this team"), "owner can't leave: " + owner.actionBar());

        e2e.step("transfer asks for confirmation; cancelling keeps the owner");
        owner.command("team transfer " + heirName);
        Bot.SeenDialog confirm = e2e.dialog(owner, "Hand over the team");
        e2e.expect(confirm.bodyText().contains(heirName), "the heir in the confirmation: " + confirm.body());
        e2e.click(owner, "Cancel");
        e2e.sleep(300);
        e2e.expect("owner".equals(placeholder(e2e, ownerName, "team_role")), "still the owner");

        e2e.step("confirming hands the team over; the old owner becomes an admin");
        owner.command("team transfer " + heirName);
        e2e.dialog(owner, "Hand over the team");
        e2e.click(owner, "Hand over");
        e2e.eventually(() -> "owner".equals(placeholder(e2e, heirName, "team_role")), "the heir owns the team");
        e2e.expect("admin".equals(placeholder(e2e, ownerName, "team_role")), "the old owner is an admin");
        e2e.eventually(() -> leaver.chatContains(ownerName + " handed the team to " + heirName + "."), "the team is told: " + leaver.chat());
        owner.clearLogs();
        owner.command("team disband");
        e2e.eventually(() -> owner.actionBarContains("Only the team owner can"), "the old owner lost owner powers: " + owner.actionBar());

        e2e.step("a member leaves from the dialog after confirming");
        openTeamMenu(e2e, leaver, "Team " + team);
        e2e.click(leaver, "Leave the team");
        e2e.dialog(leaver, "Leave the team");
        e2e.click(leaver, "Leave");
        e2e.eventually(() -> teamOf(e2e, leaverName).isEmpty(), "left the team");
        e2e.eventually(() -> leaver.chatContains("You left " + team + "."), "leave receipt: " + leaver.chat());
        e2e.eventually(() -> heir.chatContains(leaverName + " left the team."), "the team is told: " + heir.chat());

        e2e.step("the owner disbands from the dialog; nothing is refunded");
        long before = e2e.money(heirName);
        openTeamMenu(e2e, heir, "Team " + team);
        e2e.click(heir, "Disband the team");
        Bot.SeenDialog disband = e2e.dialog(heir, "Disband the team");
        e2e.expect(disband.bodyText().contains("isn't refunded"), "no refund warning: " + disband.body());
        e2e.click(heir, "Disband");
        e2e.eventually(() -> teamOf(e2e, heirName).isEmpty() && teamOf(e2e, ownerName).isEmpty(), "the team is gone");
        e2e.eventually(() -> owner.chatContains(heirName + " disbanded " + team + "."), "members are told: " + owner.chat());
        e2e.expect(e2e.money(heirName) == before, "nothing refunded");
        List<String> info = e2e.consoleOutput("team info " + team);
        e2e.expect(info.stream().anyMatch(line -> line.contains("no team called " + team)), "the name is free: " + info);
    }

    static void chat(E2E e2e) {
        String ownerName = e2e.name("Talker");
        String mateName = e2e.name("Mate");
        String outsiderName = e2e.name("Outsider");
        String spyName = e2e.name("Spy");
        String team = e2e.name("Echo");
        Bot talker = e2e.bot(ownerName);
        Bot mate = e2e.bot(mateName);
        Bot outsider = e2e.bot(outsiderName);
        Bot spy = e2e.bot(spyName);

        e2e.step("team chat needs a team");
        talker.clearLogs();
        talker.command("tc hello");
        e2e.eventually(() -> talker.actionBarContains("not in a team"), "no team: " + talker.actionBar());

        startTeam(e2e, talker, team);
        staffAdd(e2e, team, mateName);
        e2e.console("op " + spyName);

        e2e.step("/tc reaches the team and staff who spy, nobody else");
        talker.clearLogs();
        mate.clearLogs();
        outsider.clearLogs();
        spy.clearLogs();
        talker.command("tc meet at the base");
        e2e.eventually(() -> mate.chatContains("Team " + ownerName + ": meet at the base"), "the teammate gets it: " + mate.chat());
        e2e.eventually(() -> talker.chatContains("Team " + ownerName + ": meet at the base"), "the sender sees it: " + talker.chat());
        e2e.eventually(() -> spy.chatContains("Team " + team + ", " + ownerName + ": meet at the base"), "staff spy sees it: " + spy.chat());
        e2e.sleep(800);
        e2e.expect(!outsider.chatContains("meet at the base"), "the outsider sees nothing: " + outsider.chat());

        e2e.step("tags in a message stay literal text");
        talker.command("tc <red>not red</red> <click:run_command:'/op x'>x");
        e2e.eventually(() -> mate.chatContains("<red>not red</red>"), "literal tags: " + mate.chat());

        e2e.step("team chat mode moves typed chat to the team");
        talker.clearLogs();
        talker.command("team chat");
        e2e.eventually(() -> talker.actionBarContains("Team chat on"), "chat mode on: " + talker.actionBar());
        mate.clearLogs();
        outsider.clearLogs();
        talker.chat("secret plan");
        e2e.eventually(() -> mate.chatContains("Team " + ownerName + ": secret plan"), "the teammate gets typed chat: " + mate.chat());
        e2e.sleep(1_000);
        e2e.expect(!outsider.chatContains("secret plan"), "nothing reaches public chat: " + outsider.chat());

        e2e.step("turning it off sends typed chat publicly again");
        talker.command("tc");
        e2e.eventually(() -> talker.actionBarContains("Team chat off"), "chat mode off: " + talker.actionBar());
        talker.chat("public hello");
        e2e.eventually(() -> outsider.chatContains("public hello"), "public chat works: " + outsider.chat());

        e2e.step("leaving the team turns team chat mode off");
        mate.command("team chat");
        e2e.eventually(() -> mate.actionBarContains("Team chat on"), "mate in chat mode");
        mate.command("team leave");
        e2e.eventually(() -> teamOf(e2e, mateName).isEmpty(), "mate left");
        outsider.clearLogs();
        mate.chat("back in public");
        e2e.eventually(() -> outsider.chatContains("back in public"), "chat is public after leaving: " + outsider.chat());
        e2e.console("deop " + spyName);
    }

    static void home(E2E e2e) {
        String ownerName = e2e.name("Homer");
        String memberName = e2e.name("Lodger");
        String team = e2e.name("Foxtrot");
        Bot owner = e2e.bot(ownerName);
        Bot member = e2e.bot(memberName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, memberName);

        e2e.step("no home yet");
        owner.clearLogs();
        owner.command("team home");
        e2e.eventually(() -> owner.actionBarContains("no home yet"), "no home: " + owner.actionBar());

        e2e.step("members can't set the home; owners and admins can");
        member.clearLogs();
        member.command("team sethome");
        e2e.eventually(() -> member.actionBarContains("Only the team owner and admins"), "members can't set it: " + member.actionBar());
        Location home = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getLocation());
        owner.command("team sethome");
        e2e.eventually(() -> member.chatContains(ownerName + " set the team home."), "the team is told: " + member.chat());

        e2e.step("the team home teleports after the warmup");
        e2e.onPlayer(ownerName, () -> e2e.player(ownerName).teleportAsync(home.clone().add(0, 6, 0)));
        e2e.eventually(() -> e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getLocation().distance(home)) > 4,
            "the owner is away from the home");
        e2e.sleep(500);
        owner.clearLogs();
        long start = System.currentTimeMillis();
        owner.command("team home");
        e2e.eventually(() -> owner.actionBarContains("Teleporting in"), "a warmup: " + owner.actionBar());
        // Checked on the server (the bots' client side can lag on a busy machine); the warmup must have passed.
        e2e.eventually(() -> e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getLocation().distance(home)) < 1.0,
            30_000, "back at the home");
        long took = System.currentTimeMillis() - start;
        e2e.expect(took >= 4_500, "the teleport waited for the 5s warmup, took " + took + " ms");
        e2e.eventually(() -> owner.actionBarContains("Teleported"), 30_000, "teleported: " + owner.actionBar());

        e2e.step("members use the home too, and the menu shows it");
        Bot.SeenDialog main = openTeamMenu(e2e, member, "Team " + team);
        e2e.expect(main.bodyText().contains("Home at " + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ()),
            "the home in the menu: " + main.body());
        e2e.expect(main.button("Team home") != null, "a home button: " + main.buttons());
        e2e.expect(main.button("Set home here") == null, "members get no set-home button: " + main.buttons());
    }

    /**
     * The team home follows /sethome's rules: not inside the protected spawn, not in a world disabled in teams.yml or in
     * homes.yml, and a home stored inside spawn (from before the rules) can't be used.
     */
    static void homeRules(E2E e2e) throws Exception {
        String ownerName = e2e.name("Rules");
        String team = e2e.name("Hotel");
        Bot owner = e2e.botAtSpawn(ownerName);
        startTeam(e2e, owner, team);

        e2e.step("no team home at spawn");
        owner.clearLogs();
        owner.command("team sethome");
        e2e.eventually(() -> owner.actionBarContains("You can't set the team home at spawn."), "refused at spawn: " + owner.actionBar());
        e2e.sleep(500);
        e2e.expect(!placeholder(e2e, ownerName, "team_home").contains(","), "no home: " + placeholder(e2e, ownerName, "team_home"));

        e2e.step("outside spawn it works");
        e2e.leaveSpawn(owner);
        owner.clearLogs();
        owner.command("team sethome");
        e2e.eventually(() -> owner.anyFeedbackContains("set the team home") || owner.anyFeedbackContains("Team home set"),
            "set outside spawn: " + owner.chat() + owner.actionBar());

        e2e.step("in a world where team homes are off, the home can't be set or used");
        String world = e2e.player(ownerName).getWorld().getName();
        Path file = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve("features/teams.yml");
        String original = Files.readString(file);
        e2e.expect(original.contains("  disabled-worlds: []"), "teams.yml has home.disabled-worlds");
        Files.writeString(file, original.replace("  disabled-worlds: []", "  disabled-worlds: [" + world + "]"));
        try {
            e2e.expect(String.join(" ", e2e.consoleOutput("sift reload")).contains("Reloaded"), "teams.yml reloads");
            owner.clearLogs();
            owner.command("team sethome");
            e2e.eventually(() -> owner.actionBarContains("Team homes are turned off in that world."), "set refused: " + owner.actionBar());
            owner.clearLogs();
            owner.command("team home");
            e2e.eventually(() -> owner.actionBarContains("Team homes are turned off in that world."), "use refused: " + owner.actionBar());
        } finally {
            Files.writeString(file, original);
            e2e.console("sift reload");
        }

        e2e.step("homes.yml's disabled worlds apply to team homes too");
        Path homesFile = file.resolveSibling("homes.yml");
        String homesOriginal = Files.readString(homesFile);
        e2e.expect(homesOriginal.contains("\ndisabled-worlds: []"), "homes.yml has disabled-worlds");
        Files.writeString(homesFile, homesOriginal.replace("\ndisabled-worlds: []", "\ndisabled-worlds: [" + world + "]"));
        try {
            e2e.expect(String.join(" ", e2e.consoleOutput("sift reload")).contains("Reloaded"), "homes.yml reloads");
            owner.clearLogs();
            owner.command("team sethome");
            e2e.eventually(() -> owner.actionBarContains("Team homes are turned off in that world."), "set refused: " + owner.actionBar());
            owner.clearLogs();
            owner.command("team home");
            e2e.eventually(() -> owner.actionBarContains("Team homes are turned off in that world."), "use refused: " + owner.actionBar());
        } finally {
            Files.writeString(homesFile, homesOriginal);
            e2e.console("sift reload");
        }

        e2e.step("a team home stored inside spawn before the rules can't be used");
        Location spawn = e2e.feature(net.siftvanilla.siftcore.feature.spawn.SpawnFeature.class).location();
        e2e.expect(spawn != null && e2e.spawnArea().contains(spawn), "the spawn point is inside the protected area");
        java.lang.reflect.Field field = net.siftvanilla.siftcore.feature.teams.TeamsFeature.class.getDeclaredField("service");
        field.setAccessible(true);
        var service = (net.siftvanilla.siftcore.feature.teams.TeamService) field.get(e2e.feature(net.siftvanilla.siftcore.feature.teams.TeamsFeature.class));
        e2e.expect(service.setHome(e2e.uuid(ownerName), new net.siftvanilla.siftcore.feature.teams.TeamHome(spawn.getWorld().getName(),
            spawn.getX(), spawn.getY(), spawn.getZ(), 0f, 0f)).ok(), "an old home at spawn is planted (no rules, as before them)");
        Location before = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getLocation());
        owner.clearLogs();
        owner.command("team home");
        e2e.eventually(() -> owner.actionBarContains("The team home is at spawn, where team homes aren't allowed."),
            "use refused: " + owner.actionBar());
        e2e.sleep(4_000);
        Location after = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getLocation());
        e2e.expect(after.getWorld().equals(before.getWorld()) && after.distance(before) < 1.0, "not moved to spawn: " + before + " -> " + after);
    }

    /** A player who ignores someone gets no team invite from them; the inviter learns nothing about the ignore. */
    static void ignoredInvite(E2E e2e) {
        String ownerName = e2e.name("Inviter");
        String targetName = e2e.name("Ignorer");
        String team = e2e.name("India");
        Bot owner = e2e.bot(ownerName);
        Bot target = e2e.bot(targetName);
        startTeam(e2e, owner, team);
        var ignores = e2e.feature(net.siftvanilla.siftcore.feature.chat.ChatFeature.class).ignores();

        e2e.step("the target ignores the inviter");
        target.command("ignore " + ownerName);
        e2e.eventually(() -> ignores.ignores(e2e.uuid(targetName), e2e.uuid(ownerName)), "ignored");

        e2e.step("the invite is refused and never arrives");
        owner.clearLogs();
        target.clearLogs();
        owner.command("team invite " + targetName);
        // The same words as the team-invites setting's refusal: the inviter can't tell an ignore from a choice.
        e2e.eventually(() -> owner.actionBarContains(targetName + " isn't taking team invites from you."), "refused: " + owner.actionBar());
        e2e.sleep(1_000);
        e2e.expect(!target.chatContains(ownerName), "no invite message: " + target.chat());

        e2e.step("after unignoring, invites arrive again");
        target.command("unignore " + ownerName);
        e2e.eventually(() -> !ignores.ignores(e2e.uuid(targetName), e2e.uuid(ownerName)), "not ignored");
        e2e.sleep(3_500);
        owner.command("team invite " + targetName);
        e2e.eventually(() -> target.chatContains(ownerName) && target.chatContains(team), "the invite arrives: " + target.chat());
    }

    static void friendlyFire(E2E e2e) {
        String ownerName = e2e.name("Gladius");
        String mateName = e2e.name("Shield");
        String strangerName = e2e.name("Stranger");
        String team = e2e.name("Golf");
        Bot owner = e2e.bot(ownerName);
        Bot mate = e2e.bot(mateName);
        e2e.bot(strangerName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, mateName);
        e2e.sleep(4_000);

        e2e.step("teammates can't hurt each other while friendly fire is off");
        owner.clearLogs();
        double[] blocked = hit(e2e, mateName, ownerName);
        e2e.expect(blocked[1] == blocked[0], "no damage from a teammate: " + blocked[0] + " -> " + blocked[1]);
        e2e.eventually(() -> owner.actionBarContains("Friendly fire is off"), "the attacker is told: " + owner.actionBar());

        e2e.step("players outside the team still can");
        e2e.sleep(1_000);
        double[] stranger = hit(e2e, mateName, strangerName);
        e2e.expect(stranger[1] < stranger[0], "damage from outside the team: " + stranger[0] + " -> " + stranger[1]);

        e2e.step("members can't toggle friendly fire; the owner can");
        mate.clearLogs();
        mate.command("team friendlyfire");
        e2e.eventually(() -> mate.actionBarContains("Only the team owner and admins"), "members can't toggle: " + mate.actionBar());
        owner.command("team friendlyfire on");
        e2e.eventually(() -> mate.chatContains(ownerName + " turned friendly fire on."), "the team is told: " + mate.chat());
        owner.clearLogs();
        owner.command("team friendlyfire on");
        e2e.eventually(() -> owner.actionBarContains("already on"), "already on: " + owner.actionBar());

        e2e.step("with friendly fire on, teammates can hurt each other");
        e2e.sleep(1_000);
        double[] allowed = hit(e2e, mateName, ownerName);
        e2e.expect(allowed[1] < allowed[0], "teammate damage with friendly fire on: " + allowed[0] + " -> " + allowed[1]);
        owner.command("team friendlyfire");
        e2e.eventually(() -> mate.chatContains(ownerName + " turned friendly fire off."), "toggled back off: " + mate.chat());
    }

    static void staff(E2E e2e) {
        String ownerName = e2e.name("Founder2");
        String helperName = e2e.name("Helper");
        String team = e2e.name("Hotel");
        String renamed = e2e.name("India");
        Bot owner = e2e.bot(ownerName);
        Bot helper = e2e.bot(helperName);
        startTeam(e2e, owner, team);

        e2e.step("staff add and remove players");
        staffAdd(e2e, team, helperName);
        e2e.eventually(() -> helper.chatContains("You joined " + team + "."), "the player is told: " + helper.chat());
        List<String> kicked = e2e.consoleOutput("team admin kick " + helperName);
        e2e.expect(kicked.stream().anyMatch(line -> line.contains("Removed " + helperName + " from " + team)), "kick output: " + kicked);
        e2e.eventually(() -> teamOf(e2e, helperName).isEmpty(), "the helper was removed");
        List<String> ownerKick = e2e.consoleOutput("team admin kick " + ownerName);
        e2e.expect(ownerKick.stream().anyMatch(line -> line.contains("owns " + team)), "owners can't be kicked: " + ownerKick);
        List<String> missing = e2e.consoleOutput("team admin add NoSuchTeam1 " + helperName);
        e2e.expect(missing.stream().anyMatch(line -> line.contains("no team called NoSuchTeam1")), "unknown team: " + missing);

        e2e.step("staff transfer ownership");
        staffAdd(e2e, team, helperName);
        List<String> transfer = e2e.consoleOutput("team admin transfer " + team + " " + helperName);
        e2e.expect(transfer.stream().anyMatch(line -> line.contains(helperName + " now owns " + team)), "transfer output: " + transfer);
        e2e.eventually(() -> "owner".equals(placeholder(e2e, helperName, "team_role")), "the helper owns the team");
        e2e.expect("admin".equals(placeholder(e2e, ownerName, "team_role")), "the old owner is an admin");

        e2e.step("staff remove a home and rename a team");
        owner.command("team sethome");
        e2e.eventually(() -> helper.chatContains(ownerName + " set the team home."), "home set by the admin");
        List<String> delhome = e2e.consoleOutput("team admin delhome " + team);
        e2e.expect(delhome.stream().anyMatch(line -> line.contains("Removed the home of " + team)), "delhome output: " + delhome);
        List<String> rename = e2e.consoleOutput("team admin rename " + team + " " + renamed);
        e2e.expect(rename.stream().anyMatch(line -> line.contains("Renamed " + team + " to " + renamed)), "rename output: " + rename);
        e2e.eventually(() -> renamed.equals(teamOf(e2e, ownerName)), "the new name is used");
        List<String> badName = e2e.consoleOutput("team admin rename " + renamed + " x");
        e2e.expect(badName.stream().anyMatch(line -> line.contains("3 to 16 characters")), "invalid names are refused: " + badName);

        e2e.step("info, list and top work from the console");
        List<String> info = e2e.consoleOutput("team info " + renamed);
        e2e.expect(info.stream().anyMatch(line -> line.contains("Team " + renamed)), "info header: " + info);
        e2e.expect(info.stream().anyMatch(line -> line.contains(helperName + " (owner)") && line.contains(ownerName + " (admin)")),
            "members with roles: " + info);
        List<String> list = e2e.consoleOutput("team list");
        e2e.expect(list.stream().anyMatch(line -> line.startsWith("Teams")), "list header: " + list);
        List<String> top = e2e.consoleOutput("team top money");
        e2e.expect(top.stream().anyMatch(line -> line.contains("Top teams by money")), "top header: " + top);

        e2e.step("staff disband a team");
        List<String> disband = e2e.consoleOutput("team admin disband " + renamed);
        e2e.expect(disband.stream().anyMatch(line -> line.contains("Disbanded " + renamed)), "disband output: " + disband);
        e2e.eventually(() -> teamOf(e2e, ownerName).isEmpty() && teamOf(e2e, helperName).isEmpty(), "everyone is out");
        e2e.eventually(() -> owner.chatContains(renamed + " was disbanded by staff."), "members are told: " + owner.chat());
    }

    static void menu(E2E e2e) {
        String name = e2e.name("Wanderer");
        Bot a = e2e.bot(name);
        e2e.step("the main menu has a Team entry");
        a.command("menu");
        Bot.SeenDialog menu = e2e.dialog(a, "SiftVanilla");
        e2e.expect(menu.button("Team") != null, "a Team button: " + menu.buttons());
        e2e.click(a, "Team");
        Bot.SeenDialog teams = e2e.dialog(a, "Team");
        e2e.expect(teams.button("All teams") != null && teams.button("Top teams") != null, "list and top buttons: " + teams.buttons());
        e2e.step("the pause-menu route opens it too");
        a.clearLogs();
        a.rawClick("siftcore:hub/teams", null);
        e2e.dialog(a, "Team");
        e2e.step("top teams and the team list open from the menu");
        e2e.click(a, "Top teams");
        Bot.SeenDialog top = e2e.dialog(a, "Top teams by kills");
        e2e.expect(top.button("By money") != null, "a board switch: " + top.buttons());
        e2e.click(a, "By money");
        e2e.dialog(a, "Top teams by money");
        e2e.click(a, "Back");
        e2e.dialog(a, "Team");
        e2e.click(a, "All teams");
        e2e.dialog(a, "All teams");
    }

    // ------------------------------------------------------------------ player settings

    /**
     * The teams settings change what happens: who can invite (picked in the settings dialog; "friends" asks the friends
     * system), how team news shows (above the hotbar or not at all; disbanding always shows in chat), the team chat
     * sound each member picked, and /team spy through the registry (refused while the server locks it).
     */
    static void settings(E2E e2e) throws Exception {
        String ownerName = e2e.name("TsOwner");
        String mateName = e2e.name("TsMate");
        String guestName = e2e.name("TsGuest");
        String team = e2e.name("Tango");
        Bot owner = e2e.bot(ownerName);
        Bot mate = e2e.bot(mateName);
        Bot guest = e2e.bot(guestName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, mateName);

        e2e.step("Team invites from: nobody, picked in the settings dialog, refuses invites with a reason");
        FriendsScenarios.saveSocial(e2e, guest, Map.of("team_invites", "nobody"));
        e2e.eventually(() -> guest.anyFeedbackContains("Team invites from set to Nobody."), "saved: " + guest.actionBar() + " " + guest.chat());
        e2e.expect("nobody".equals(FriendsScenarios.stored(e2e, e2e.uuid(guestName), "team-invites")), "stored as nobody");
        owner.clearLogs();
        guest.clearLogs();
        owner.command("team invite " + guestName);
        e2e.eventually(() -> owner.actionBarContains(guestName + " isn't taking team invites from you."), "refused: " + owner.actionBar());
        e2e.sleep(800);
        e2e.expect(!guest.chatContains("invited you"), "no invite arrived: " + guest.chat());

        e2e.step("friends only: a stranger is refused, a friend's invite arrives");
        set(e2e, guestName, "team-invites", "friends");
        owner.clearLogs();
        owner.command("team invite " + guestName);
        e2e.eventually(() -> owner.actionBarContains(guestName + " isn't taking team invites from you."), "not friends: " + owner.actionBar());
        List<String> added = e2e.consoleOutput("sift friends add " + ownerName + " " + guestName);
        e2e.expect(added.stream().anyMatch(line -> line.contains("friends")), "made friends: " + added);
        e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(guestName), e2e.uuid(ownerName)), "they are friends");
        guest.clearLogs();
        owner.command("team invite " + guestName);
        e2e.eventually(() -> guest.chatContains(ownerName + " invited you to join " + team), "a friend's invite arrives: " + guest.chat());

        e2e.step("Team news above the hotbar, typed as /settings team-notices actionbar: a teammate sees the new home there");
        FriendsScenarios.setByCommand(e2e, mate, "team-notices", "actionbar", "actionbar");
        mate.clearLogs();
        owner.clearLogs();
        owner.command("team sethome");
        e2e.eventually(() -> mate.actionBarContains(ownerName + " set the team home."), "news above the hotbar: " + mate.actionBar());
        e2e.eventually(() -> owner.chatContains(ownerName + " set the team home."), "the default stays chat: " + owner.chat());
        e2e.expect(!mate.chatContains("set the team home"), "not in chat: " + mate.chat());

        e2e.step("Team news off: a friendly fire change isn't told to that teammate");
        set(e2e, mateName, "team-notices", "off");
        mate.clearLogs();
        owner.clearLogs();
        owner.command("team friendlyfire on");
        e2e.eventually(() -> owner.chatContains(ownerName + " turned friendly fire on."), "the owner is told: " + owner.chat());
        e2e.sleep(800);
        e2e.expect(!mate.chatContains("friendly fire") && !mate.actionBarContains("friendly fire"), "nothing for the teammate: "
            + mate.chat() + " " + mate.actionBar());

        e2e.step("Team news off for the one who acts: their own change is still confirmed, in chat");
        set(e2e, ownerName, "team-notices", "off");
        mate.clearLogs();
        owner.clearLogs();
        owner.command("team sethome");
        e2e.eventually(() -> owner.chatContains(ownerName + " set the team home."), "the owner's confirmation: " + owner.chat());
        owner.clearLogs();
        owner.command("team friendlyfire off");
        e2e.eventually(() -> owner.chatContains(ownerName + " turned friendly fire off."), "and for friendly fire: " + owner.chat());
        e2e.sleep(800);
        e2e.expect(!mate.chatContains("set the team home") && !mate.actionBarContains("set the team home"),
            "the teammate with news off still hears nothing: " + mate.chat() + " " + mate.actionBar());

        e2e.step("becoming owner is always told, even with team news off");
        mate.clearLogs();
        owner.command("team transfer " + mateName);
        e2e.dialog(owner, "Hand over the team");
        e2e.click(owner, "Hand over");
        e2e.eventually(() -> "owner".equals(placeholder(e2e, mateName, "team_role")), mateName + " owns the team");
        e2e.eventually(() -> mate.chatContains(ownerName + " handed the team to " + mateName + "."), "the new owner is told: " + mate.chat());
        e2e.eventually(() -> owner.chatContains(ownerName + " handed the team to " + mateName + "."), "and the old owner: " + owner.chat());
        set(e2e, ownerName, "team-notices", "chat");

        e2e.step("the team chat sound each member picked plays for them, never for the sender");
        set(e2e, mateName, net.siftvanilla.siftcore.core.player.SharedSettings.SOUND_TEAM_CHAT,
            net.siftvanilla.siftcore.core.player.options.PingSound.BELL);
        set(e2e, ownerName, net.siftvanilla.siftcore.core.player.SharedSettings.SOUND_TEAM_CHAT,
            net.siftvanilla.siftcore.core.player.options.PingSound.BELL);
        mate.clearLogs();
        owner.clearLogs();
        owner.command("tc ping");
        e2e.eventually(() -> mate.chatContains("Team " + ownerName + ": ping"), "the message: " + mate.chat());
        e2e.eventually(() -> mate.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bell")), "the bell: " + mate.sounds());
        e2e.sleep(500);
        e2e.expect(owner.sounds().stream().noneMatch(s -> s.sound().endsWith("note_block.bell")), "not for the sender: " + owner.sounds());
        set(e2e, mateName, net.siftvanilla.siftcore.core.player.SharedSettings.SOUND_TEAM_CHAT,
            net.siftvanilla.siftcore.core.player.options.PingSound.OFF);
        mate.clearLogs();
        owner.command("tc quiet");
        e2e.eventually(() -> mate.chatContains("Team " + ownerName + ": quiet"), "the message: " + mate.chat());
        e2e.sleep(500);
        e2e.expect(mate.sounds().stream().noneMatch(s -> s.sound().endsWith("note_block.bell")), "off is silent: " + mate.sounds());

        e2e.step("/team spy flips the staff setting, and is refused while the server locks it");
        e2e.console("op " + guestName);
        try {
            guest.clearLogs();
            guest.command("team spy");
            e2e.eventually(() -> guest.actionBarContains("Team chat spy off."), "spy off: " + guest.actionBar());
            guest.command("team spy");
            e2e.eventually(() -> guest.actionBarContains("Team chat spy on."), "spy on: " + guest.actionBar());
            FriendsScenarios.withOverrides(e2e, new net.siftvanilla.siftcore.core.player.Overrides(Map.of(), Map.of("team-spy", "true"),
                java.util.Set.of()), () -> {
                    guest.clearLogs();
                    guest.command("team spy");
                    e2e.eventually(() -> guest.actionBarContains("The server sets team chat spy for every staff member."),
                        "locked: " + guest.actionBar());
                });
        } finally {
            e2e.console("deop " + guestName);
        }

        e2e.step("disbanding always shows in chat, whatever the team news setting");
        mate.clearLogs();
        List<String> disband = e2e.consoleOutput("team admin disband " + team);
        e2e.expect(disband.stream().anyMatch(line -> line.contains("Disbanded " + team)), "disbanded: " + disband);
        e2e.eventually(() -> mate.chatContains(team + " was disbanded by staff."), "told in chat with news off: " + mate.chat());
    }

    /**
     * Teammate login alerts ({@code team-member-alerts}, with test timings in teams.yml): logins by default, nothing
     * when off, logouts too when picked, no double line for friends the friends alert tells; and team chat mode
     * coming back after a relog for players who keep it ({@code team-chat-sticky}).
     */
    static void loginAlerts(E2E e2e) throws Exception {
        withConfig(e2e, Map.of("join-delay", "1s", "leave-delay", "2s", "relog-grace", "0s", "startup-quiet", "0s"), x -> {
            String ownerName = e2e.name("TlOwner");
            String mateName = e2e.name("TlMate");
            String goerName = e2e.name("TlGoer");
            String team = e2e.name("Lima");
            Bot owner = e2e.bot(ownerName);
            Bot mate = e2e.bot(mateName);
            Bot goer = e2e.bot(goerName);
            startTeam(e2e, owner, team);
            staffAdd(e2e, team, mateName);
            staffAdd(e2e, team, goerName);

            e2e.step("a teammate's login is told by default");
            quit(e2e, goer);
            owner.clearLogs();
            mate.clearLogs();
            Bot back = e2e.bot(goerName);
            e2e.eventually(() -> owner.chatContains(goerName + " from your team is online."), "login alert: " + owner.chat());
            e2e.eventually(() -> mate.chatContains(goerName + " from your team is online."), "for every teammate: " + mate.chat());
            e2e.expect(!back.chatContains("from your team is online"), "not for the player themselves: " + back.chat());

            e2e.step("off: no login alert; logins and logouts: the logout is told after the leave delay");
            set(e2e, mateName, "team-member-alerts", "off");
            set(e2e, ownerName, "team-member-alerts", "joins-and-leaves");
            owner.clearLogs();
            mate.clearLogs();
            quit(e2e, back);
            e2e.eventually(() -> owner.chatContains(goerName + " from your team went offline."), 8_000, "logout alert: " + owner.chat());
            Bot again = e2e.bot(goerName);
            e2e.eventually(() -> owner.chatContains(goerName + " from your team is online."), "login alert again: " + owner.chat());
            e2e.sleep(2_000);
            e2e.expect(!mate.chatContains(goerName + " from your team"), "nothing with alerts off: " + mate.chat());

            e2e.step("a friend who gets the friends login alert isn't told twice");
            List<String> added = e2e.consoleOutput("sift friends add " + ownerName + " " + goerName);
            e2e.expect(added.stream().anyMatch(line -> line.contains("friends")), "made friends: " + added);
            e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(ownerName), e2e.uuid(goerName)), "friends");
            quit(e2e, again);
            e2e.sleep(3_000);
            owner.clearLogs();
            Bot third = e2e.bot(goerName);
            e2e.sleep(4_000);
            e2e.expect(!owner.chatContains(goerName + " from your team is online"), "no team line for a friend: " + owner.chat());

            e2e.step("Remember team chat mode: team chat comes back after a relog, and only while it is on");
            set(e2e, goerName, "team-chat-sticky", "true");
            third.clearLogs();
            third.command("team chat");
            e2e.eventually(() -> third.actionBarContains("Team chat on"), "team chat on: " + third.actionBar());
            quit(e2e, third);
            Bot sticky = e2e.bot(goerName);
            e2e.eventually(() -> sticky.chatContains("Team chat is still on."), "told at login: " + sticky.chat());
            mate.clearLogs();
            sticky.chat("still team");
            e2e.eventually(() -> mate.chatContains("Team " + goerName + ": still team"), "typed chat goes to the team: " + mate.chat());
            set(e2e, goerName, "team-chat-sticky", "false");
            quit(e2e, sticky);
            Bot plain = e2e.bot(goerName);
            e2e.sleep(1_500);
            e2e.expect(!plain.chatContains("Team chat is still on."), "not kept with the setting off: " + plain.chat());
            owner.clearLogs();
            plain.chat("public again");
            e2e.eventually(() -> owner.chatContains("public again") && !owner.chatContains("Team " + goerName + ": public again"),
                "typed chat is public: " + owner.chat());

            e2e.step("team chat on while the setting is off, a whole session in public chat, then the setting on: it stays off");
            plain.clearLogs();
            plain.command("team chat");
            e2e.eventually(() -> plain.actionBarContains("Team chat on"), "team chat on: " + plain.actionBar());
            quit(e2e, plain);
            Bot publicSession = e2e.bot(goerName);
            e2e.sleep(1_500);
            e2e.expect(!publicSession.chatContains("Team chat is still on."), "not kept with the setting off: " + publicSession.chat());
            set(e2e, goerName, "team-chat-sticky", "true");
            quit(e2e, publicSession);
            Bot later = e2e.bot(goerName);
            e2e.sleep(1_500);
            e2e.expect(!later.chatContains("Team chat is still on."), "a mode from an older session never comes back: " + later.chat());
            owner.clearLogs();
            later.chat("still public");
            e2e.eventually(() -> owner.chatContains("still public") && !owner.chatContains("Team " + goerName + ": still public"),
                "typed chat is public: " + owner.chat());
        });
    }

    /**
     * Who sees when a member was last online ({@code seen-privacy}) also holds in the team member lists: a stranger's
     * {@code /team info} and a teammate's {@code /team} show "offline" without the time for a member who keeps it to
     * nobody (or to friends, for someone who isn't one); staff who may look players up see it.
     */
    static void seenPrivacy(E2E e2e) throws Exception {
        String ownerName = e2e.name("TpOwner");
        String quietName = e2e.name("TpQuiet");
        String openName = e2e.name("TpOpen");
        String strangerName = e2e.name("TpStranger");
        String team = e2e.name("Papa");
        Bot owner = e2e.bot(ownerName);
        Bot quiet = e2e.bot(quietName);
        Bot open = e2e.bot(openName);
        Bot stranger = e2e.bot(strangerName);
        startTeam(e2e, owner, team);
        staffAdd(e2e, team, quietName);
        staffAdd(e2e, team, openName);

        e2e.step("one member keeps their last-seen time to nobody, the other keeps the default; both log off");
        java.util.UUID quietId = e2e.uuid(quietName);
        java.util.UUID openId = e2e.uuid(openName);
        set(e2e, quietName, net.siftvanilla.siftcore.core.player.SharedSettings.SEEN_PRIVACY,
            net.siftvanilla.siftcore.core.player.options.Audience.NOBODY);
        quit(e2e, quiet);
        quit(e2e, open);
        e2e.eventually(() -> "nobody".equals(FriendsScenarios.stored(e2e, quietId, "seen-privacy")), "stored");

        e2e.step("a stranger's /team info shows the hidden member as offline, without the time");
        Bot.SeenDialog info = infoOf(e2e, stranger, team);
        e2e.expect(info.bodyText().contains(quietName + " member, offline"), "offline without a time: " + info.body());
        e2e.expect(!info.bodyText().contains(quietName + " member, seen"), "no last-seen time: " + info.body());
        e2e.expect(info.bodyText().contains(openName + " member, seen"), "the default shows the time: " + info.body());

        e2e.step("a teammate's /team shows the same");
        Bot.SeenDialog roster = openTeamMenu(e2e, owner, "Team " + team);
        e2e.expect(roster.bodyText().contains(quietName + " member, offline") && roster.bodyText().contains(openName + " member, seen"),
            "the roster: " + roster.body());

        e2e.step("friends only: hidden from the stranger until they are friends");
        var friendsOnlyResult = e2e.services().settings().setParsed(openId, "seen-privacy", "friends",
            net.siftvanilla.siftcore.core.player.Change.api("e2e"));
        e2e.expect(friendsOnlyResult.succeeded(), "set for the offline member: " + friendsOnlyResult);
        Bot.SeenDialog friendsOnly = infoOf(e2e, stranger, team);
        e2e.expect(friendsOnly.bodyText().contains(openName + " member, offline"), "not a friend: " + friendsOnly.body());
        List<String> added = e2e.consoleOutput("sift friends add " + strangerName + " " + openName);
        e2e.expect(added.stream().anyMatch(line -> line.contains("friends")), "made friends: " + added);
        e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(strangerName), openId), "friends");
        Bot.SeenDialog asFriend = infoOf(e2e, stranger, team);
        e2e.expect(asFriend.bodyText().contains(openName + " member, seen"), "a friend sees it: " + asFriend.body());

        e2e.step("staff who may look players up always see it");
        e2e.console("op " + strangerName);
        try {
            Bot.SeenDialog asStaff = infoOf(e2e, stranger, team);
            e2e.expect(asStaff.bodyText().contains(quietName + " member, seen"), "staff see the time: " + asStaff.body());
        } finally {
            e2e.console("deop " + strangerName);
        }
    }

    /** Opens /team info for a team and waits for that fresh dialog. */
    private static Bot.SeenDialog infoOf(E2E e2e, Bot bot, String team) {
        bot.clearLogs();
        bot.command("team info " + team);
        return e2e.dialog(bot, "Team " + team);
    }
}
