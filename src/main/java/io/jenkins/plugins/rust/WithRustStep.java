package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step that provisions and configures Rust and Cargo in the workspace execution context.
 */
public class WithRustStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private String toolchain = "stable";
    private String components;
    private String targets;
    private String mirrorUrl;
    private boolean isolateCargoHome = false;

    @DataBoundConstructor
    public WithRustStep(String toolchain) {
        if (toolchain != null && !toolchain.isBlank()) {
            this.toolchain = toolchain.trim();
        }
    }

    public String getToolchain() {
        return toolchain;
    }

    @DataBoundSetter
    public void setToolchain(String toolchain) {
        if (toolchain != null && !toolchain.isBlank()) {
            this.toolchain = toolchain.trim();
        }
    }

    public String getComponents() {
        return components;
    }

    @DataBoundSetter
    public void setComponents(String components) {
        this.components = components;
    }

    public String getTargets() {
        return targets;
    }

    @DataBoundSetter
    public void setTargets(String targets) {
        this.targets = targets;
    }

    public String getMirrorUrl() {
        return mirrorUrl;
    }

    @DataBoundSetter
    public void setMirrorUrl(String mirrorUrl) {
        this.mirrorUrl = mirrorUrl;
    }

    public boolean isIsolateCargoHome() {
        return isolateCargoHome;
    }

    @DataBoundSetter
    public void setIsolateCargoHome(boolean isolateCargoHome) {
        this.isolateCargoHome = isolateCargoHome;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, this);
    }

    private static class Execution extends GeneralNonBlockingStepExecution {

        private static final long serialVersionUID = 1L;

        private final WithRustStep step;

        Execution(StepContext context, WithRustStep step) {
            super(context);
            this.step = step;
        }

        @Override
        public boolean start() throws Exception {
            run(this::execute);
            return false;
        }

        private void execute() throws Exception {
            StepContext context = getContext();
            TaskListener listener = context.get(TaskListener.class);
            Node node = context.get(Node.class);
            FilePath workspace = context.get(FilePath.class);
            EnvVars currentEnv = context.get(EnvVars.class);

            if (listener == null || node == null || workspace == null || currentEnv == null) {
                throw new IllegalStateException("Missing required execution context (listener, node, workspace, or env)");
            }

            RustInstallation installation = findInstallation(step.getToolchain());
            if (installation == null) {
                // Provision dynamically on the fly
                listener.getLogger().println("[withRust] Dynamic toolchain requested: " + step.getToolchain());
                String toolName = "rust-" + step.getToolchain().replaceAll("[^a-zA-Z0-9.-]", "_");
                RustupInstaller installer = new RustupInstaller(
                        step.getToolchain(),
                        step.getMirrorUrl(),
                        step.getComponents(),
                        step.getTargets());
                installation = new RustInstallation(toolName, null, Collections.singletonList(new hudson.tools.InstallSourceProperty(Collections.singletonList(installer))));
            }

            RustInstallation nodeInstallation = installation.forNode(node, listener);
            nodeInstallation = nodeInstallation.forEnvironment(currentEnv);

            EnvVars overrides = new EnvVars();
            nodeInstallation.buildEnvVars(overrides);

            // Workspace-isolated CARGO_HOME if requested
            if (step.isIsolateCargoHome()) {
                FilePath cargoHome = workspace.child(".cargo");
                cargoHome.mkdirs();
                overrides.put("CARGO_HOME", cargoHome.getRemote());
                overrides.put("PATH+CARGO_LOCAL", cargoHome.child("bin").getRemote());
                if (step.getMirrorUrl() != null) {
                    CargoConfigUtils.writeConfig(workspace, step.getMirrorUrl());
                }
            } else if (step.getMirrorUrl() != null) {
                CargoConfigUtils.writeConfig(workspace, step.getMirrorUrl());
            }

            context.newBodyInvoker()
                    .withContext(overrides)
                    .withCallback(BodyExecutionCallback.wrap(context))
                    .start();
        }

        private RustInstallation findInstallation(String toolchainOrName) {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return null;
            }
            RustInstallation.DescriptorImpl desc = jenkins.getDescriptorByType(RustInstallation.DescriptorImpl.class);
            for (RustInstallation inst : desc.getInstallations()) {
                if (inst.getName().equalsIgnoreCase(toolchainOrName)) {
                    return inst;
                }
            }
            return null;
        }
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
