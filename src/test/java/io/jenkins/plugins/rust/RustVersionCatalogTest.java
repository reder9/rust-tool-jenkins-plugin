package io.jenkins.plugins.rust;

import hudson.util.ListBoxModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustVersionCatalogTest {

    @Test
    void testCatalogLoadsVersionsAndPreservesHistory() {
        RustVersionCatalog catalog = RustVersionCatalog.getInstance();
        List<String> versions = catalog.getAvailableVersions();

        assertNotNull(versions);
        assertFalse(versions.isEmpty(), "Catalog should contain Rust versions");
        assertTrue(versions.size() >= 15, "Catalog should retain historical and current Rust versions");
        assertTrue(versions.contains("1.85.0"), "Catalog should preserve 1.85.0");
        assertTrue(versions.contains("1.84.1"), "Catalog should preserve 1.84.1");
        assertTrue(versions.contains("1.75.0"), "Catalog should preserve 1.75.0");
        assertEquals(List.of("stable", "beta", "nightly"), catalog.getChannels());
    }

    @Test
    void testCatalogLoadsSccacheAndBinstallVersions() {
        RustVersionCatalog catalog = RustVersionCatalog.getInstance();

        List<String> sccacheVersions = catalog.getSccacheVersions();
        assertNotNull(sccacheVersions);
        assertFalse(sccacheVersions.isEmpty(), "Catalog should contain sccache versions");
        assertTrue(sccacheVersions.contains("0.18.0"));

        List<String> binstallVersions = catalog.getBinstallVersions();
        assertNotNull(binstallVersions);
        assertFalse(binstallVersions.isEmpty(), "Catalog should contain cargo-binstall versions");
        assertTrue(binstallVersions.contains("1.26.0"));
    }

    @Test
    void testDefaultRemoteUrlTargetsOfficialCatalogBranch() {
        assertEquals(
                "https://raw.githubusercontent.com/jenkinsci/rust-tool-plugin/catalog/versions.json",
                RustVersionCatalog.DEFAULT_REMOTE_URL
        );
    }

    @Test
    void testResolveVersionAndChannels() {
        RustVersionCatalog catalog = RustVersionCatalog.getInstance();

        assertEquals("stable", catalog.resolveVersion("stable"));
        assertEquals("beta", catalog.resolveVersion("beta"));
        assertEquals("nightly", catalog.resolveVersion("nightly"));

        String latest = catalog.resolveVersion("latest");
        assertNotNull(latest);
        assertFalse(latest.isEmpty());
        assertEquals(latest, catalog.resolveStandaloneVersion("stable"));

        String rust185 = catalog.resolveVersion("1.85");
        assertNotNull(rust185);
        assertTrue(rust185.startsWith("1.85."), "1.85 prefix should resolve to 1.85.x");
    }

    @Test
    void testDownloadsContainMajorTriples() {
        RustVersionCatalog catalog = RustVersionCatalog.getInstance();
        String latest = catalog.getLatestVersion();

        RustVersionCatalog.PlatformDownload linuxGnu = catalog.getDownload(latest, "x86_64-unknown-linux-gnu");
        assertNotNull(linuxGnu, "Missing x86_64-unknown-linux-gnu for " + latest);
        assertTrue(linuxGnu.getUrl().contains("x86_64-unknown-linux-gnu.tar.gz"));

        RustVersionCatalog.PlatformDownload linuxMusl = catalog.getDownload(latest, "x86_64-unknown-linux-musl");
        assertNotNull(linuxMusl, "Missing x86_64-unknown-linux-musl for " + latest);
        assertTrue(linuxMusl.getUrl().contains("x86_64-unknown-linux-musl.tar.gz"));

        RustVersionCatalog.PlatformDownload macArm64 = catalog.getDownload(latest, "aarch64-apple-darwin");
        assertNotNull(macArm64, "Missing aarch64-apple-darwin for " + latest);
        assertTrue(macArm64.getUrl().contains("aarch64-apple-darwin.tar.gz"));

        RustVersionCatalog.PlatformDownload winMsvc = catalog.getDownload(latest, "x86_64-pc-windows-msvc");
        assertNotNull(winMsvc, "Missing x86_64-pc-windows-msvc for " + latest);
        assertTrue(winMsvc.getUrl().contains("x86_64-pc-windows-msvc.zip"));
    }

    @Test
    void testUiListBoxBuildersPreserveCustomValues() {
        ListBoxModel toolchainsWithChannels = RustVersionCatalog.buildToolchainItems("custom-1.99.9", true);
        assertTrue(toolchainsWithChannels.stream().anyMatch(o -> "stable".equals(o.value)));
        assertTrue(toolchainsWithChannels.stream().anyMatch(o -> "custom-1.99.9".equals(o.value)));

        ListBoxModel sccacheItems = RustVersionCatalog.buildSccacheVersionItems("0.99.9");
        assertEquals("latest", sccacheItems.get(0).value);
        assertTrue(sccacheItems.stream().anyMatch(o -> "0.99.9".equals(o.value)));

        ListBoxModel binstallItems = RustVersionCatalog.buildBinstallVersionItems("1.99.9");
        assertEquals("latest", binstallItems.get(0).value);
        assertTrue(binstallItems.stream().anyMatch(o -> "1.99.9".equals(o.value)));
    }

    @Test
    void testAirGappedFileOverride(@TempDir Path tempDir) throws Exception {
        String customJson = "{\n" +
                "  \"channels\": [\"stable\", \"nightly\"],\n" +
                "  \"versions\": {\n" +
                "    \"1.99.9\": {\n" +
                "      \"releaseDate\": \"2099-01-01\",\n" +
                "      \"platforms\": {\n" +
                "        \"x86_64-unknown-linux-gnu\": {\n" +
                "          \"url\": \"https://internal.mirror/rust-1.99.9-x86_64-unknown-linux-gnu.tar.gz\",\n" +
                "          \"filename\": \"rust-1.99.9-x86_64-unknown-linux-gnu.tar.gz\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"sccacheVersions\": [\"0.99.1\", \"0.99.0\"],\n" +
                "  \"binstallVersions\": [\"1.99.1\", \"1.99.0\"]\n" +
                "}";

        Path overrideFile = tempDir.resolve("custom-rust-versions.json");
        Files.writeString(overrideFile, customJson);

        String prev = System.getProperty("io.jenkins.plugins.rust.versionsFile");
        try {
            System.setProperty("io.jenkins.plugins.rust.versionsFile", overrideFile.toString());
            RustVersionCatalog catalog = RustVersionCatalog.getInstance();
            catalog.reload();

            assertTrue(catalog.getAvailableVersions().contains("1.99.9"));
            assertEquals("1.99.9", catalog.resolveVersion("1.99"));
            assertEquals(List.of("0.99.1", "0.99.0"), catalog.getSccacheVersions());
            assertEquals(List.of("1.99.1", "1.99.0"), catalog.getBinstallVersions());

            RustVersionCatalog.PlatformDownload dl = catalog.getDownload("1.99.9", "x86_64-unknown-linux-gnu");
            assertNotNull(dl);
            assertEquals("https://internal.mirror/rust-1.99.9-x86_64-unknown-linux-gnu.tar.gz", dl.getUrl());
        } finally {
            if (prev != null) {
                System.setProperty("io.jenkins.plugins.rust.versionsFile", prev);
            } else {
                System.clearProperty("io.jenkins.plugins.rust.versionsFile");
            }
            RustVersionCatalog.getInstance().reload();
        }
    }
}
