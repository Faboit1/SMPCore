package net.siftvanilla.siftcore;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point. All work happens in {@link SiftCore}. */
public final class SiftCorePlugin extends JavaPlugin {

    private SiftCore core;

    @Override
    public void onEnable() {
        this.core = new SiftCore(this);
        try {
            this.core.start();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "SiftCore could not start and is disabling itself so nothing runs half-working", t);
            this.core.stop();
            this.core = null;
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (this.core != null) {
            this.core.stop();
            this.core = null;
        }
    }

    /** The running core, or null when disabled. */
    public SiftCore core() {
        return this.core;
    }
}
