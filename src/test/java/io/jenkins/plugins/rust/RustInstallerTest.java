package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RustInstallerTest {

    @Test
    void testComponentParsing() {
        RustupInstaller installer = new RustupInstaller("stable", "https://mirror.internal", "clippy, rustfmt,  miri", "wasm32-unknown-unknown, x86_64-unknown-linux-musl");

        List<String> comps = installer.getComponentList();
        assertEquals(3, comps.size());
        assertEquals("clippy", comps.get(0));
        assertEquals("rustfmt", comps.get(1));
        assertEquals("miri", comps.get(2));

        List<String> tgts = installer.getTargetList();
        assertEquals(2, tgts.size());
        assertEquals("wasm32-unknown-unknown", tgts.get(0));
        assertEquals("x86_64-unknown-linux-musl", tgts.get(1));
    }

    @Test
    void testEmptyComponentsAndTargets() {
        RustupInstaller installer = new RustupInstaller("1.85.0", null, null, null);
        assertTrue(installer.getComponentList().isEmpty());
        assertTrue(installer.getTargetList().isEmpty());
        assertEquals("1.85.0", installer.getToolchain());
    }

    @Test
    void testStandaloneInstaller() {
        StandaloneRustInstaller installer = new StandaloneRustInstaller("1.84.0", "https://mirror.internal");
        assertEquals("1.84.0", installer.getToolchain());
        assertEquals("https://mirror.internal", installer.getMirrorUrl());
    }
}
