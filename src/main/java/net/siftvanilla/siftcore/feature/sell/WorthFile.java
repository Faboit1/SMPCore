package net.siftvanilla.siftcore.feature.sell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The generated worth table as a YAML file for admins to read ({@code data/worth-generated.yml}). It is rewritten
 * at every start and successful {@code /sift reload}; nothing reads it back. Rendering is pure; writing replaces the
 * file atomically so a reader never sees half of it.
 */
final class WorthFile {

    static final String NAME = "data/worth-generated.yml";

    private WorthFile() {
    }

    /** The file's text. */
    static String render(SellSettings settings, Instant generated) {
        WorthTable table = settings.table();
        StringBuilder out = new StringBuilder(64 * (table.size() + 16));
        out.append("# The worth table SiftCore generated from features/sell.yml and this server's recipes.\n");
        out.append("# It is rewritten at every start and /sift reload, and nothing reads it back: to change a price,\n");
        out.append("# edit base-prices or overrides in features/sell.yml and run /sift reload.\n");
        out.append("#\n");
        out.append("# generated: ").append(generated.truncatedTo(ChronoUnit.SECONDS)).append('\n');
        out.append("# craft-loss: ").append(Multipliers.format(settings.craftLoss())).append('\n');
        out.append("# recipes used: ").append(settings.recipes().size()).append('\n');
        out.append("# sellable items: ").append(table.size())
            .append(" (").append(table.count(WorthTable.Origin.BASE)).append(" base, ")
            .append(table.count(WorthTable.Origin.DERIVED)).append(" derived, ")
            .append(table.count(WorthTable.Origin.OVERRIDE)).append(" overrides)\n");
        out.append("# best sell multiplier: ").append(Multipliers.format(settings.highestMultiplier())).append('\n');
        out.append('\n');
        out.append("# Price of one plain item in whole dollars, and where it came from.\n");
        out.append("prices:\n");
        for (Map.Entry<String, WorthTable.Entry> entry : table.entries().entrySet()) {
            WorthTable.Entry value = entry.getValue();
            out.append("  ").append(quote(entry.getKey())).append(": ").append(value.price()).append("  # ");
            switch (value.origin()) {
                case BASE -> out.append("base price");
                case OVERRIDE -> out.append("override");
                case DERIVED -> out.append("from recipe ").append(value.recipe());
            }
            out.append('\n');
        }
        Map<String, Double> belowOne = table.belowOne();
        out.append('\n');
        out.append("# Made from priced items but worth less than $1 each, so they can't be sold (exact value shown).\n");
        out.append(belowOne.isEmpty() ? "below-one-dollar: {}\n" : "below-one-dollar:\n");
        for (Map.Entry<String, Double> entry : belowOne.entrySet()) {
            out.append("  ").append(quote(entry.getKey())).append(": ")
                .append(String.format(Locale.ROOT, "%.4f", entry.getValue())).append('\n');
        }
        Set<String> off = table.unsellable();
        out.append('\n');
        out.append("# Turned off with an override of 0.\n");
        out.append(off.isEmpty() ? "turned-off: []\n" : "turned-off:\n");
        for (String item : off) {
            out.append("  - ").append(quote(item)).append('\n');
        }
        return out.toString();
    }

    private static String quote(String key) {
        return '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Replaces the file with {@code text}. Blocking I/O: call off the world threads. */
    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
