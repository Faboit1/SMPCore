package net.siftvanilla.siftcore.feature.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The permission and placeholder reference pages ({@code docs/permissions.md}, {@code docs/placeholders.md}),
 * generated from the live registries so they always match the running plugin. Pure.
 */
final class RegistryDocs {

    /** A permission node as the docs show it. */
    record Node(String name, String description, String defaultValue) {
    }

    private RegistryDocs() {
    }

    /** Who has a node by default, in words ({@code TRUE} → everyone, {@code OP} → operators). */
    static String who(String permissionDefault) {
        return switch (permissionDefault.toUpperCase(Locale.ROOT)) {
            case "TRUE" -> "everyone";
            case "OP" -> "operators";
            case "NOT_OP" -> "everyone but operators";
            default -> "nobody";
        };
    }

    static String permissions(List<Node> nodes, String version) {
        Map<String, List<Node>> groups = new TreeMap<>();
        for (Node node : nodes) {
            groups.computeIfAbsent(area(node.name()), k -> new ArrayList<>()).add(node);
        }
        StringBuilder md = new StringBuilder();
        md.append("# Permissions\n\n");
        md.append("Every permission node SiftCore ").append(version).append(" declares (").append(nodes.size())
            .append(" nodes), generated with `/sift docs`. Nodes are registered with the server at startup, so LuckPerms ")
            .append("suggests them. \"Everyone\" nodes are granted by default; take them away with a negated node ")
            .append("(`/lp group default permission set <node> false`).\n\n");
        md.append("Rank limits (homes, auction listings, team size and similar) are numeric nodes such as ")
            .append("`siftcore.homes.5`: the highest number a player has wins. They are described with the feature that ")
            .append("reads them in `docs/features/`.\n");
        for (Map.Entry<String, List<Node>> group : groups.entrySet()) {
            md.append("\n## ").append(group.getKey()).append("\n\n");
            md.append("| Node | Default | Description |\n|---|---|---|\n");
            List<Node> sorted = new ArrayList<>(group.getValue());
            sorted.sort((a, b) -> a.name().compareTo(b.name()));
            for (Node node : sorted) {
                md.append("| `").append(node.name()).append("` | ").append(who(node.defaultValue())).append(" | ")
                    .append(cell(node.description())).append(" |\n");
            }
        }
        return md.toString();
    }

    static String placeholders(Map<String, String> docs, String version) {
        StringBuilder md = new StringBuilder();
        md.append("# Placeholders\n\n");
        md.append("Every placeholder SiftCore ").append(version).append(" provides (").append(docs.size())
            .append("), generated with `/sift docs`. With PlaceholderAPI installed they are `%siftcore_<name>%`; ")
            .append("plugins can also read them through the API (`SiftCoreApi#placeholders`). A name ending in `<...>` ")
            .append("takes the rest of the placeholder as an argument, for example `%siftcore_baltop_name_1%`.\n\n");
        md.append("| Placeholder | Shows |\n|---|---|\n");
        for (Map.Entry<String, String> entry : new TreeMap<>(docs).entrySet()) {
            md.append("| `%siftcore_").append(entry.getKey()).append("%` | ").append(cell(entry.getValue())).append(" |\n");
        }
        return md.toString();
    }

    /** The section a node belongs to: commands, staff tools, or the feature named after {@code siftcore.}. */
    static String area(String node) {
        String[] parts = node.split("\\.");
        if (parts.length < 2) {
            return "Other";
        }
        return switch (parts[1]) {
            case "command" -> "Commands";
            case "admin" -> "Staff and admin";
            case "bypass" -> "Bypasses";
            default -> parts[1].substring(0, 1).toUpperCase(Locale.ROOT) + parts[1].substring(1).replace('-', ' ');
        };
    }

    /** Text safe inside a table cell: pipes escaped, angle brackets as entities so usages like {@code <player>} show. */
    private static String cell(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|")
            .replace("\n", " ");
    }
}
