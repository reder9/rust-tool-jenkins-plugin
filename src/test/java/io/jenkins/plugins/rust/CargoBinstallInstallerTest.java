package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CargoBinstallInstallerTest {

    @Test
    void testNormalizeVersion() {
        assertEquals("latest", CargoBinstallInstaller.normalizeVersion(null));
        assertEquals("latest", CargoBinstallInstaller.normalizeVersion(""));
        assertEquals("latest", CargoBinstallInstaller.normalizeVersion("latest"));
        assertEquals("1.26.0", CargoBinstallInstaller.normalizeVersion("v1.26.0"));
        assertEquals("1.26.0", CargoBinstallInstaller.normalizeVersion("1.26.0"));
    }

    @Test
    void testGetArchiveFilename() {
        assertEquals("cargo-binstall-x86_64-unknown-linux-musl.tgz",
                CargoBinstallInstaller.getArchiveFilename("x86_64-unknown-linux-musl"));
        assertEquals("cargo-binstall-x86_64-unknown-linux-gnu.tgz",
                CargoBinstallInstaller.getArchiveFilename("x86_64-unknown-linux-gnu"));
        assertEquals("cargo-binstall-x86_64-apple-darwin.zip",
                CargoBinstallInstaller.getArchiveFilename("x86_64-apple-darwin"));
        assertEquals("cargo-binstall-x86_64-pc-windows-msvc.zip",
                CargoBinstallInstaller.getArchiveFilename("x86_64-pc-windows-msvc"));
    }

    @Test
    void testBuildDownloadUrl() {
        String latestUrl = CargoBinstallInstaller.buildDownloadUrl(
                "latest", "x86_64-unknown-linux-musl", null);
        assertEquals("https://github.com/cargo-bins/cargo-binstall/releases/latest/download/cargo-binstall-x86_64-unknown-linux-musl.tgz", latestUrl);

        String pinnedUrl = CargoBinstallInstaller.buildDownloadUrl(
                "1.26.0", "x86_64-unknown-linux-musl", null);
        assertEquals("https://github.com/cargo-bins/cargo-binstall/releases/download/v1.26.0/cargo-binstall-x86_64-unknown-linux-musl.tgz", pinnedUrl);

        String mirrorUrl = CargoBinstallInstaller.buildDownloadUrl(
                "1.26.0", "x86_64-pc-windows-msvc", "https://artifactory.corp/cargo-binstall/");
        assertEquals("https://artifactory.corp/cargo-binstall/v1.26.0/cargo-binstall-x86_64-pc-windows-msvc.zip", mirrorUrl);
    }
}
