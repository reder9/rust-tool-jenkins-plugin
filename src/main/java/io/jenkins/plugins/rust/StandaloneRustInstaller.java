package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstallerDescriptor;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

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

        String marker = toolchain + "|" + mirrorUrl;
        FilePath markerFile = expected.child(".installed-standalone");
        if (markerFile.exists()) {
            try {
                String existing = markerFile.readToString().trim();
                if (existing.equals(marker)) {
                    log.getLogger().println("[Rust] Standalone toolchain '" + toolchain + "' already installed at " + expected.getRemote());
                    return expected;
                }
            } catch (IOException ignored) {
            }
        }

        RustPlatformUtils.PlatformInfo platform = RustPlatformUtils.detect(node);
        String downloadUrl = RustPlatformUtils.getStandaloneArchiveUrl(toolchain, platform.targetTriple());
        log.getLogger().println("[Rust] Downloading standalone archive from " + downloadUrl);

        FilePath tempArchive = expected.child("rust-dist" + (platform.targetTriple().contains("windows") ? ".zip" : ".tar.gz"));
        URL url = URI.create(downloadUrl).toURL();
        URLConnection conn = ProxyConfiguration.open(url);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        try (InputStream in = conn.getInputStream()) {
            tempArchive.copyFrom(in);
        }

        log.getLogger().println("[Rust] Extracting toolchain archive...");
        if (tempArchive.getName().endsWith(".zip")) {
            tempArchive.unzip(expected);
        } else {
            tempArchive.untar(expected, FilePath.TarCompression.GZIP);
        }
        tempArchive.delete();

        // Write mirror config if specified
        if (mirrorUrl != null) {
            CargoConfigUtils.writeConfig(expected.child(".cargo"), mirrorUrl);
        }

        markerFile.write(marker, "UTF-8");
        log.getLogger().println("[Rust] Successfully installed standalone Rust toolchain '" + toolchain + "' at " + expected.getRemote());
        return expected;
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
    }
}
