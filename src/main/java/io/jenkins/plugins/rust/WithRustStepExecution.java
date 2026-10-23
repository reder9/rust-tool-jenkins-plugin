package io.jenkins.plugins.rust;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.InstallSourceProperty;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Step execution handler for the withRust pipeline step.
 */
public class WithRustStepExecution extends StepExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private final WithRustStep step;

    public WithRustStepExecution(WithRustStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        StepContext context = getContext();
        TaskListener listener = context.get(TaskListener.class);
        EnvVars currentEnv = context.get(EnvVars.class);
        FilePath workspace = context.get(FilePath.class);
        Launcher launcher = context.get(Launcher.class);
        Node node = context.get(Node.class);

        if (listener == null || workspace == null) {
            throw new IllegalStateException("Missing required execution context (listener or workspace)");
        }
        if (node == null) {
            node = Jenkins.get();
        }
        if (launcher == null && node != null) {
            launcher = node.createLauncher(listener);
        }
        if (currentEnv == null) {
            currentEnv = new EnvVars();
        }

        Map<String, String> additions = new HashMap<>();
        Set<String> deletions = new HashSet<>();

        // 1. Resolve Rust toolchain / environment
        if (step.getHome() != null && !step.getHome().isBlank()) {
            String baseHome = step.getHome().trim();
            listener.getLogger().println("[withRust] Using pre-configured Rust home: " + baseHome);
            RustInstallation installation = new RustInstallation("CustomRust", baseHome, Collections.emptyList());
            RustInstallation translated = installation.forNode(node, listener).forEnvironment(currentEnv);
            EnvVars toolEnv = new EnvVars();
            translated.buildEnvVars(toolEnv);
            additions.putAll(toolEnv);
        } else if (step.isSystem()) {
            RustProbeUtils.SystemRustInfo sysRust = RustProbeUtils.probeSystemRust(node, launcher, listener);
            if (sysRust == null) {
                throw new AbortException("[withRust] 'system: true' specified, but no host system Rust runtime (cargo/rustc) was found on this agent.");
            }
            listener.getLogger().println("[withRust] Using host system Rust: rustc=" + sysRust.getRustc()
                    + " cargo=" + sysRust.getCargo() + " (home: " + sysRust.getHome() + ")");

            additions.put("PATH", sysRust.getBinDir());
            additions.put("RUSTC", sysRust.getRustc());
            additions.put("CARGO", sysRust.getCargo());
            if (sysRust.getRustup() != null) {
                additions.put("RUSTUP", sysRust.getRustup());
            }
            if (sysRust.getHome() != null && !sysRust.getHome().isBlank()) {
                if (!currentEnv.containsKey("CARGO_HOME")) {
                    additions.put("CARGO_HOME", sysRust.getHome());
                }
            }
        } else if (step.getTool() != null && !step.getTool().isBlank()) {
            String toolName = step.getTool().trim();
            RustInstallation installation = findInstallation(toolName);
            if (installation == null) {
                throw new AbortException("[withRust] No configured Rust tool installation found matching name: '" + toolName + "'");
            }
            listener.getLogger().println("[withRust] Using configured tool installation: " + toolName);
            RustInstallation translated = installation.forNode(node, listener).forEnvironment(currentEnv);
            EnvVars toolEnv = new EnvVars();
            translated.buildEnvVars(toolEnv);
            additions.putAll(toolEnv);
        } else {
            // Standard / dynamic toolchain
            String effectiveToolchain = step.getToolchain();
            String effectiveComponents = step.getComponents();
            String effectiveTargets = step.getTargets();

            // Auto-detect from rust-toolchain.toml or rust-toolchain if toolchain is blank or "auto"
            if (effectiveToolchain == null || effectiveToolchain.isBlank() || "auto".equalsIgnoreCase(effectiveToolchain.trim())) {
                RustToolchainFileParser.ToolchainSpec spec = RustToolchainFileParser.parseFromDirectory(workspace);
                if (spec != null) {
                    if (spec.channel() != null && !spec.channel().isBlank()) {
                        effectiveToolchain = spec.channel().trim();
                        listener.getLogger().println("[withRust] Auto-detected toolchain '" + effectiveToolchain + "' from rust-toolchain file");
                    }
                    if (!spec.components().isEmpty() && (effectiveComponents == null || effectiveComponents.isBlank())) {
                        effectiveComponents = String.join(",", spec.components());
                        listener.getLogger().println("[withRust] Auto-detected components from rust-toolchain file: " + effectiveComponents);
                    }
                    if (!spec.targets().isEmpty() && (effectiveTargets == null || effectiveTargets.isBlank())) {
                        effectiveTargets = String.join(",", spec.targets());
                        listener.getLogger().println("[withRust] Auto-detected targets from rust-toolchain file: " + effectiveTargets);
                    }
                }
            }

            if (effectiveToolchain == null || effectiveToolchain.isBlank() || "auto".equalsIgnoreCase(effectiveToolchain.trim())) {
                effectiveToolchain = "stable";
            } else {
                effectiveToolchain = effectiveToolchain.trim();
            }

            RustInstallation installation = findInstallation(effectiveToolchain);
            if (installation == null) {
                listener.getLogger().println("[withRust] Dynamic toolchain requested: " + effectiveToolchain);
                String toolName = "rust-" + effectiveToolchain.replaceAll("[^a-zA-Z0-9.-]", "_");
                RustupInstaller installer = new RustupInstaller(
                        effectiveToolchain,
                        step.getMirrorUrl(),
                        effectiveComponents,
                        effectiveTargets);
                installation = new RustInstallation(
                        toolName,
                        null,
                        Collections.singletonList(new InstallSourceProperty(Collections.singletonList(installer))));
            }

            RustInstallation nodeInstallation = installation.forNode(node, listener);
            nodeInstallation = nodeInstallation.forEnvironment(currentEnv);
            EnvVars toolEnv = new EnvVars();
            nodeInstallation.buildEnvVars(toolEnv);
            additions.putAll(toolEnv);
        }

        // 2. Workspace-isolated CARGO_HOME if requested
        if (step.isIsolateCargoHome()) {
            FilePath cargoHome = workspace.child(".cargo");
            cargoHome.mkdirs();
            additions.put("CARGO_HOME", cargoHome.getRemote());
            additions.put("PATH+CARGO_LOCAL", cargoHome.child("bin").getRemote());

            if (step.getMirrorUrl() != null && !step.getMirrorUrl().isBlank()) {
                CargoConfigUtils.writeConfig(workspace, step.getMirrorUrl().trim());
            }
        } else if (step.getMirrorUrl() != null && !step.getMirrorUrl().isBlank()) {
            CargoConfigUtils.writeConfig(workspace, step.getMirrorUrl().trim());
        }

        // 3. Mozilla sccache shared compilation cache support
        if (step.isSccache()) {
            FilePath sccacheInstallDir = SccacheInstaller.provisionSccache(
                    node, workspace, listener, step.getSccacheVersion(), step.getMirrorUrl());
            additions.put("PATH+SCCACHE", sccacheInstallDir.getRemote());
            additions.put("RUSTC_WRAPPER", "sccache");

            String resolvedSccacheDir;
            if (step.getSccacheDir() != null && !step.getSccacheDir().isBlank()) {
                String raw = step.getSccacheDir().trim();
                if (!raw.startsWith("/") && !raw.contains(":")) {
                    FilePath customDir = workspace.child(raw);
                    customDir.mkdirs();
                    resolvedSccacheDir = customDir.getRemote();
                } else {
                    resolvedSccacheDir = raw;
                }
            } else {
                FilePath agentRootDir = node.getRootPath() != null ? node.getRootPath() : workspace;
                FilePath defaultCache = agentRootDir.child("caches").child("sccache");
                defaultCache.mkdirs();
                resolvedSccacheDir = defaultCache.getRemote();
            }
            additions.put("SCCACHE_DIR", resolvedSccacheDir);
            listener.getLogger().println("[withRust] Configured sccache: RUSTC_WRAPPER=sccache (SCCACHE_DIR=" + resolvedSccacheDir + ")");
        }

        // 4. cargo-binstall fast binary crate tool support
        if (step.isBinstall()) {
            FilePath binstallDir = CargoBinstallInstaller.provisionBinstall(
                    node, workspace, listener, step.getBinstallVersion(), step.getMirrorUrl());
            additions.put("PATH+BINSTALL", binstallDir.getRemote());
            listener.getLogger().println("[withRust] Configured cargo-binstall: " + binstallDir.getRemote());
        }

        // 5. Automatic tool crate provisioning (e.g. cargo-nextest, cargo-audit)
        if (step.getCrates() != null && !step.getCrates().isBlank()) {
            installCrates(step, node, workspace, launcher, listener, currentEnv, additions, deletions);
        }

        // 6. Launch execution body with merged environment
        EnvironmentExpander expander = EnvironmentExpander.merge(
                context.get(EnvironmentExpander.class),
                new RustEnvironmentExpander(additions, deletions)
        );

        context.newBodyInvoker()
                .withContext(expander)
                .withCallback(BodyExecutionCallback.wrap(context))
                .start();

        return false;
    }

    private void installCrates(WithRustStep step, Node node, FilePath workspace, Launcher launcher,
                              TaskListener listener, EnvVars currentEnv, Map<String, String> additions,
                              Set<String> deletions)
            throws IOException, InterruptedException {
        String cratesSpec = step.getCrates();
        if (cratesSpec == null || cratesSpec.isBlank()) {
            return;
        }

        String[] crates = cratesSpec.split("[,\\s]+");
        boolean hasBinstall = step.isBinstall() || additions.containsKey("PATH+BINSTALL");

        EnvVars execEnv = new EnvVars(currentEnv);
        RustEnvironmentExpander expander = new RustEnvironmentExpander(additions, deletions);
        expander.expand(execEnv);

        String cargoExe = additions.getOrDefault("CARGO", "cargo");

        for (String rawCrate : crates) {
            String crate = rawCrate.trim();
            if (crate.isEmpty()) {
                continue;
            }

            ArgumentListBuilder cmd = new ArgumentListBuilder();
            cmd.add(cargoExe);
            if (hasBinstall) {
                cmd.add("binstall", "--no-confirm", crate);
                listener.getLogger().println("[withRust] Installing crate via cargo-binstall: " + crate);
            } else {
                cmd.add("install", crate);
                listener.getLogger().println("[withRust] Installing crate via cargo install: " + crate);
            }

            Launcher.ProcStarter ps = launcher.launch()
                    .cmds(cmd)
                    .envs(execEnv)
                    .pwd(workspace);
            if (listener != null) {
                ps.stdout(listener.getLogger()).stderr(listener.getLogger());
            }

            int exitCode = ps.join();
            if (exitCode != 0) {
                throw new AbortException("[withRust] Failed to install crate '" + crate + "' (exit code " + exitCode + ")");
            }
            listener.getLogger().println("[withRust] Successfully installed crate: " + crate);
        }
    }

    static RustInstallation findInstallation(String toolchainOrName) {
        if (toolchainOrName == null || toolchainOrName.isBlank()) {
            return null;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        RustInstallation.DescriptorImpl desc = jenkins.getDescriptorByType(RustInstallation.DescriptorImpl.class);
        for (RustInstallation inst : desc.getInstallations()) {
            if (inst.getName().equalsIgnoreCase(toolchainOrName.trim())) {
                return inst;
            }
        }
        return null;
    }

    private static class RustEnvironmentExpander extends EnvironmentExpander implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Map<String, String> additions;
        private final Set<String> deletions;

        public RustEnvironmentExpander(Map<String, String> additions, Set<String> deletions) {
            this.additions = new HashMap<>(additions);
            this.deletions = new HashSet<>(deletions);
        }

        @Override
        public void expand(EnvVars env) {
            for (String del : deletions) {
                env.remove(del);
            }
            for (Map.Entry<String, String> entry : additions.entrySet()) {
                String key = entry.getKey();
                if (deletions.contains(key)) {
                    continue;
                }
                String val = entry.getValue();
                if ("PATH".equalsIgnoreCase(key)) {
                    String existing = env.get("PATH");
                    if (existing != null && !existing.isEmpty()) {
                        boolean isWin = val.contains(";") || existing.contains(";");
                        String sep = isWin ? ";" : ":";
                        env.put("PATH", val + sep + existing);
                    } else {
                        env.put("PATH", val);
                    }
                } else if (key.startsWith("PATH+")) {
                    String existing = env.get("PATH");
                    if (existing != null && !existing.isEmpty()) {
                        boolean isWin = val.contains(";") || existing.contains(";");
                        String sep = isWin ? ";" : ":";
                        env.put("PATH", val + sep + existing);
                    } else {
                        env.put("PATH", val);
                    }
                    env.put(key, val);
                } else {
                    env.put(key, val);
                }
            }
        }
    }
}
