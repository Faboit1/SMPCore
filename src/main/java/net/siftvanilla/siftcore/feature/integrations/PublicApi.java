package net.siftvanilla.siftcore.feature.integrations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.api.CombatView;
import net.siftvanilla.siftcore.api.PlaceholderView;
import net.siftvanilla.siftcore.api.RankView;
import net.siftvanilla.siftcore.api.SiftCoreApi;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import org.bukkit.OfflinePlayer;

/** The {@link SiftCoreApi} registered in the services manager: thin read-only views over SiftCore's services. */
final class PublicApi implements SiftCoreApi {

    private static final Pattern PLACEHOLDER = Pattern.compile("%siftcore_([A-Za-z0-9_.\\-]+)%");

    private final String version;
    private final EconomyApi economy;
    private final CombatView combat;
    private final PlaceholderView placeholders;
    private final RankView ranks;

    PublicApi(String version, EconomyApi economy, CombatTags tags, Placeholders registry, Ranks ranks) {
        this.version = version;
        this.economy = economy;
        this.combat = new CombatView() {
            @Override
            public boolean tagged(UUID player) {
                return tags.tagged(player);
            }

            @Override
            public Duration remaining(UUID player) {
                return tags.remaining(player);
            }

            @Override
            public Optional<UUID> lastAttacker(UUID player) {
                CombatTags.Tag tag = tags.get(player);
                return tag == null ? Optional.empty() : Optional.ofNullable(tag.lastAttacker());
            }
        };
        this.placeholders = new PlaceholderView() {
            @Override
            public Optional<String> resolve(OfflinePlayer player, String name) {
                if (name == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.ofNullable(registry.resolve(player, name));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }

            @Override
            public String apply(OfflinePlayer player, String text) {
                if (text == null || text.indexOf('%') < 0) {
                    return text;
                }
                Matcher matcher = PLACEHOLDER.matcher(text);
                StringBuilder out = new StringBuilder(text.length());
                while (matcher.find()) {
                    String value = resolve(player, matcher.group(1)).orElse(matcher.group());
                    matcher.appendReplacement(out, Matcher.quoteReplacement(value));
                }
                matcher.appendTail(out);
                return out.toString();
            }

            @Override
            public Map<String, String> available() {
                return registry.documentation();
            }
        };
        this.ranks = new RankView() {
            @Override
            public String label(UUID player) {
                return ranks.label(player);
            }

            @Override
            public String group(UUID player) {
                return ranks.group(player);
            }
        };
    }

    @Override
    public String version() {
        return this.version;
    }

    @Override
    public EconomyApi economy() {
        return this.economy;
    }

    @Override
    public CombatView combat() {
        return this.combat;
    }

    @Override
    public PlaceholderView placeholders() {
        return this.placeholders;
    }

    @Override
    public RankView ranks() {
        return this.ranks;
    }
}
