package io.jenkins.plugins.rust;

import hudson.tools.ToolInstaller;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Base installer class for Rust toolchains.
 */
public abstract class RustInstaller extends ToolInstaller {

    protected final String toolchain;
    protected final String mirrorUrl;
    protected final String components;
    protected final String targets;

    protected RustInstaller(String label, String toolchain, String mirrorUrl, String components, String targets) {
        super(label);
        this.toolchain = (toolchain != null && !toolchain.isBlank()) ? toolchain.trim() : "stable";
        this.mirrorUrl = (mirrorUrl != null && !mirrorUrl.isBlank()) ? mirrorUrl.trim() : null;
        this.components = (components != null && !components.isBlank()) ? components.trim() : null;
        this.targets = (targets != null && !targets.isBlank()) ? targets.trim() : null;
    }

    public String getToolchain() {
        return toolchain;
    }

    public String getMirrorUrl() {
        return mirrorUrl;
    }

    public String getComponents() {
        return components;
    }

    public String getTargets() {
        return targets;
    }

    public List<String> getComponentList() {
        if (components == null || components.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(components.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public List<String> getTargetList() {
        if (targets == null || targets.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(targets.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
