package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RustPlatformUtilsTest {

    @Test
    void testResolveTripleLinuxGlibc() {
        assertEquals("x86_64-unknown-linux-gnu", RustPlatformUtils.resolveTriple("linux", "x86_64"));
        assertEquals("aarch64-unknown-linux-gnu", RustPlatformUtils.resolveTriple("linux", "aarch64"));
        assertEquals("armv7-unknown-linux-gnueabihf", RustPlatformUtils.resolveTriple("linux", "armv7"));
        assertEquals("riscv64gc-unknown-linux-gnu", RustPlatformUtils.resolveTriple("linux", "riscv64gc"));
        assertEquals("powerpc64le-unknown-linux-gnu", RustPlatformUtils.resolveTriple("linux", "powerpc64le"));
        assertEquals("s390x-unknown-linux-gnu", RustPlatformUtils.resolveTriple("linux", "s390x"));
    }

    @Test
    void testResolveTripleLinuxMusl() {
        assertEquals("x86_64-unknown-linux-musl", RustPlatformUtils.resolveTriple("linux", "x86_64", true));
        assertEquals("aarch64-unknown-linux-musl", RustPlatformUtils.resolveTriple("linux", "aarch64", true));
        assertEquals("armv7-unknown-linux-musleabihf", RustPlatformUtils.resolveTriple("linux", "armv7", true));
    }

    @Test
    void testResolveTripleDarwin() {
        assertEquals("x86_64-apple-darwin", RustPlatformUtils.resolveTriple("darwin", "x86_64"));
        assertEquals("aarch64-apple-darwin", RustPlatformUtils.resolveTriple("darwin", "aarch64"));
    }

    @Test
    void testResolveTripleWindows() {
        assertEquals("x86_64-pc-windows-msvc", RustPlatformUtils.resolveTriple("windows", "x86_64"));
        assertEquals("aarch64-pc-windows-msvc", RustPlatformUtils.resolveTriple("windows", "aarch64"));
        assertEquals("i686-pc-windows-msvc", RustPlatformUtils.resolveTriple("windows", "i686"));
    }

    @Test
    void testParsePlatform() {
        RustPlatformUtils.PlatformInfo linuxInfo = RustPlatformUtils.parsePlatform("Linux", "amd64");
        assertEquals("linux", linuxInfo.os());
        assertEquals("x86_64", linuxInfo.arch());
        assertEquals("x86_64-unknown-linux-gnu", linuxInfo.targetTriple());
        assertEquals("", linuxInfo.binaryExtension());
        assertFalse(linuxInfo.isMusl());

        RustPlatformUtils.PlatformInfo winInfo = RustPlatformUtils.parsePlatform("Windows 11", "aarch64");
        assertEquals("windows", winInfo.os());
        assertEquals("aarch64", winInfo.arch());
        assertEquals("aarch64-pc-windows-msvc", winInfo.targetTriple());
        assertEquals(".exe", winInfo.binaryExtension());
        assertFalse(winInfo.isMusl());

        RustPlatformUtils.PlatformInfo alpineInfo = RustPlatformUtils.parsePlatform("Alpine Linux v3.20", "x86_64");
        assertEquals("linux", alpineInfo.os());
        assertEquals("x86_64", alpineInfo.arch());
        assertEquals("x86_64-unknown-linux-musl", alpineInfo.targetTriple());
        assertTrue(alpineInfo.isMusl());

        RustPlatformUtils.PlatformInfo muslInfo = RustPlatformUtils.parsePlatform("Linux", "aarch64", true);
        assertEquals("aarch64-unknown-linux-musl", muslInfo.targetTriple());
        assertTrue(muslInfo.isMusl());
    }

    @Test
    void testFailEarlyOnUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> RustPlatformUtils.parsePlatform("FreeBSD", "x86_64"));
        assertThrows(IllegalArgumentException.class, () -> RustPlatformUtils.parsePlatform("Linux", "sparc"));
    }

    @Test
    void testRustupInitUrl() {
        String url = RustPlatformUtils.getRustupInitUrl("x86_64-unknown-linux-gnu", "");
        assertEquals("https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init", url);

        String muslUrl = RustPlatformUtils.getRustupInitUrl("x86_64-unknown-linux-musl", "");
        assertEquals("https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-musl/rustup-init", muslUrl);

        String winUrl = RustPlatformUtils.getRustupInitUrl("x86_64-pc-windows-msvc", ".exe");
        assertEquals("https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe", winUrl);
    }

    @Test
    void testStandaloneArchiveUrl() {
        String url = RustPlatformUtils.getStandaloneArchiveUrl("1.85.0", "x86_64-unknown-linux-gnu");
        assertEquals("https://static.rust-lang.org/dist/rust-1.85.0-x86_64-unknown-linux-gnu.tar.gz", url);

        String muslUrl = RustPlatformUtils.getStandaloneArchiveUrl("1.85.0", "x86_64-unknown-linux-musl");
        assertEquals("https://static.rust-lang.org/dist/rust-1.85.0-x86_64-unknown-linux-musl.tar.gz", muslUrl);

        String winUrl = RustPlatformUtils.getStandaloneArchiveUrl("1.85.0", "x86_64-pc-windows-msvc");
        assertEquals("https://static.rust-lang.org/dist/rust-1.85.0-x86_64-pc-windows-msvc.zip", winUrl);
    }
}
