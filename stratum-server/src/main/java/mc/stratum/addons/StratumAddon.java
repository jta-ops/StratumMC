package mc.stratum.addons;

/**
 * Optional interface that addon JARs may implement on their main class.
 *
 * <p>Stratum's {@link AddonLoader} will invoke {@link #onEnable()} and
 * {@link #onDisable()} when the addon is activated or deactivated at runtime.
 *
 * <p>Addons are not required to implement this interface; it is only needed
 * when lifecycle callbacks are desired.
 *
 * <p>Example addon main class:
 * <pre>{@code
 * public class MyAddon implements StratumAddon {
 *     private final AddonMeta meta;
 *
 *     public MyAddon(AddonMeta meta) { this.meta = meta; }
 *
 *     @Override public void onEnable()  { /* startup logic *\/ }
 *     @Override public void onDisable() { /* cleanup logic *\/ }
 *     @Override public AddonMeta getMeta() { return meta; }
 * }
 * }</pre>
 */
public interface StratumAddon {

    /**
     * Called when the addon is enabled (either on startup or via
     * {@code /ST addons enable <id>}).
     */
    void onEnable();

    /**
     * Called when the addon is disabled (via {@code /ST addons disable <id>}
     * or on server shutdown).
     */
    void onDisable();

    /**
     * Returns the addon's metadata, as read from {@code META-INF/addon.json}.
     *
     * @return the addon's {@link AddonMeta}
     */
    AddonMeta getMeta();
}
