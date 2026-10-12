package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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

    @Test
    void testPipelineExecutionAutoDetectToolchain(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "rust-pipeline-test");
        p.setDefinition(new CpsFlowDefinition(
            """
            node {
                writeFile file: 'rust-toolchain.toml', text: '[toolchain]\\nchannel = "1.85.0"\\ncomponents = ["clippy"]\\n'
                withRust(toolchain: 'auto', isolateCargoHome: true) {
                    echo "CARGO_HOME=${env.CARGO_HOME}"
                    echo "PATH=${env.PATH}"
                }
            }
            """, true));

        // Pre-register mock toolchain so test executes locally without downloading
        File mockHome = j.createTmpDir();
        new File(mockHome, "bin").mkdirs();
        new File(mockHome, ".cargo/bin").mkdirs();
        RustInstallation inst = new RustInstallation("rust-1.85.0", mockHome.getAbsolutePath(), Collections.emptyList());
        j.jenkins.getDescriptorByType(RustInstallation.DescriptorImpl.class).setInstallations(inst);

        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("[withRust] Auto-detected toolchain '1.85.0' from rust-toolchain file", b);
        j.assertLogContains("[withRust] Auto-detected components from rust-toolchain file: clippy", b);
        j.assertLogContains("CARGO_HOME=", b);
    }
}
