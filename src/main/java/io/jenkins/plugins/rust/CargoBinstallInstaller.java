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
 * Provisions standalone cargo-binstall binaries across Linux, macOS, and Windows agents.
 * Enables fast installation of pre-compiled binary crates without compilation overhead.
 */
public final class CargoBinstallInstaller implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_BINSTALL_BASE_URL = "https://github.com/cargo-bins/cargo-binstall/releases";
    private static final String MARKER_FILENAME = ".installed-binstall-version";

    private CargoBinstallInstaller() {}

    public static String normalizeVersion(String version) {
        String clean = Util.fixEmptyAndTrim(version);
        if (clean == null || "latest".equalsIgnoreCase(clean)) {
            return "latest";
        }
        if ((clean.startsWith("v") || clean.startsWith("V")) && clean.length() > 1 && Character.isDigit(clean.charAt(1))) {
            return clean.substring(1);
        }
        return clean;
    }

    public static String getArchiveFilename(String targetTriple) {
        if (targetTriple.contains("windows")) {
            return "cargo-binstall-" + targetTriple + ".zip";
        } else if (targetTriple.contains("apple-darwin")) {
            return "cargo-binstall-" + targetTriple + ".zip";
        } else {
            // Linux and other Unix: .tgz
            return "cargo-binstall-" + targetTriple + ".tgz";
        }
    }

    public static String buildDownloadUrl(String version, String targetTriple, String customMirrorUrl) {
        String ver = normalizeVersion(version);
        String filename = getArchiveFilename(targetTriple);

        String effectiveMirror = Util.fixEmptyAndTrim(customMirrorUrl);
        if (effectiveMirror == null) {
            effectiveMirror = Util.fixEmptyAndTrim(System.getProperty("io.jenkins.plugins.rust.binstallMirrorUrl"));
        }

        if (effectiveMirror != null) {
            String base = effectiveMirror.endsWith("/") ? effectiveMirror : effectiveMirror + "/";
            if ("latest".equals(ver) || base.endsWith("/" + ver + "/")) {
                return base + filename;
            }
            return base + "v" + ver + "/" + filename;
        }

        if ("latest".equals(ver)) {
            return DEFAULT_BINSTALL_BASE_URL + "/latest/download/" + filename;
        }
        return DEFAULT_BINSTALL_BASE_URL + "/download/v" + ver + "/" + filename;
    }

    public static FilePath provisionBinstall(Node node, FilePath workspaceFallback, TaskListener log,
                                            String version, String customMirrorUrl) throws IOException, InterruptedException {
        RustPlatformUtils.PlatformInfo platform = RustPlatformUtils.detect(node);
        String resolvedVersion = normalizeVersion(version);
        String downloadUrl = buildDownloadUrl(resolvedVersion, platform.targetTriple(), customMirrorUrl);
        String filename = getArchiveFilename(platform.targetTriple());

        FilePath rootDir = node != null ? node.getRootPath() : null;
        if (rootDir == null) {
            rootDir = workspaceFallback;
        }
        if (rootDir == null) {
            throw new IOException("[Rust Plugin] Cannot determine agent root or workspace directory to install cargo-binstall.");
        }

        FilePath installDir = rootDir.child("tools")
                .child("io.jenkins.plugins.rust.CargoBinstallInstaller")
                .child("binstall-" + resolvedVersion + "-" + platform.targetTriple());

        FilePath marker = installDir.child(MARKER_FILENAME);
        String markerContent = resolvedVersion + "@" + downloadUrl;

        boolean isWin = platform.binaryExtension().equals(".exe");
        FilePath expectedBinary = installDir.child(isWin ? "cargo-binstall.exe" : "cargo-binstall");

        if (marker.exists() && expectedBinary.exists()) {
            try {
                String existing = marker.readToString().trim();
                if (existing.equals(markerContent)) {
                    if (log != null) {
                        log.getLogger().println("[Rust Plugin] cargo-binstall (" + resolvedVersion + ") is already cached at: " + installDir.getRemote());
                    }
                    return installDir;
                }
            } catch (IOException | InterruptedException ignored) {
            }
        }

        if (log != null) {
            log.getLogger().println("[Rust Plugin] Provisioning cargo-binstall (" + resolvedVersion + ") for " + platform.targetTriple() + " from: " + downloadUrl);
        }

        installDir.mkdirs();
        FilePath tempArchive = installDir.child("binstall-download-" + filename);

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

        flattenAndChmodBinaries(installDir, !isWin, isWin ? "cargo-binstall.exe" : "cargo-binstall");
        marker.write(markerContent, "UTF-8");

        if (log != null) {
            log.getLogger().println("[Rust Plugin] Successfully provisioned cargo-binstall at: " + installDir.getRemote());
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
