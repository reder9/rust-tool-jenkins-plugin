package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Represents a Rust toolchain installation containing rustc, cargo, and rustup.
 */
public class RustInstallation extends ToolInstallation
        implements NodeSpecific<RustInstallation>, EnvironmentSpecific<RustInstallation> {

    private static final long serialVersionUID = 1L;

    @DataBoundConstructor
    public RustInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public RustInstallation forNode(@NonNull Node node, TaskListener log) throws IOException, InterruptedException {
        return new RustInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public RustInstallation forEnvironment(EnvVars environment) {
        return new RustInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home != null && !home.isEmpty()) {
            // Binaries can live in $HOME/bin or $HOME/.cargo/bin
            String binDir = home + File.separator + "bin";
            String cargoBinDir = home + File.separator + ".cargo" + File.separator + "bin";

            env.put("PATH+RUST", binDir);
            env.put("PATH+CARGO", cargoBinDir);

            if (!env.containsKey("CARGO_HOME")) {
                env.put("CARGO_HOME", home + File.separator + ".cargo");
            }
            if (!env.containsKey("RUSTUP_HOME")) {
                env.put("RUSTUP_HOME", home + File.separator + ".rustup");
            }

            env.put("RUSTC", binDir + File.separator + "rustc");
            env.put("CARGO", binDir + File.separator + "cargo");
        }
    }

    public FilePath getExecutable(String binaryName, Launcher launcher) {
        String home = getHome();
        if (home == null || home.isEmpty()) {
            return null;
        }
        boolean isWin = launcher.isUnix() ? false : true;
        String ext = isWin ? ".exe" : "";
        String fullName = binaryName + ext;

        FilePath homeDir = new FilePath(launcher.getChannel(), home);
        FilePath binFile = homeDir.child("bin").child(fullName);
        try {
            if (binFile.exists()) {
                return binFile;
            }
            FilePath cargoBinFile = homeDir.child(".cargo").child("bin").child(fullName);
            if (cargoBinFile.exists()) {
                return cargoBinFile;
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return binFile;
    }

    @Extension
    @Symbol("rust")
    public static class DescriptorImpl extends ToolDescriptor<RustInstallation> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Rust";
        }

        @Override
        public List<? extends RustInstaller> getDefaultInstallers() {
            return Collections.singletonList(new RustupInstaller("stable", null, null, null));
        }

        @Override
        public RustInstallation[] getInstallations() {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                return jenkins.getDescriptorByType(DescriptorImpl.class).getInstallations();
            }
            return new RustInstallation[0];
        }

        @Override
        public void setInstallations(RustInstallation... installations) {
            super.setInstallations(installations);
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Name is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckHome(@QueryParameter String value) {
            if (value != null && !value.trim().isEmpty()) {
                File f = new File(value);
                if (!f.isDirectory()) {
                    return FormValidation.warning("Specified directory does not exist or is not a directory on controller");
                }
            }
            return FormValidation.ok();
        }
    }
}
