package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.security.MasterToSlaveCallable;

import java.io.File;
import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility for remotely detecting host system Rust runtimes (rustc, cargo, rustup)
 * on Jenkins controller or agent nodes.
 */
public final class RustProbeUtils implements Serializable {

    private static final long serialVersionUID = 1L;

    private RustProbeUtils() {}

    public static class SystemRustInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String home;
        private final String binDir;
        private final String rustc;
        private final String cargo;
        private final String rustup;
        private final boolean isMusl;

        public SystemRustInfo(String home, String binDir, String rustc, String cargo, String rustup, boolean isMusl) {
            this.home = home;
            this.binDir = binDir;
            this.rustc = rustc;
            this.cargo = cargo;
            this.rustup = rustup;
            this.isMusl = isMusl;
        }

        public String getHome() {
            return home;
        }

        public String getBinDir() {
            return binDir;
        }

        public String getRustc() {
            return rustc;
        }

        public String getCargo() {
            return cargo;
        }

        public String getRustup() {
            return rustup;
        }

        public boolean isMusl() {
            return isMusl;
        }
    }

    /**
     * Probes the node for an existing system Rust toolchain (cargo and rustc).
     */
    public static SystemRustInfo probeSystemRust(Node node, Launcher launcher, TaskListener listener) {
        VirtualChannel channel = node != null ? node.getChannel() : null;
        if (channel != null) {
            try {
                return channel.call(new ProbeSystemRustCallable());
            } catch (Exception e) {
                if (listener != null) {
                    listener.getLogger().println("[Rust Plugin] Remote system Rust probe failed: " + e.getMessage());
                }
            }
        }
        return new ProbeSystemRustCallable().call();
    }

    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Probing standard platform paths for Rust toolchain")
    private static class ProbeSystemRustCallable extends MasterToSlaveCallable<SystemRustInfo, RuntimeException> {
        private static final long serialVersionUID = 1L;

        @Override
        public SystemRustInfo call() {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");
            boolean isMusl = new File("/etc/alpine-release").exists()
                    || new File("/lib/ld-musl-x86_64.so.1").exists()
                    || new File("/lib/ld-musl-aarch64.so.1").exists();

            String ext = isWindows ? ".exe" : "";
            String pathEnv = System.getenv("PATH");

            // 1. Search existing PATH
            if (pathEnv != null) {
                String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));
                for (String dir : dirs) {
                    File d = new File(dir);
                    if (!d.isDirectory()) {
                        continue;
                    }
                    File cargo = new File(d, "cargo" + ext);
                    File rustc = new File(d, "rustc" + ext);
                    if (cargo.isFile() && (isWindows || cargo.canExecute())
                            && rustc.isFile() && (isWindows || rustc.canExecute())) {
                        File rustup = new File(d, "rustup" + ext);
                        String home = resolveHomeFromBin(d);
                        return new SystemRustInfo(
                                home,
                                d.getAbsolutePath(),
                                rustc.getAbsolutePath(),
                                cargo.getAbsolutePath(),
                                rustup.isFile() ? rustup.getAbsolutePath() : null,
                                isMusl
                        );
                    }
                }
            }

            // 2. Standard location heuristics if PATH doesn't contain cargo/rustc
            if (isWindows) {
                String userProfile = System.getenv("USERPROFILE");
                String localAppData = System.getenv("LOCALAPPDATA");
                String[] winCandidates = {
                        userProfile != null ? userProfile + "\\.cargo\\bin" : null,
                        localAppData != null ? localAppData + "\\.cargo\\bin" : null,
                        "C:\\Users\\" + System.getProperty("user.name", "") + "\\.cargo\\bin",
                        "C:\\Program Files\\Rust\\bin",
                        "C:\\Rust\\bin"
                };
                for (String candidate : winCandidates) {
                    if (candidate == null) continue;
                    File d = new File(candidate);
                    if (d.isDirectory()) {
                        File cargo = new File(d, "cargo.exe");
                        File rustc = new File(d, "rustc.exe");
                        if (cargo.isFile() && rustc.isFile()) {
                            File rustup = new File(d, "rustup.exe");
                            String home = resolveHomeFromBin(d);
                            return new SystemRustInfo(
                                    home,
                                    d.getAbsolutePath(),
                                    rustc.getAbsolutePath(),
                                    cargo.getAbsolutePath(),
                                    rustup.isFile() ? rustup.getAbsolutePath() : null,
                                    false
                            );
                        }
                    }
                }
            } else {
                String userHome = System.getProperty("user.home", "");
                String[] unixCandidates = {
                        userHome + "/.cargo/bin",
                        "/usr/local/cargo/bin",
                        "/usr/bin",
                        "/usr/local/bin",
                        "/opt/cargo/bin"
                };
                for (String candidate : unixCandidates) {
                    File d = new File(candidate);
                    if (d.isDirectory()) {
                        File cargo = new File(d, "cargo");
                        File rustc = new File(d, "rustc");
                        if (cargo.isFile() && cargo.canExecute() && rustc.isFile() && rustc.canExecute()) {
                            File rustup = new File(d, "rustup");
                            String home = resolveHomeFromBin(d);
                            return new SystemRustInfo(
                                    home,
                                    d.getAbsolutePath(),
                                    rustc.getAbsolutePath(),
                                    cargo.getAbsolutePath(),
                                    rustup.isFile() ? rustup.getAbsolutePath() : null,
                                    isMusl
                            );
                        }
                    }
                }
            }

            return null;
        }

        private String resolveHomeFromBin(File binDir) {
            if ("bin".equals(binDir.getName())) {
                File parent = binDir.getParentFile();
                return parent != null ? parent.getAbsolutePath() : binDir.getAbsolutePath();
            }
            return binDir.getAbsolutePath();
        }
    }
}
