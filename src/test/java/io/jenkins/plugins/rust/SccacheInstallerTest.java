package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SccacheInstallerTest {

    @Test
    void testNormalizeVersion() {
        String expectedLatest = RustVersionCatalog.getInstance().getLatestSccacheVersion();
        assertEquals(expectedLatest, SccacheInstaller.normalizeVersion(null));
        assertEquals(expectedLatest, SccacheInstaller.normalizeVersion(""));
        assertEquals(expectedLatest, SccacheInstaller.normalizeVersion("latest"));
        assertEquals("0.18.0", SccacheInstaller.normalizeVersion("v0.18.0"));
        assertEquals("0.17.0", SccacheInstaller.normalizeVersion("0.17.0"));
    }

    @Test
    void testGetSccacheTargetTriple() {
        RustPlatformUtils.PlatformInfo linux = new RustPlatformUtils.PlatformInfo(
                "linux", "x86_64", "x86_64-unknown-linux-gnu", "");
        assertEquals("x86_64-unknown-linux-musl", SccacheInstaller.getSccacheTargetTriple(linux));

        RustPlatformUtils.PlatformInfo mac = new RustPlatformUtils.PlatformInfo(
                "darwin", "aarch64", "aarch64-apple-darwin", "");
        assertEquals("aarch64-apple-darwin", SccacheInstaller.getSccacheTargetTriple(mac));

        RustPlatformUtils.PlatformInfo win = new RustPlatformUtils.PlatformInfo(
                "windows", "x86_64", "x86_64-pc-windows-msvc", ".exe");
        assertEquals("x86_64-pc-windows-msvc", SccacheInstaller.getSccacheTargetTriple(win));
    }

    @Test
    void testGetArchiveFilename() {
        assertEquals("sccache-v0.18.0-x86_64-unknown-linux-musl.tar.gz",
                SccacheInstaller.getArchiveFilename("0.18.0", "x86_64-unknown-linux-musl"));
        assertEquals("sccache-v0.18.0-x86_64-apple-darwin.tar.gz",
                SccacheInstaller.getArchiveFilename("0.18.0", "x86_64-apple-darwin"));
        assertEquals("sccache-v0.18.0-x86_64-pc-windows-msvc.zip",
                SccacheInstaller.getArchiveFilename("0.18.0", "x86_64-pc-windows-msvc"));
    }

    @Test
    void testBuildDownloadUrl() {
        String url = SccacheInstaller.buildDownloadUrl(
                "0.18.0", "x86_64-unknown-linux-musl", null);
        assertEquals("https://github.com/mozilla/sccache/releases/download/v0.18.0/sccache-v0.18.0-x86_64-unknown-linux-musl.tar.gz", url);

        String mirrorUrl = SccacheInstaller.buildDownloadUrl(
                "0.18.0", "x86_64-unknown-linux-musl", "https://artifactory.corp/sccache/");
        assertEquals("https://artifactory.corp/sccache/v0.18.0/sccache-v0.18.0-x86_64-unknown-linux-musl.tar.gz", mirrorUrl);
    }
}
