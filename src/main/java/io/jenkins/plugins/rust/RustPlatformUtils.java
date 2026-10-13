package io.jenkins.plugins.rust;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Utility for detecting agent platform, CPU architecture, and target triples for Rust toolchains.
 * Directly queries controller-managed Computer system properties rather than remote remoting callables,
 * and fails early on unsupported platforms.
 */
public final class RustPlatformUtils implements Serializable {

    private static final long serialVersionUID = 1L;

    private RustPlatformUtils() {}

    public record PlatformInfo(String os, String arch, String targetTriple, String binaryExtension) implements Serializable {}

    public static PlatformInfo detect(Node node) {
        String osName = null;
        String osArch = null;

        Computer computer = node != null ? node.toComputer() : null;
        if (computer != null) {
            try {
                Map<?, ?> props = computer.getSystemProperties();
                if (props != null) {
                    Object nameObj = props.get("os.name");
                    Object archObj = props.get("os.arch");
                    if (nameObj != null) {
                        osName = nameObj.toString();
                    }
                    if (archObj != null) {
                        osArch = archObj.toString();
                    }
                }
            } catch (java.io.IOException | InterruptedException ignored) {
                // Fall back to System.getProperty
            }
        }

        if (osName == null) {
            osName = System.getProperty("os.name", "");
        }
        if (osArch == null) {
            osArch = System.getProperty("os.arch", "");
        }

        return parsePlatform(osName, osArch);
    }

    public static PlatformInfo detect(FilePath workspace) {
        return parsePlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static PlatformInfo parsePlatform(String rawOs, String rawArch) {
        String osName = rawOs != null ? rawOs.toLowerCase(Locale.ENGLISH) : "";
        String osArch = rawArch != null ? rawArch.toLowerCase(Locale.ENGLISH) : "";

        String os;
        String ext = "";
        if (osName.contains("win")) {
            os = "windows";
            ext = ".exe";
        } else if (osName.contains("mac") || osName.contains("darwin") || osName.contains("osx")) {
            os = "darwin";
        } else if (osName.contains("linux") || osName.contains("unix") || osName.contains("ubuntu")
                || osName.contains("debian") || osName.contains("fedora") || osName.contains("centos")
                || osName.contains("rhel") || osName.contains("alpine")) {
            os = "linux";
        } else {
            throw new IllegalArgumentException("Unsupported or unrecognized operating system: '" + rawOs + "'. Supported: Linux, macOS, Windows.");
        }

        String arch;
        if (osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64")) {
            arch = "x86_64";
        } else if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            arch = "aarch64";
        } else if (osArch.contains("armv7") || osArch.contains("armhf") || osArch.equals("arm")) {
            arch = "armv7";
        } else if (osArch.contains("ppc64le") || osArch.contains("powerpc64le") || osArch.contains("ppc64el")) {
            arch = "powerpc64le";
        } else if (osArch.contains("s390x") || osArch.contains("ibm390x")) {
            arch = "s390x";
        } else if (osArch.contains("riscv64")) {
            arch = "riscv64gc";
        } else if (osArch.equals("x86") || osArch.equals("i386") || osArch.equals("i686")) {
            arch = "i686";
        } else {
            throw new IllegalArgumentException("Unsupported CPU architecture: '" + rawArch + "' for Rust toolchain on " + os);
        }

        String triple = resolveTriple(os, arch);
        return new PlatformInfo(os, arch, triple, ext);
    }

    public static String resolveTriple(String os, String arch) {
        if ("windows".equals(os)) {
            return arch + "-pc-windows-msvc";
        } else if ("darwin".equals(os)) {
            return arch + "-apple-darwin";
        } else {
            // Linux target triples
            if ("armv7".equals(arch)) {
                return "armv7-unknown-linux-gnueabihf";
            }
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
