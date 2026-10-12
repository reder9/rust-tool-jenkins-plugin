package io.jenkins.plugins.rust;

import hudson.FilePath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for rust-toolchain.toml and legacy rust-toolchain specification files.
 */
public final class RustToolchainFileParser implements Serializable {

    private static final long serialVersionUID = 1L;

    private RustToolchainFileParser() {}

    public record ToolchainSpec(String channel, List<String> components, List<String> targets, String profile) implements Serializable {}

    /**
     * Parses rust-toolchain.toml or rust-toolchain if either exists in the given directory.
     */
    public static ToolchainSpec parseFromDirectory(FilePath dir) throws IOException, InterruptedException {
        FilePath tomlFile = dir.child("rust-toolchain.toml");
        if (tomlFile.exists()) {
            return parseToml(tomlFile.readToString());
        }

        FilePath legacyFile = dir.child("rust-toolchain");
        if (legacyFile.exists()) {
            String content = legacyFile.readToString().trim();
            // Legacy file could be TOML format or single-line channel name
            if (content.contains("[toolchain]") || content.contains("channel =")) {
                return parseToml(content);
            } else if (!content.isEmpty()) {
                String firstLine = content.split("[\r\n]+")[0].trim();
                return new ToolchainSpec(firstLine, Collections.emptyList(), Collections.emptyList(), null);
            }
        }
        return null;
    }

    /**
     * Parses the TOML string representation of a rust-toolchain specification.
     */
    public static ToolchainSpec parseToml(String tomlContent) throws IOException {
        if (tomlContent == null || tomlContent.isBlank()) {
            return null;
        }

        String channel = null;
        String profile = null;
        List<String> components = new ArrayList<>();
        List<String> targets = new ArrayList<>();

        Pattern channelPattern = Pattern.compile("channel\\s*=\\s*\"([^\"]+)\"");
        Pattern profilePattern = Pattern.compile("profile\\s*=\\s*\"([^\"]+)\"");

        try (BufferedReader reader = new BufferedReader(new StringReader(tomlContent))) {
            String line;
            StringBuilder arrayAccumulator = null;
            String currentArrayField = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }

                if (arrayAccumulator != null) {
                    arrayAccumulator.append(" ").append(line);
                    if (line.contains("]")) {
                        parseArray(currentArrayField, arrayAccumulator.toString(), components, targets);
                        arrayAccumulator = null;
                        currentArrayField = null;
                    }
                    continue;
                }

                Matcher cm = channelPattern.matcher(line);
                if (cm.find()) {
                    channel = cm.group(1);
                    continue;
                }

                Matcher pm = profilePattern.matcher(line);
                if (pm.find()) {
                    profile = pm.group(1);
                    continue;
                }

                if (line.startsWith("components") && line.contains("=")) {
                    if (line.contains("]") && line.contains("[")) {
                        parseArray("components", line, components, targets);
                    } else if (line.contains("[")) {
                        arrayAccumulator = new StringBuilder(line);
                        currentArrayField = "components";
                    }
                } else if (line.startsWith("targets") && line.contains("=")) {
                    if (line.contains("]") && line.contains("[")) {
                        parseArray("targets", line, components, targets);
                    } else if (line.contains("[")) {
                        arrayAccumulator = new StringBuilder(line);
                        currentArrayField = "targets";
                    }
                }
            }
        }

        return new ToolchainSpec(channel, components, targets, profile);
    }

    private static void parseArray(String field, String content, List<String> components, List<String> targets) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            String inside = content.substring(start + 1, end);
            Matcher m = Pattern.compile("\"([^\"]+)\"|'([^']+)'").matcher(inside);
            while (m.find()) {
                String val = m.group(1) != null ? m.group(1) : m.group(2);
                if (val != null && !val.isBlank()) {
                    if ("components".equals(field)) {
                        components.add(val.trim());
                    } else if ("targets".equals(field)) {
                        targets.add(val.trim());
                    }
                }
            }
        }
    }
}
