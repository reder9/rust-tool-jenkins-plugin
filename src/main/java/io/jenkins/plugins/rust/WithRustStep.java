package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step that provisions and configures Rust and Cargo in the workspace execution context.
 * Supports dynamic toolchains, pre-configured Jenkins tools, host system runtimes,
 * workspace cache isolation, corporate crate mirrors, and CI acceleration tools (sccache, cargo-binstall).
 */
public class WithRustStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private String toolchain = "stable";
    private String tool;
    private String home;
    private boolean system = false;
    private String components;
    private String targets;
    private String mirrorUrl;
    private boolean isolateCargoHome = false;
    private boolean binstall = false;
    private String binstallVersion = "latest";
    private boolean sccache = false;
    private String sccacheVersion = "latest";
    private String sccacheDir;
    private String crates;

    @DataBoundConstructor
    public WithRustStep(String toolchain) {
        this.toolchain = Util.fixEmptyAndTrim(toolchain);
    }

    public String getToolchain() {
        return toolchain;
    }

    @DataBoundSetter
    public void setToolchain(String toolchain) {
        this.toolchain = Util.fixEmptyAndTrim(toolchain);
    }

    public String getTool() {
        return tool;
    }

    @DataBoundSetter
    public void setTool(String tool) {
        this.tool = Util.fixEmptyAndTrim(tool);
    }

    public String getHome() {
        return home;
    }

    @DataBoundSetter
    public void setHome(String home) {
        this.home = Util.fixEmptyAndTrim(home);
    }

    public boolean isSystem() {
        return system;
    }

    @DataBoundSetter
    public void setSystem(boolean system) {
        this.system = system;
    }

    public String getComponents() {
        return components;
    }

    @DataBoundSetter
    public void setComponents(String components) {
        this.components = Util.fixEmptyAndTrim(components);
    }

    public String getTargets() {
        return targets;
    }

    @DataBoundSetter
    public void setTargets(String targets) {
        this.targets = Util.fixEmptyAndTrim(targets);
    }

    public String getMirrorUrl() {
        return mirrorUrl;
    }

    @DataBoundSetter
    public void setMirrorUrl(String mirrorUrl) {
        this.mirrorUrl = Util.fixEmptyAndTrim(mirrorUrl);
    }

    public boolean isIsolateCargoHome() {
        return isolateCargoHome;
    }

    @DataBoundSetter
    public void setIsolateCargoHome(boolean isolateCargoHome) {
        this.isolateCargoHome = isolateCargoHome;
    }

    public boolean isBinstall() {
        return binstall;
    }

    @DataBoundSetter
    public void setBinstall(boolean binstall) {
        this.binstall = binstall;
    }

    public String getBinstallVersion() {
        return binstallVersion;
    }

    @DataBoundSetter
    public void setBinstallVersion(String binstallVersion) {
        this.binstallVersion = Util.fixEmptyAndTrim(binstallVersion);
    }

    public boolean isSccache() {
        return sccache;
    }

    @DataBoundSetter
    public void setSccache(boolean sccache) {
        this.sccache = sccache;
    }

    public String getSccacheVersion() {
        return sccacheVersion;
    }

    @DataBoundSetter
    public void setSccacheVersion(String sccacheVersion) {
        this.sccacheVersion = Util.fixEmptyAndTrim(sccacheVersion);
    }

    public String getSccacheDir() {
        return sccacheDir;
    }

    @DataBoundSetter
    public void setSccacheDir(String sccacheDir) {
        this.sccacheDir = Util.fixEmptyAndTrim(sccacheDir);
    }

    public String getCrates() {
        return crates;
    }

    @DataBoundSetter
    public void setCrates(String crates) {
        this.crates = Util.fixEmptyAndTrim(crates);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new WithRustStepExecution(this, context);
    }

    @Extension
    @Symbol("withRust")
    public static class DescriptorImpl extends StepDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Set up Rust and Cargo environment";
        }

        @Override
        public String getFunctionName() {
            return "withRust";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            context.add(TaskListener.class);
            context.add(Node.class);
            context.add(FilePath.class);
            context.add(Launcher.class);
            context.add(EnvVars.class);
            return context;
        }
    }
}
