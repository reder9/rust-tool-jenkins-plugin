package io.jenkins.plugins.rust;

import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads, dynamically fetches, and caches the Rust toolchain, sccache, and cargo-binstall version catalog.
 * Supports air-gapped instances via local filesystem override and fallback to the bundled catalog.
 */
public final class RustVersionCatalog implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(RustVersionCatalog.class.getName());

    /**
     * Self-updating catalog published by the scheduled "Update Rust Catalog" workflow to the
     * {@code catalog} branch, so new Rust, sccache, and cargo-binstall versions appear without a plugin release.
     */
    public static final String DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/jenkinsci/rust-tool-plugin/catalog/versions.json";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static final List<String> DEFAULT_CHANNELS =
            Collections.unmodifiableList(Arrays.asList("stable", "beta", "nightly"));

    private static volatile RustVersionCatalog instance;

    private final List<String> channels = new ArrayList<>(DEFAULT_CHANNELS);
    private final Map<String, RustRelease> releases = new LinkedHashMap<>();
    private final List<String> sccacheVersions = new ArrayList<>();
    private final List<String> binstallVersions = new ArrayList<>();

    public static class PlatformDownload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String url;
        private final String filename;

        public PlatformDownload(String url, String filename) {
            this.url = url;
            this.filename = filename;
        }

        public String getUrl() {
            return url;
        }

        public String getFilename() {
            return filename;
        }
    }

    public static class RustRelease implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String version;
        private final String releaseDate;
        private final Map<String, PlatformDownload> platforms = new LinkedHashMap<>();

        public RustRelease(String version, String releaseDate) {
            this.version = version;
            this.releaseDate = releaseDate;
        }

        public String getVersion() {
            return version;
        }

        public String getReleaseDate() {
            return releaseDate;
        }

        public Map<String, PlatformDownload> getPlatforms() {
            return Collections.unmodifiableMap(platforms);
        }

        public PlatformDownload getPlatform(String targetTriple) {
            return platforms.get(targetTriple);
        }

        public void addPlatform(String targetTriple, PlatformDownload download) {
            this.platforms.put(targetTriple, download);
        }
    }

    public static RustVersionCatalog getInstance() {
        if (instance == null) {
            synchronized (RustVersionCatalog.class) {
                if (instance == null) {
                    instance = new RustVersionCatalog();
                }
            }
        }
        return instance;
    }

    private RustVersionCatalog() {
        loadCatalog();
    }

    /**
     * Reloads the version catalog from local override, dynamic cache, or bundled resources.
     */
    public synchronized void reload() {
        channels.clear();
        channels.addAll(DEFAULT_CHANNELS);
        releases.clear();
        sccacheVersions.clear();
        binstallVersions.clear();
        loadCatalog();
    }

    private void loadCatalog() {
        // 1. Check for explicit local file override (system property or $JENKINS_HOME/rust-versions.json)
        String overridePath = System.getProperty("io.jenkins.plugins.rust.versionsFile");
        if (overridePath != null && !overridePath.trim().isEmpty()) {
            File customFile = new File(overridePath.trim());
            if (customFile.isFile() && loadFromFile(customFile)) {
                LOGGER.log(Level.INFO, "[Rust Plugin] Loaded Rust version catalog from configured override: " + customFile.getAbsolutePath());
                return;
            }
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            File rootFile = new File(jenkins.getRootDir(), "rust-versions.json");
            if (rootFile.isFile() && loadFromFile(rootFile)) {
                LOGGER.log(Level.INFO, "[Rust Plugin] Loaded Rust version catalog from JENKINS_HOME override: " + rootFile.getAbsolutePath());
                return;
            }

            // 2. Check dynamic cache or fetch remote updates if enabled
            File cacheDir = new File(new File(jenkins.getRootDir(), "caches"), "rust-tool");
            File cachedFile = new File(cacheDir, "versions.json");

            boolean dynamicFetchEnabled = !"false".equalsIgnoreCase(System.getProperty("io.jenkins.plugins.rust.dynamicFetch"));
            if (dynamicFetchEnabled) {
                boolean needsRefresh = !cachedFile.exists() || (System.currentTimeMillis() - cachedFile.lastModified() > CACHE_TTL_MS);
                if (needsRefresh) {
                    fetchRemoteCatalog(cachedFile);
                }
            }

            if (cachedFile.isFile() && loadFromFile(cachedFile)) {
                LOGGER.log(Level.FINE, "[Rust Plugin] Loaded Rust version catalog from local disk cache: " + cachedFile.getAbsolutePath());
                return;
            }
        }

        // 3. Fallback: load bundled classpath versions.json
        loadFromClasspath();
    }

    private void fetchRemoteCatalog(File cacheDestination) {
        String remoteUrl = System.getProperty("io.jenkins.plugins.rust.versionsUrl", DEFAULT_REMOTE_URL);
        try {
            LOGGER.log(Level.INFO, "[Rust Plugin] Refreshing Rust versions catalog from: " + remoteUrl);
            URL url = URI.create(remoteUrl).toURL();
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);

            try (InputStream in = conn.getInputStream()) {
                String content = IOUtils.toString(in, StandardCharsets.UTF_8);
                JSONObject root = JSONObject.fromObject(content);
                if (root.has("versions") && !root.getJSONObject("versions").isEmpty()) {
                    File parent = cacheDestination.getParentFile();
                    if (parent != null && !parent.exists()) {
                        Files.createDirectories(parent.toPath());
                    }
                    Files.writeString(cacheDestination.toPath(), content, StandardCharsets.UTF_8);
                    LOGGER.log(Level.INFO, "[Rust Plugin] Successfully cached latest Rust versions catalog to: " + cacheDestination.getAbsolutePath());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[Rust Plugin] Note: Dynamic catalog update skipped (offline or unreachable): " + e.getMessage());
        }
    }

    private boolean loadFromFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            String content = IOUtils.toString(in, StandardCharsets.UTF_8);
            return parseCatalogJson(content);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Rust Plugin] Failed to read version catalog file: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private void loadFromClasspath() {
        try (InputStream in = RustVersionCatalog.class.getResourceAsStream("versions.json")) {
            if (in == null) {
                LOGGER.log(Level.WARNING, "[Rust Plugin] Embedded versions.json resource not found on classpath!");
                return;
            }
            String content = IOUtils.toString(in, StandardCharsets.UTF_8);
            parseCatalogJson(content);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[Rust Plugin] Failed to parse embedded Rust versions catalog", e);
        }
    }

    private boolean parseCatalogJson(String content) {
        try {
            JSONObject root = JSONObject.fromObject(content);
            if (root.has("channels")) {
                channels.clear();
                for (Object o : root.getJSONArray("channels")) {
                    String ch = String.valueOf(o).trim();
                    if (!ch.isEmpty()) {
                        channels.add(ch);
                    }
                }
                if (channels.isEmpty()) {
                    channels.addAll(DEFAULT_CHANNELS);
                }
            }
            if (root.has("versions")) {
                JSONObject vObj = root.getJSONObject("versions");
                for (Object keyObj : vObj.keySet()) {
                    String ver = String.valueOf(keyObj);
                    JSONObject vData = vObj.getJSONObject(ver);
                    String releaseDate = vData.optString("releaseDate", "");
                    RustRelease release = new RustRelease(ver, releaseDate);

                    if (vData.has("platforms")) {
                        JSONObject pObj = vData.getJSONObject("platforms");
                        for (Object pKeyObj : pObj.keySet()) {
                            String pKey = String.valueOf(pKeyObj);
                            JSONObject pData = pObj.getJSONObject(pKey);
                            String url = pData.getString("url");
                            String filename = pData.optString("filename", "");
                            release.addPlatform(pKey, new PlatformDownload(url, filename));
                        }
                    }
                    releases.put(ver, release);
                }
                sortReleasesNewestFirst();
                parseVersionList(root, "sccacheVersions", sccacheVersions);
                parseVersionList(root, "binstallVersions", binstallVersions);
                return true;
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Rust Plugin] Invalid Rust catalog JSON format: " + e.getMessage());
        }
        return false;
    }

    private void parseVersionList(JSONObject root, String key, List<String> target) {
        target.clear();
        if (!root.has(key)) {
            return;
        }
        for (Object o : root.getJSONArray(key)) {
            String v = String.valueOf(o).trim();
            if (!v.isEmpty()) {
                target.add(v);
            }
        }
        target.sort(RustVersionCatalog::compareVersionsDescending);
    }

    private void sortReleasesNewestFirst() {
        List<Map.Entry<String, RustRelease>> entries = new ArrayList<>(releases.entrySet());
        entries.sort((a, b) -> compareVersionsDescending(a.getKey(), b.getKey()));
        releases.clear();
        for (Map.Entry<String, RustRelease> e : entries) {
            releases.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Orders dotted numeric versions newest-first so that "latest" never depends on the
     * key order of a hand-edited or merged catalog file. Non-numeric segments sort last.
     */
    static int compareVersionsDescending(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long na = i < pa.length ? parseSegment(pa[i]) : 0;
            long nb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (na != nb) {
                return Long.compare(nb, na);
            }
        }
        return 0;
    }

    private static long parseSegment(String segment) {
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public List<String> getChannels() {
        return new ArrayList<>(channels);
    }

    public List<String> getAvailableVersions() {
        return new ArrayList<>(releases.keySet());
    }

    public List<String> getSccacheVersions() {
        return new ArrayList<>(sccacheVersions);
    }

    public String getLatestSccacheVersion() {
        return sccacheVersions.isEmpty() ? SccacheInstaller.DEFAULT_SCCACHE_VERSION : sccacheVersions.get(0);
    }

    public List<String> getBinstallVersions() {
        return new ArrayList<>(binstallVersions);
    }

    public String getLatestBinstallVersion() {
        return binstallVersions.isEmpty() ? "latest" : binstallVersions.get(0);
    }

    public RustRelease getRelease(String version) {
        return releases.get(version);
    }

    public String getLatestVersion() {
        List<String> list = getAvailableVersions();
        return list.isEmpty() ? "1.85.0" : list.get(0);
    }

    /**
     * Resolves an exact version or prefix (e.g. "1.85" -> "1.85.1", "latest" -> latest stable).
     * Channels ("stable", "beta", "nightly") are preserved as-is for rustup.
     */
    public String resolveVersion(String versionSpec) {
        if (versionSpec == null || versionSpec.trim().isEmpty() || "latest".equalsIgnoreCase(versionSpec.trim())) {
            return getLatestVersion();
        }
        String clean = versionSpec.trim();
        for (String ch : channels) {
            if (ch.equalsIgnoreCase(clean)) {
                return ch.toLowerCase(Locale.ENGLISH);
            }
        }
        if (releases.containsKey(clean)) {
            return clean;
        }
        for (String v : releases.keySet()) {
            if (v.startsWith(clean + ".") || v.equals(clean)) {
                return v;
            }
        }
        return clean;
    }

    /**
     * Resolves a version for standalone archive installation (where "stable" or "latest" resolves to the latest numeric version).
     */
    public String resolveStandaloneVersion(String versionSpec) {
        if (versionSpec == null || versionSpec.trim().isEmpty()
                || "latest".equalsIgnoreCase(versionSpec.trim())
                || "stable".equalsIgnoreCase(versionSpec.trim())) {
            return getLatestVersion();
        }
        return resolveVersion(versionSpec);
    }

    public PlatformDownload getDownload(String version, String targetTriple) {
        String resolved = resolveStandaloneVersion(version);
        RustRelease release = releases.get(resolved);
        if (release != null) {
            return release.getPlatform(targetTriple);
        }
        return null;
    }

    /**
     * Builds the toolchain choices for UI dropdowns.
     */
    public static ListBoxModel buildToolchainItems(String currentValue, boolean includeChannels) {
        ListBoxModel model = new ListBoxModel();
        RustVersionCatalog catalog = getInstance();
        List<String> known = new ArrayList<>();

        if (includeChannels) {
            for (String ch : catalog.getChannels()) {
                model.add("Rust " + ch + " channel", ch);
                known.add(ch);
            }
        }

        List<String> versions = catalog.getAvailableVersions();
        String trimmedCurrent = Util.fixEmptyAndTrim(currentValue);
        if (trimmedCurrent != null && !known.contains(trimmedCurrent) && !versions.contains(trimmedCurrent)) {
            model.add(trimmedCurrent, trimmedCurrent);
        }

        for (String v : versions) {
            model.add("Rust " + v, v);
        }
        return model;
    }

    /**
     * Builds the sccache version choices shared by pipeline step editors.
     */
    public static ListBoxModel buildSccacheVersionItems(String currentValue) {
        return buildToolVersionItems(currentValue, getInstance().getSccacheVersions());
    }

    /**
     * Builds the cargo-binstall version choices shared by pipeline step editors.
     */
    public static ListBoxModel buildBinstallVersionItems(String currentValue) {
        return buildToolVersionItems(currentValue, getInstance().getBinstallVersions());
    }

    private static ListBoxModel buildToolVersionItems(String currentValue, List<String> catalogVersions) {
        ListBoxModel model = new ListBoxModel();
        model.add("latest", "latest");
        String trimmedCurrent = Util.fixEmptyAndTrim(currentValue);
        if (trimmedCurrent != null
                && !"latest".equalsIgnoreCase(trimmedCurrent)
                && !catalogVersions.contains(trimmedCurrent)) {
            model.add(trimmedCurrent, trimmedCurrent);
        }
        for (String v : catalogVersions) {
            model.add(v, v);
        }
        return model;
    }
}
