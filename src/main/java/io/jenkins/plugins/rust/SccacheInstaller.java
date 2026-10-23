package io.jenkins.plugins.rust;

import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Provisions standalone Mozilla sccache binaries across Linux, macOS, and Windows agents.
 * Enables shared compiler caching across builds and agents, drastically speeding up Rust CI jobs.
 */
public final class SccacheInstaller implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_SCCACHE_BASE_URL = "https://github.com/mozilla/sccache/releases";
    public static final String DEFAULT_SCCACHE_VERSION = "0.18.0";
    private static final String MARKER_FILENAME = ".installed-sccache-version";

    private SccacheInstaller() {}

    public static String normalizeVersion(String version) {
        String clean = Util.fixEmptyAndTrim(version);
        if (clean == null || "latest".equalsIgnoreCase(clean)) {
            return DEFAULT_SCCACHE_VERSION;
        }
        if ((clean.startsWith("v") || clean.startsWith("V")) && clean.length() > 1 && Character.isDigit(clean.charAt(1))) {
            return clean.substring(1);
        }
        return clean;
    }

    public static String getSccacheTargetTriple(RustPlatformUtils.PlatformInfo platform) {
        if ("windows".equals(platform.os())) {
            return platform.arch() + "-pc-windows-msvc";
        } else if ("darwin".equals(platform.os())) {
            return platform.arch() + "-apple-darwin";
        } else {
            // Linux musl static binaries are provided by Mozilla for all linux distributions
            return platform.arch() + "-unknown-linux-musl";
        }
    }

    public static String getArchiveFilename(String version, String sccacheTriple) {
        String ext = sccacheTriple.contains("windows") ? ".zip" : ".tar.gz";
        return "sccache-v" + version + "-" + sccacheTriple + ext;
    }

    public static String buildDownloadUrl(String version, String sccacheTriple, String customMirrorUrl) {
        String ver = normalizeVersion(version);
        String filename = getArchiveFilename(ver, sccacheTriple);

        String effectiveMirror = Util.fixEmptyAndTrim(customMirrorUrl);
        if (effectiveMirror == null) {
            effectiveMirror = Util.fixEmptyAndTrim(System.getProperty("io.jenkins.plugins.rust.sccacheMirrorUrl"));
        }

        if (effectiveMirror != null) {
            String base = effectiveMirror.endsWith("/") ? effectiveMirror : effectiveMirror + "/";
            if (base.endsWith("/" + ver + "/") || base.endsWith("/v" + ver + "/")) {
                return base + filename;
            }
            return base + "v" + ver + "/" + filename;
        }

        return DEFAULT_SCCACHE_BASE_URL + "/download/v" + ver + "/" + filename;
    }

    public static FilePath provisionSccache(Node node, FilePath workspaceFallback, TaskListener log,
                                           String version, String customMirrorUrl) throws IOException, InterruptedException {
        RustPlatformUtils.PlatformInfo platform = RustPlatformUtils.detect(node);
        String resolvedVersion = normalizeVersion(version);
        String sccacheTriple = getSccacheTargetTriple(platform);
        String downloadUrl = buildDownloadUrl(resolvedVersion, sccacheTriple, customMirrorUrl);
        String filename = getArchiveFilename(resolvedVersion, sccacheTriple);

        FilePath rootDir = node != null ? node.getRootPath() : null;
        if (rootDir == null) {
            rootDir = workspaceFallback;
        }
        if (rootDir == null) {
            throw new IOException("[Rust Plugin] Cannot determine agent root or workspace directory to install sccache.");
        }

        FilePath installDir = rootDir.child("tools")
                .child("io.jenkins.plugins.rust.SccacheInstaller")
                .child("sccache-" + resolvedVersion + "-" + sccacheTriple);

        FilePath marker = installDir.child(MARKER_FILENAME);
        String markerContent = resolvedVersion + "@" + downloadUrl;

        boolean isWin = platform.binaryExtension().equals(".exe");
        FilePath expectedBinary = installDir.child(isWin ? "sccache.exe" : "sccache");

        if (marker.exists() && expectedBinary.exists()) {
            try {
                String existing = marker.readToString().trim();
                if (existing.equals(markerContent)) {
                    if (log != null) {
                        log.getLogger().println("[Rust Plugin] sccache (" + resolvedVersion + ") is already cached at: " + installDir.getRemote());
                    }
                    return installDir;
                }
            } catch (IOException | InterruptedException ignored) {
            }
        }

        if (log != null) {
            log.getLogger().println("[Rust Plugin] Provisioning sccache (" + resolvedVersion + ") for " + sccacheTriple + " from: " + downloadUrl);
        }

        installDir.mkdirs();
        FilePath tempArchive = installDir.child("sccache-download-" + filename);

        try {
            URL url = URI.create(downloadUrl).toURL();
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            try (InputStream in = conn.getInputStream()) {
                tempArchive.copyFrom(in);
            }

            if (filename.endsWith(".zip")) {
                tempArchive.unzip(installDir);
            } else {
                tempArchive.untar(installDir, FilePath.TarCompression.GZIP);
            }
        } finally {
            if (tempArchive.exists()) {
                tempArchive.delete();
            }
        }

        flattenAndChmodBinaries(installDir, !isWin, isWin ? "sccache.exe" : "sccache");
        marker.write(markerContent, "UTF-8");

        if (log != null) {
            log.getLogger().println("[Rust Plugin] Successfully provisioned sccache at: " + installDir.getRemote());
        }
        return installDir;
    }

    static void flattenAndChmodBinaries(FilePath installDir, boolean isUnix, String binaryName) throws IOException, InterruptedException {
        FilePath primaryExe = installDir.child(binaryName);
        if (!primaryExe.exists()) {
            List<FilePath> subDirs = installDir.listDirectories();
            for (FilePath subDir : subDirs) {
                FilePath candidate = subDir.child(binaryName);
                if (candidate.exists()) {
                    candidate.copyTo(installDir.child(binaryName));
                    break;
                }
            }
        }

        if (isUnix) {
            FilePath bin = installDir.child(binaryName);
            if (bin.exists()) {
                try {
                    bin.chmod(0755);
                } catch (IOException | InterruptedException ignored) {
                }
            }
        }
    }
}
