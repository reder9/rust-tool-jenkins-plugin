package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RustProbeUtilsTest {

    @Test
    void testSystemRustInfoGetters() {
        RustProbeUtils.SystemRustInfo info = new RustProbeUtils.SystemRustInfo(
                "/usr/local/cargo",
                "/usr/local/cargo/bin",
                "/usr/local/cargo/bin/rustc",
                "/usr/local/cargo/bin/cargo",
                "/usr/local/cargo/bin/rustup",
                false
        );

        assertEquals("/usr/local/cargo", info.getHome());
        assertEquals("/usr/local/cargo/bin", info.getBinDir());
        assertEquals("/usr/local/cargo/bin/rustc", info.getRustc());
        assertEquals("/usr/local/cargo/bin/cargo", info.getCargo());
        assertEquals("/usr/local/cargo/bin/rustup", info.getRustup());
        assertFalse(info.isMusl());
    }

    @Test
    void testProbeFallbackWithoutNode() {
        // Calling probeSystemRust with null node shouldn't throw, either returns system rust or null
        RustProbeUtils.SystemRustInfo info = RustProbeUtils.probeSystemRust(null, null, null);
        if (info != null) {
            assertNotNull(info.getCargo());
            assertNotNull(info.getRustc());
            assertNotNull(info.getBinDir());
        }
    }
}
