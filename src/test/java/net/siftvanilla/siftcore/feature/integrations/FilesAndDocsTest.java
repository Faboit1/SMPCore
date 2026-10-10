package net.siftvanilla.siftcore.feature.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import org.junit.jupiter.api.Test;

/** Backup and export file names, CSV escaping and the generated reference docs. */
class FilesAndDocsTest {

    private static final Instant TIME = Instant.parse("2026-10-08T14:30:05Z");

    @Test
    void backupNamesAreSortableAndNeverTaken() {
        assertEquals("siftcore-2026-10-08_14-30-05.db", FileNames.backup(TIME, Set.of()));
        assertEquals("siftcore-2026-10-08_14-30-05-2.db", FileNames.backup(TIME, Set.of("siftcore-2026-10-08_14-30-05.db")));
        assertEquals("siftcore-2026-10-08_14-30-05-3.db", FileNames.backup(TIME,
            Set.of("siftcore-2026-10-08_14-30-05.db", "siftcore-2026-10-08_14-30-05-2.db")));
        assertTrue(FileNames.isBackup("siftcore-2026-10-08_14-30-05-12.db"));
        assertFalse(FileNames.isBackup("siftcore.db"));
        assertFalse(FileNames.isBackup("siftcore-2026-10-08_14-30-05.db-journal"));
        assertEquals("ledger-2026-10-08_14-30-05.csv", FileNames.export("ledger", TIME));
    }

    @Test
    void pruningKeepsTheNewestBackupsAndNothingElse() {
        List<String> names = List.of(
            "siftcore-2026-10-06_10-00-00.db",
            "siftcore-2026-10-08_14-30-05.db",
            "siftcore-2026-10-08_14-30-05-2.db",
            "siftcore-2026-10-08_14-30-05-10.db",
            "siftcore-2026-10-07_10-00-00.db",
            "notes.txt");
        assertEquals(List.of("siftcore-2026-10-07_10-00-00.db", "siftcore-2026-10-06_10-00-00.db"), FileNames.prune(names, 3),
            "the three newest stay, same-second backups ordered by their counter");
        assertEquals(List.of(), FileNames.prune(names, 0), "0 keeps every backup");
        assertEquals(List.of(), FileNames.prune(names, 10));
    }

    @Test
    void sizesReadNaturally() {
        assertEquals("812 B", FileNames.size(812));
        assertEquals("1.0 KB", FileNames.size(1024));
        assertEquals("14.2 MB", FileNames.size(14_890_000));
        assertEquals("2.0 GB", FileNames.size(2L * 1024 * 1024 * 1024));
    }

    @Test
    void csvQuotesWhatNeedsQuotingAndDefusesFormulas() throws Exception {
        StringWriter out = new StringWriter();
        Csv.line(out, "plain", "a,b", "say \"hi\"", "two\nlines", null, -500L, "=HYPERLINK(\"x\")", "-12", "@cmd");
        assertEquals("plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",,-500,\"'=HYPERLINK(\"\"x\"\")\",-12,'@cmd\r\n", out.toString());
    }

    @Test
    void exportNamesCoverPlayersAndSystemAccounts() {
        UUID player = UUID.randomUUID();
        Map<UUID, String> names = Map.of(player, "Alex");
        assertEquals("Alex", ExportService.name(player.toString(), names));
        assertEquals("orders escrow", ExportService.name(SystemAccounts.ORDERS_ESCROW.toString(), names));
        assertEquals("bounty escrow", ExportService.name(SystemAccounts.BOUNTY_ESCROW.toString(), names));
        assertEquals("", ExportService.name(UUID.randomUUID().toString(), names));
        assertEquals("", ExportService.name("console", names));
    }

    @Test
    void permissionDocsGroupNodesByArea() {
        String md = RegistryDocs.permissions(List.of(
            new RegistryDocs.Node("siftcore.command.pay", "Use /pay", "TRUE"),
            new RegistryDocs.Node("siftcore.admin.store", "Deliver | store purchases", "OP"),
            new RegistryDocs.Node("siftcore.homes.unlimited", "No home limit", "FALSE"),
            new RegistryDocs.Node("siftcore.bypass.cooldown", "Skip cooldowns", "OP"),
            new RegistryDocs.Node("siftcore.bounties.place", "Use /bounty <player> <amount>", "TRUE")), "1.0.0");
        assertTrue(md.startsWith("# Permissions\n"));
        assertTrue(md.contains("SiftCore 1.0.0 declares (5 nodes)"));
        assertTrue(md.contains("Use /bounty &lt;player&gt; &lt;amount&gt;"), "usages show in rendered markdown");
        assertTrue(md.contains("## Commands\n\n| Node | Default | Description |\n|---|---|---|\n| `siftcore.command.pay` | everyone | Use /pay |"));
        assertTrue(md.contains("| `siftcore.admin.store` | operators | Deliver \\| store purchases |"), "pipes are escaped");
        assertTrue(md.contains("## Homes\n"));
        assertTrue(md.contains("| `siftcore.homes.unlimited` | nobody | No home limit |"));
        assertTrue(md.indexOf("## Bypasses") < md.indexOf("## Commands"), "areas are sorted");
    }

    @Test
    void placeholderDocsListEveryPlaceholderSorted() {
        String md = RegistryDocs.placeholders(Map.of("balance", "Your money", "baltop_name_<rank>", "Name at a place", "rank", "Your rank"), "1.0.0");
        assertTrue(md.contains("provides (3)"));
        int balance = md.indexOf("| `%siftcore_balance%` | Your money |");
        int top = md.indexOf("| `%siftcore_baltop_name_<rank>%` | Name at a place |");
        int rank = md.indexOf("| `%siftcore_rank%` | Your rank |");
        assertTrue(balance > 0 && top > balance && rank > top, md);
    }

    @Test
    void defaultsReadAsWords() {
        assertEquals("everyone", RegistryDocs.who("TRUE"));
        assertEquals("operators", RegistryDocs.who("OP"));
        assertEquals("everyone but operators", RegistryDocs.who("NOT_OP"));
        assertEquals("nobody", RegistryDocs.who("FALSE"));
        assertEquals("Staff and admin", RegistryDocs.area("siftcore.admin.eco"));
        assertEquals("Other", RegistryDocs.area("siftcore"));
    }
}
