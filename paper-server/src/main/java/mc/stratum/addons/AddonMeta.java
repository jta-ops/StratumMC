package mc.stratum.addons;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Metadata for a Stratum addon, read from {@code META-INF/addon.json} inside
 * the addon's JAR file.
 *
 * <p>Example {@code addon.json}:
 * <pre>{@code
 * {
 *   "id": "example-addon",
 *   "name": "Example Addon",
 *   "description": "Does something cool.",
 *   "version": "1.0.0",
 *   "author": "StratumDev",
 *   "minStratumVersion": "Stratum1.0",
 *   "maxStratumVersion": null,
 *   "conflicts": [],
 *   "requiresTier2Key": false
 * }
 * }</pre>
 */
public final class AddonMeta {

    private static final Gson GSON = new Gson();

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("version")
    private String version;

    @SerializedName("author")
    private String author;

    @SerializedName("minStratumVersion")
    private String minStratumVersion;

    @SerializedName("maxStratumVersion")
    private String maxStratumVersion;   // nullable

    @SerializedName("conflicts")
    private List<String> conflicts;

    @SerializedName("requiresTier2Key")
    private boolean requiresTier2Key;

    @SerializedName("tags")
    private List<String> tags;

    // Private — use fromJson()
    private AddonMeta() {}

    /** Deserialises {@link AddonMeta} from raw JSON. */
    public static AddonMeta fromJson(final String json) {
        return GSON.fromJson(json, AddonMeta.class);
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String id()                 { return id; }
    public String name()               { return name; }
    public String description()        { return description; }
    public String version()            { return version; }
    public String author()             { return author; }
    public String minStratumVersion()  { return minStratumVersion; }
    public String maxStratumVersion()  { return maxStratumVersion; }
    public List<String> conflicts()    { return conflicts != null ? conflicts : List.of(); }
    public boolean requiresTier2Key()  { return requiresTier2Key; }
    public List<String> tags()         { return tags != null ? tags : List.of(); }

    @Override
    public String toString() {
        return id + " v" + version + " by " + author;
    }
}
