package mc.stratum.tweaks;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * JSON model for the manifest returned by the Stratum tweaks API endpoint
 * {@code GET <apiUrl>/manifest}.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "version": "1",
 *   "tweaks": [
 *     {
 *       "id": "core-optimisations",
 *       "name": "Core Optimisations",
 *       "stratumVersion": "Stratum1.3",
 *       "downloadUrl": "https://cdn.stratum.mc/tweaks/core-optimisations-1.3.jar",
 *       "signature": "<base64 Ed25519 sig>"
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class TweakManifest {

    private static final Gson GSON = new Gson();

    @SerializedName("version")
    private String version;

    @SerializedName("tweaks")
    private List<TweakEntry> tweaks;

    // Private — use fromJson()
    private TweakManifest() {}

    /** Deserialises a manifest from raw JSON text. */
    public static TweakManifest fromJson(String json) {
        return GSON.fromJson(json, TweakManifest.class);
    }

    public String version() { return version; }
    public List<TweakEntry> tweaks() { return tweaks != null ? tweaks : List.of(); }

    // ── Nested record ──────────────────────────────────────────────────────────

    /**
     * A single entry in the tweaks manifest.
     *
     * @param id              unique identifier (e.g. {@code "core-optimisations"})
     * @param name            human-readable name
     * @param stratumVersion  the Stratum version label (e.g. {@code "Stratum1.3"})
     * @param downloadUrl     HTTPS URL to the tweak JAR
     * @param signature       base64-encoded Ed25519 signature of the JAR bytes
     */
    public record TweakEntry(
            @SerializedName("id")              String id,
            @SerializedName("name")            String name,
            @SerializedName("stratumVersion")  String stratumVersion,
            @SerializedName("downloadUrl")     String downloadUrl,
            @SerializedName("signature")       String signature
    ) {}
}
