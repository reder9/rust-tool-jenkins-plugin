package io.jenkins.plugins.rust;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Locale;
import jenkins.MasterToSlaveFileCallable;

/**
 * Utility for detecting remote agent platform, CPU architecture, and target triples for Rust toolchains.
 */
public final class RustPlatformUtils implements Serializable {

    private static final long serialVersionUID = 1L;

    private RustPlatformUtils() {}

    public record PlatformInfo(String os, String arch, String targetTriple, String binaryExtension) implements Serializable {}

    public static PlatformInfo detect(FilePath workspace) throws IOException, InterruptedException {
        return workspace.act(new DetectPlatformCallable());
    }

    private static class DetectPlatformCallable extends MasterToSlaveFileCallable<PlatformInfo> {
        private static final long serialVersionUID = 1L;

        @Override
        public PlatformInfo invoke(File f, VirtualChannel channel) {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
            String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);

            String os;
            String ext = "";
            if (osName.contains("win")) {
                os = "windows";
                ext = ".exe";
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                os = "darwin";
            } else {
                os = "linux";
            }

            String arch;
            if (osArch.equals("amd64") || osArch.equals("x86_64")) {
                arch = "x86_64";
            } else if (osArch.equals("aarch64") || osArch.equals("arm64")) {
                arch = "aarch64";
            } else if (osArch.startsWith("arm")) {
                arch = "armv7";
            } else if (osArch.contains("riscv64")) {
                arch = "riscv64gc";
            } else {
                arch = "x86_64";
            }

            String triple = resolveTriple(os, arch);
            return new PlatformInfo(os, arch, triple, ext);
        }
    }

    public static String resolveTriple(String os, String arch) {
        if ("windows".equals(os)) {
            return arch + "-pc-windows-msvc";
        } else if ("darwin".equals(os)) {
            return arch + "-apple-darwin";
        } else {
            // Linux default
            return arch + "-unknown-linux-gnu";
        }
    }

    public static String getRustupInitUrl(String targetTriple, String ext) {
        return "https://static.rust-lang.org/rustup/dist/" + targetTriple + "/rustup-init" + ext;
    }

    public static String getStandaloneArchiveUrl(String version, String targetTriple) {
        String archiveExt = targetTriple.contains("windows") ? ".zip" : ".tar.gz";
        return "https://static.rust-lang.org/dist/rust-" + version + "-" + targetTriple + archiveExt;
    }
}
