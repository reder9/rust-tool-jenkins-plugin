package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class WithRustStepTest {

    @Test
    void testStepConfiguration(JenkinsRule j) {
        WithRustStep step = new WithRustStep("1.85.0");
        step.setComponents("clippy,rustfmt");
        step.setTargets("wasm32-unknown-unknown");
        step.setMirrorUrl("https://artifactory.corp/cargo-virtual");
        step.setIsolateCargoHome(true);

        assertEquals("1.85.0", step.getToolchain());
        assertEquals("clippy,rustfmt", step.getComponents());
        assertEquals("wasm32-unknown-unknown", step.getTargets());
        assertEquals("https://artifactory.corp/cargo-virtual", step.getMirrorUrl());
        assertTrue(step.isIsolateCargoHome());
    }

    @Test
    void testDescriptorMetadata(JenkinsRule j) {
        WithRustStep.DescriptorImpl desc = j.jenkins.getDescriptorByType(WithRustStep.DescriptorImpl.class);
        assertNotNull(desc);
        assertEquals("withRust", desc.getFunctionName());
        assertTrue(desc.takesImplicitBlockArgument());
        assertFalse(desc.getRequiredContext().isEmpty());
    }
}
