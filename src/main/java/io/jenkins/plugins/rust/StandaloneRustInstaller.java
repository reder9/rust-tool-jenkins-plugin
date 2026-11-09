package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Installs standalone pre-built Rust toolchain archives directly from static.rust-lang.org.
 * Ideal for air-gapped or restricted environments where rustup is not permitted.
 */
public class StandaloneRustInstaller extends RustInstaller {

    @DataBoundConstructor
    public StandaloneRustInstaller(String toolchain, String mirrorUrl) {
        super(null, toolchain, mirrorUrl, null, null);
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {
        FilePath expected = preferredLocation(tool, node);
        expected.mkdirs();

        RustVersionCatalog catalog = RustVersionCatalog.getInstance();
        String resolvedToolchain = catalog.resolveStandaloneVersion(toolchain);

        String marker = resolvedToolchain + "|" + mirrorUrl;
        FilePath markerFile = expected.child(".installed-standalone");
        if (markerFile.exists()) {
            try {
                String existing = markerFile.readToString().trim();
                if (existing.equals(marker)) {
                    log.getLogger().println("[Rust] Standalone toolchain '" + resolvedToolchain + "' already installed at " + expected.getRemote());
                    return expected;
                }
            } catch (IOException ignored) {
            }
        }

        RustPlatformUtils.PlatformInfo platform = RustPlatformUtils.detect(node);
        RustVersionCatalog.PlatformDownload catalogDownload = catalog.getDownload(resolvedToolchain, platform.targetTriple());
        String downloadUrl = catalogDownload != null
                ? catalogDownload.getUrl()
                : RustPlatformUtils.getStandaloneArchiveUrl(resolvedToolchain, platform.targetTriple());

        log.getLogger().println("[Rust] Downloading standalone archive (" + resolvedToolchain + ") from " + downloadUrl);

        FilePath tempArchive = expected.child("rust-dist" + (platform.targetTriple().contains("windows") ? ".zip" : ".tar.gz"));
        try {
            URL url = URI.create(downloadUrl).toURL();
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            try (InputStream in = conn.getInputStream()) {
                tempArchive.copyFrom(in);
            }

            if (getExpectedSha256() != null && !getExpectedSha256().isBlank()) {
                RustChecksumUtils.verifySha256(tempArchive, getExpectedSha256(), log);
            } else {
                RustChecksumUtils.downloadAndVerifySha256(tempArchive, downloadUrl + ".sha256", log, false);
            }

            log.getLogger().println("[Rust] Extracting toolchain archive...");
            if (tempArchive.getName().endsWith(".zip")) {
                tempArchive.unzip(expected);
            } else {
                tempArchive.untar(expected, FilePath.TarCompression.GZIP);
            }
        } finally {
            if (tempArchive.exists()) {
                tempArchive.delete();
            }
        }

        flattenStandaloneBinaries(expected, !platform.binaryExtension().equals(".exe"), platform.binaryExtension());

        // Write mirror config if specified
        if (mirrorUrl != null) {
            CargoConfigUtils.writeConfig(expected.child(".cargo"), mirrorUrl);
        }

        markerFile.write(marker, "UTF-8");
        log.getLogger().println("[Rust] Successfully installed standalone Rust toolchain '" + resolvedToolchain + "' at " + expected.getRemote());
        return expected;
    }

    static void flattenStandaloneBinaries(FilePath expected, boolean isUnix, String ext)
            throws IOException, InterruptedException {
        FilePath targetBin = expected.child("bin");
        targetBin.mkdirs();

        String[] binaries = {"rustc", "rustdoc", "cargo", "rustfmt", "cargo-fmt", "cargo-clippy", "clippy-driver"};
        String[] components = {"rustc", "cargo", "rustfmt-preview", "clippy-preview"};

        List<FilePath> topDirs = expected.listDirectories();
        for (FilePath topDir : topDirs) {
            if ("bin".equals(topDir.getName()) || ".cargo".equals(topDir.getName())) {
                continue;
            }
            for (String comp : components) {
                FilePath compBin = topDir.child(comp).child("bin");
                if (compBin.exists()) {
                    for (String bin : binaries) {
                        FilePath src = compBin.child(bin + ext);
                        if (src.exists()) {
                            FilePath dst = targetBin.child(bin + ext);
                            src.copyTo(dst);
                            if (isUnix) {
                                try {
                                    dst.chmod(0755);
                                } catch (IOException | InterruptedException ignored) {
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Extension
    @Symbol("standaloneRust")
    public static class DescriptorImpl extends ToolInstallerDescriptor<StandaloneRustInstaller> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Install standalone from static.rust-lang.org";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == RustInstallation.class;
        }

        @POST
        @SuppressWarnings({"lgtm[jenkins/no-permission-check]", "lgtm[jenkins/csrf]"})
        public ListBoxModel doFillToolchainItems(@QueryParameter String toolchain) {
            Jenkins instance = Jenkins.getInstanceOrNull();
            if (instance != null && !instance.hasPermission(Jenkins.MANAGE) && !instance.hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }
            return RustVersionCatalog.buildToolchainItems(toolchain, false);
        }

        @POST
        @SuppressWarnings({"lgtm[jenkins/no-permission-check]", "lgtm[jenkins/csrf]"})
        public FormValidation doCheckToolchain(@QueryParameter String value) {
            Jenkins instance = Jenkins.getInstanceOrNull();
            if (instance != null) {
                if (!instance.hasPermission(Jenkins.ADMINISTER)) {
                    instance.checkPermission(Jenkins.MANAGE);
                }
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Rust version must be specified.");
            }
            RustVersionCatalog catalog = RustVersionCatalog.getInstance();
            String resolved = catalog.resolveStandaloneVersion(value.trim());
            if (catalog.getRelease(resolved) == null) {
                return FormValidation.warning("Version '" + value.trim() + "' is not present in the bundled catalog. It will attempt direct download.");
            }
            if (!resolved.equals(value.trim())) {
                return FormValidation.ok("Resolves to catalog release: " + resolved);
            }
            return FormValidation.ok();
        }
    }
}
