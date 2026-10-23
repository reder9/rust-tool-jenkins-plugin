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
        step.setTool("Rust-1.85.0");
        step.setHome("/opt/rust");
        step.setSystem(true);
        step.setComponents("clippy,rustfmt");
        step.setTargets("wasm32-unknown-unknown");
        step.setMirrorUrl("https://artifactory.corp/cargo-virtual");
        step.setIsolateCargoHome(true);
        step.setSccache(true);
        step.setSccacheVersion("0.18.0");
        step.setSccacheDir(".sccache-custom");
        step.setBinstall(true);
        step.setBinstallVersion("1.26.0");
        step.setCrates("cargo-nextest,cargo-audit");

        assertEquals("1.85.0", step.getToolchain());
        assertEquals("Rust-1.85.0", step.getTool());
        assertEquals("/opt/rust", step.getHome());
        assertTrue(step.isSystem());
        assertEquals("clippy,rustfmt", step.getComponents());
        assertEquals("wasm32-unknown-unknown", step.getTargets());
        assertEquals("https://artifactory.corp/cargo-virtual", step.getMirrorUrl());
        assertTrue(step.isIsolateCargoHome());
        assertTrue(step.isSccache());
        assertEquals("0.18.0", step.getSccacheVersion());
        assertEquals(".sccache-custom", step.getSccacheDir());
        assertTrue(step.isBinstall());
        assertEquals("1.26.0", step.getBinstallVersion());
        assertEquals("cargo-nextest,cargo-audit", step.getCrates());
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

    @Test
    void testPipelineExecutionWithConfiguredTool(JenkinsRule j) throws Exception {
        File mockHome = j.createTmpDir();
        new File(mockHome, "bin").mkdirs();
        RustInstallation inst = new RustInstallation("GlobalRustTool", mockHome.getAbsolutePath(), Collections.emptyList());
        j.jenkins.getDescriptorByType(RustInstallation.DescriptorImpl.class).setInstallations(inst);

        WorkflowJob p = j.createProject(WorkflowJob.class, "rust-named-tool-test");
        p.setDefinition(new CpsFlowDefinition(
            """
            node {
                withRust(tool: 'GlobalRustTool') {
                    echo "TOOL_PATH=${env.PATH}"
                }
            }
            """, true));

        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("[withRust] Using configured tool installation: GlobalRustTool", b);
        j.assertLogContains("TOOL_PATH=", b);
    }

    @Test
    void testPipelineExecutionWithCustomHome(JenkinsRule j) throws Exception {
        File customDir = j.createTmpDir();
        new File(customDir, "bin").mkdirs();
        String escapedPath = customDir.getAbsolutePath().replace("\\", "/");

        WorkflowJob p = j.createProject(WorkflowJob.class, "rust-custom-home-test");
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
            "    withRust(home: '" + escapedPath + "') {\n" +
            "        echo \"CUSTOM_RUSTC=${env.RUSTC}\"\n" +
            "    }\n" +
            "}\n", true));

        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("[withRust] Using pre-configured Rust home: " + escapedPath, b);
        j.assertLogContains("CUSTOM_RUSTC=", b);
    }
}
