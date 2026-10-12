package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.ArgumentListBuilder;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Automatically bootstraps rustup and provisions requested toolchains, components, and targets.
 */
public class RustupInstaller extends RustInstaller {

    @DataBoundConstructor
    public RustupInstaller(String toolchain, String mirrorUrl, String components, String targets) {
        super(null, toolchain, mirrorUrl, components, targets);
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {
        FilePath expected = preferredLocation(tool, node);
        expected.mkdirs();

        String marker = toolchain + "|" + components + "|" + targets + "|" + mirrorUrl;
        FilePath markerFile = expected.child(".installed-rustup");
        if (markerFile.exists()) {
            try {
                String existing = markerFile.readToString().trim();
                if (existing.equals(marker)) {
                    log.getLogger().println("[Rust] Toolchain '" + toolchain + "' already installed at " + expected.getRemote());
                    return expected;
                }
            } catch (IOException ignored) {
            }
        }

        log.getLogger().println("[Rust] Bootstrapping Rust toolchain '" + toolchain + "' on " + node.getDisplayName() + "...");

        RustPlatformUtils.PlatformInfo platform = RustPlatformUtils.detect(expected);
        String rustupUrl = RustPlatformUtils.getRustupInitUrl(platform.targetTriple(), platform.binaryExtension());

        FilePath tempDir = expected.child("tmp");
        tempDir.mkdirs();
        FilePath rustupInit = tempDir.child("rustup-init" + platform.binaryExtension());

        log.getLogger().println("[Rust] Downloading rustup-init from " + rustupUrl);
        rustupInit.copyFrom(URI.create(rustupUrl).toURL());
        if (!platform.binaryExtension().equals(".exe")) {
            rustupInit.chmod(0755);
        }

        FilePath cargoHome = expected.child(".cargo");
        FilePath rustupHome = expected.child(".rustup");
        cargoHome.mkdirs();
        rustupHome.mkdirs();

        Map<String, String> env = new HashMap<>();
        env.put("CARGO_HOME", cargoHome.getRemote());
        env.put("RUSTUP_HOME", rustupHome.getRemote());

        Launcher launcher = node.createLauncher(log);

        // 1. Initialize rustup unattended
        ArgumentListBuilder initArgs = new ArgumentListBuilder();
        initArgs.add(rustupInit.getRemote());
        initArgs.add("--no-modify-path");
        initArgs.add("--default-toolchain");
        initArgs.add("none");
        initArgs.add("-y");

        log.getLogger().println("[Rust] Initializing rustup manager...");
        int exitCode = launcher.launch()
                .cmds(initArgs)
                .envs(env)
                .stdout(log.getLogger())
                .stderr(log.getLogger())
                .pwd(expected)
                .join();

        if (exitCode != 0) {
            throw new IOException("Failed to initialize rustup (exit code " + exitCode + ")");
        }

        FilePath rustupBin = cargoHome.child("bin").child("rustup" + platform.binaryExtension());
        if (!rustupBin.exists()) {
            throw new IOException("rustup executable not found at " + rustupBin.getRemote());
        }

        // 2. Install requested toolchain
        log.getLogger().println("[Rust] Installing toolchain '" + toolchain + "'...");
        ArgumentListBuilder toolchainArgs = new ArgumentListBuilder();
        toolchainArgs.add(rustupBin.getRemote());
        toolchainArgs.add("toolchain");
        toolchainArgs.add("install");
        toolchainArgs.add(toolchain);

        exitCode = launcher.launch()
                .cmds(toolchainArgs)
                .envs(env)
                .stdout(log.getLogger())
                .stderr(log.getLogger())
                .pwd(expected)
                .join();

        if (exitCode != 0) {
            throw new IOException("Failed to install toolchain '" + toolchain + "' (exit code " + exitCode + ")");
        }

        // 3. Set default toolchain
        ArgumentListBuilder defaultArgs = new ArgumentListBuilder();
        defaultArgs.add(rustupBin.getRemote());
        defaultArgs.add("default");
        defaultArgs.add(toolchain);
        launcher.launch().cmds(defaultArgs).envs(env).pwd(expected).join();

        // 4. Install components if specified
        for (String comp : getComponentList()) {
            log.getLogger().println("[Rust] Adding component: " + comp);
            ArgumentListBuilder compArgs = new ArgumentListBuilder();
            compArgs.add(rustupBin.getRemote());
            compArgs.add("component");
            compArgs.add("add");
            compArgs.add(comp);
            compArgs.add("--toolchain");
            compArgs.add(toolchain);
            launcher.launch().cmds(compArgs).envs(env).stdout(log.getLogger()).pwd(expected).join();
        }

        // 5. Install targets if specified
        for (String tgt : getTargetList()) {
            log.getLogger().println("[Rust] Adding target: " + tgt);
            ArgumentListBuilder tgtArgs = new ArgumentListBuilder();
            tgtArgs.add(rustupBin.getRemote());
            tgtArgs.add("target");
            tgtArgs.add("add");
            tgtArgs.add(tgt);
            tgtArgs.add("--toolchain");
            tgtArgs.add(toolchain);
            launcher.launch().cmds(tgtArgs).envs(env).stdout(log.getLogger()).pwd(expected).join();
        }

        // 6. Write corporate mirror config if provided
        if (mirrorUrl != null) {
            log.getLogger().println("[Rust] Configuring crates.io mirror: " + mirrorUrl);
            CargoConfigUtils.writeConfig(cargoHome, mirrorUrl);
        }

        // 7. Expose bin directory at expected/bin pointing to cargo/bin
        FilePath targetBin = expected.child("bin");
        targetBin.mkdirs();
        copyBinaries(cargoHome.child("bin"), targetBin, platform.binaryExtension());

        // Cleanup temp
        try {
            tempDir.deleteRecursive();
        } catch (IOException ignored) {
        }

        markerFile.write(marker, "UTF-8");
        log.getLogger().println("[Rust] Successfully installed Rust toolchain '" + toolchain + "' at " + expected.getRemote());
        return expected;
    }

    private void copyBinaries(FilePath srcDir, FilePath dstDir, String ext) throws IOException, InterruptedException {
        String[] binaries = {"rustc", "cargo", "rustup", "rustfmt", "cargo-clippy", "clippy-driver"};
        for (String bin : binaries) {
            FilePath src = srcDir.child(bin + ext);
            if (src.exists()) {
                FilePath dst = dstDir.child(bin + ext);
                src.copyTo(dst);
                if (!ext.equals(".exe")) {
                    dst.chmod(0755);
                }
            }
        }
    }

    @Extension
    @Symbol("rustup")
    public static class DescriptorImpl extends ToolInstallerDescriptor<RustupInstaller> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Install from rustup";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == RustInstallation.class;
        }
    }
}
