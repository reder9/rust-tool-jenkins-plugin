package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RustToolchainFileParserTest {

    @Test
    void testParseTomlFull() throws Exception {
        String toml = """
            [toolchain]
            channel = "1.85.0"
            components = ["clippy", "rustfmt"]
            targets = ["wasm32-unknown-unknown", "x86_64-unknown-linux-musl"]
            profile = "minimal"
            """;

        RustToolchainFileParser.ToolchainSpec spec = RustToolchainFileParser.parseToml(toml);
        assertNotNull(spec);
        assertEquals("1.85.0", spec.channel());
        assertEquals("minimal", spec.profile());
        assertEquals(2, spec.components().size());
        assertEquals("clippy", spec.components().get(0));
        assertEquals("rustfmt", spec.components().get(1));
        assertEquals(2, spec.targets().size());
        assertEquals("wasm32-unknown-unknown", spec.targets().get(0));
        assertEquals("x86_64-unknown-linux-musl", spec.targets().get(1));
    }

    @Test
    void testParseTomlMultilineArray() throws Exception {
        String toml = """
            [toolchain]
            channel = "nightly-2026-03-01"
            components = [
                "clippy",
                "rustfmt",
                "rust-src"
            ]
            """;

        RustToolchainFileParser.ToolchainSpec spec = RustToolchainFileParser.parseToml(toml);
        assertNotNull(spec);
        assertEquals("nightly-2026-03-01", spec.channel());
        assertEquals(3, spec.components().size());
        assertTrue(spec.components().contains("rust-src"));
    }

    @Test
    void testParseFromDirectoryToml(@TempDir Path tempDir) throws Exception {
        FilePath dir = new FilePath(tempDir.toFile());
        dir.child("rust-toolchain.toml").write("[toolchain]\nchannel = \"stable\"\ncomponents = [\"clippy\"]\n", "UTF-8");

        RustToolchainFileParser.ToolchainSpec spec = RustToolchainFileParser.parseFromDirectory(dir);
        assertNotNull(spec);
        assertEquals("stable", spec.channel());
        assertEquals(1, spec.components().size());
        assertEquals("clippy", spec.components().get(0));
    }

    @Test
    void testParseFromDirectoryLegacy(@TempDir Path tempDir) throws Exception {
        FilePath dir = new FilePath(tempDir.toFile());
        dir.child("rust-toolchain").write("1.84.1\n", "UTF-8");

        RustToolchainFileParser.ToolchainSpec spec = RustToolchainFileParser.parseFromDirectory(dir);
        assertNotNull(spec);
        assertEquals("1.84.1", spec.channel());
        assertTrue(spec.components().isEmpty());
    }

    @Test
    void testParseNonExistent(@TempDir Path tempDir) throws Exception {
        FilePath dir = new FilePath(tempDir.toFile());
        assertNull(RustToolchainFileParser.parseFromDirectory(dir));
    }
}
