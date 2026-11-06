package io.jenkins.plugins.rust;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import jenkins.security.MasterToSlaveCallable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Utility for detecting agent platform, CPU architecture, and target triples for Rust toolchains.
 * Directly queries controller-managed Computer system properties and remote agents,
 * properly supporting Linux musl (Alpine) as well as glibc targets.
 */
public final class RustPlatformUtils implements Serializable {

    private static final long serialVersionUID = 1L;

    private RustPlatformUtils() {}

    public record PlatformInfo(String os, String arch, String targetTriple, String binaryExtension, boolean isMusl) implements Serializable {
        public PlatformInfo(String os, String arch, String targetTriple, String binaryExtension) {
            this(os, arch, targetTriple, binaryExtension, targetTriple != null && targetTriple.contains("-musl"));
        }
    }

    public static PlatformInfo detect(Node node) {
        String osName = null;
        String osArch = null;
        boolean isMusl = false;

        Computer computer = node != null ? node.toComputer() : null;
        if (computer != null) {
            try {
                Map<?, ?> props = computer.getSystemProperties();
                if (props != null) {
                    Object nameObj = props.get("os.name");
                    Object archObj = props.get("os.arch");
                    Object verObj = props.get("os.version");
                    if (nameObj != null) {
                        osName = nameObj.toString();
                    }
                    if (archObj != null) {
                        osArch = archObj.toString();
                    }
                    if (verObj != null && verObj.toString().toLowerCase(Locale.ENGLISH).contains("alpine")) {
                        isMusl = true;
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

        if (osName.toLowerCase(Locale.ENGLISH).contains("alpine") || osName.toLowerCase(Locale.ENGLISH).contains("musl")) {
            isMusl = true;
        }

        if (!isMusl && node != null) {
            VirtualChannel channel = node.getChannel();
            if (channel != null) {
                try {
                    isMusl = Boolean.TRUE.equals(channel.call(new MuslProbeCallable()));
                } catch (IOException | InterruptedException | RuntimeException ignored) {
                    // Fall back to default libc detection
                }
            }
        }

        return parsePlatform(osName, osArch, isMusl);
    }

    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Probing standard musl libc platform paths")
    public static PlatformInfo detect(FilePath workspace) {
        boolean isMusl = new File("/etc/alpine-release").exists()
                || new File("/lib/ld-musl-x86_64.so.1").exists()
                || new File("/lib/ld-musl-aarch64.so.1").exists();
        return parsePlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), isMusl);
    }

    public static PlatformInfo parsePlatform(String rawOs, String rawArch) {
        boolean isMusl = rawOs != null && (rawOs.toLowerCase(Locale.ENGLISH).contains("alpine") || rawOs.toLowerCase(Locale.ENGLISH).contains("musl"));
        return parsePlatform(rawOs, rawArch, isMusl);
    }

    public static PlatformInfo parsePlatform(String rawOs, String rawArch, boolean isMusl) {
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

        String triple = resolveTriple(os, arch, isMusl);
        return new PlatformInfo(os, arch, triple, ext, isMusl);
    }

    public static String resolveTriple(String os, String arch) {
        return resolveTriple(os, arch, false);
    }

    public static String resolveTriple(String os, String arch, boolean isMusl) {
        if ("windows".equals(os)) {
            return arch + "-pc-windows-msvc";
        } else if ("darwin".equals(os)) {
            return arch + "-apple-darwin";
        } else {
            // Linux target triples (musl vs gnu)
            if (isMusl) {
                if ("armv7".equals(arch)) {
                    return "armv7-unknown-linux-musleabihf";
                }
                return arch + "-unknown-linux-musl";
            }
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

    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Probing standard musl libc platform paths")
    private static class MuslProbeCallable extends MasterToSlaveCallable<Boolean, RuntimeException> {
        private static final long serialVersionUID = 1L;

        @Override
        public Boolean call() {
            return new File("/etc/alpine-release").exists()
                    || new File("/lib/ld-musl-x86_64.so.1").exists()
                    || new File("/lib/ld-musl-aarch64.so.1").exists();
        }
    }
}
