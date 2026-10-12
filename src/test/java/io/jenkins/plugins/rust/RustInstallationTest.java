package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.EnvVars;
import hudson.util.FormValidation;
import java.io.File;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RustInstallationTest {

    @Test
    void testBuildEnvVars(JenkinsRule j) {
        String mockHome = "/opt/rust-1.85.0";
        RustInstallation inst = new RustInstallation("Rust 1.85.0", mockHome, Collections.emptyList());

        EnvVars env = new EnvVars();
        inst.buildEnvVars(env);

        assertTrue(env.containsKey("PATH+RUST"));
        assertTrue(env.get("PATH+RUST").contains(mockHome));
        assertTrue(env.containsKey("CARGO_HOME"));
        assertTrue(env.containsKey("RUSTUP_HOME"));
        assertTrue(env.containsKey("RUSTC"));
        assertTrue(env.containsKey("CARGO"));
    }

    @Test
    void testDescriptorValidation(JenkinsRule j) {
        RustInstallation.DescriptorImpl desc = j.jenkins.getDescriptorByType(RustInstallation.DescriptorImpl.class);
        assertNotNull(desc);
        assertEquals("Rust", desc.getDisplayName());

        assertEquals(FormValidation.Kind.ERROR, desc.doCheckName("").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckName("Rust-Stable").kind);

        assertEquals(FormValidation.Kind.OK, desc.doCheckHome("").kind);
    }
}
