package net.siftvanilla.siftcore.integration.vault;

import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Finds the plugin that called the legacy Vault interface (it does not say who is calling) by walking the stack to
 * the first class owned by another plugin. The answer per class is cached, so a call costs a short stack walk.
 */
final class Callers {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final int DEPTH = 48;

    private static final ClassValue<String> OWNERS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                return JavaPlugin.getProvidingPlugin(type).getName();
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
                return "";
            }
        }
    };

    private final Set<String> skip;

    /** @param skip plugins that are never the caller (SiftCore itself and Vault) */
    Callers(Set<String> skip) {
        this.skip = Set.copyOf(skip);
    }

    /** The calling plugin's name, or null when no other plugin is on the stack (a server command, for example). */
    String plugin() {
        return WALKER.walk(frames -> frames.limit(DEPTH)
            .map(frame -> OWNERS.get(frame.getDeclaringClass()))
            .filter(name -> !name.isEmpty() && !this.skip.contains(name))
            .findFirst()
            .orElse(null));
    }
}
