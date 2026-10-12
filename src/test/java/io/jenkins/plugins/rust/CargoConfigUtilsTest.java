package io.jenkins.plugins.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CargoConfigUtilsTest {

    @Test
    void testGenerateMirrorConfigEmpty() {
        assertEquals("", CargoConfigUtils.generateMirrorConfig(null));
        assertEquals("", CargoConfigUtils.generateMirrorConfig("   "));
    }

    @Test
    void testGenerateMirrorConfigUrl() {
        String mirror = "https://artifactory.corp/cargo-virtual";
        String toml = CargoConfigUtils.generateMirrorConfig(mirror);

        assertTrue(toml.contains("[source.crates-io]"));
        assertTrue(toml.contains("replace-with = \"corporate-mirror\""));
        assertTrue(toml.contains("[source.corporate-mirror]"));
        assertTrue(toml.contains("registry = \"sparse+https://artifactory.corp/cargo-virtual\""));
    }

    @Test
    void testGenerateMirrorConfigSparseAlready() {
        String mirror = "sparse+https://artifactory.corp/index/";
        String toml = CargoConfigUtils.generateMirrorConfig(mirror);

        assertTrue(toml.contains("registry = \"sparse+https://artifactory.corp/index/\""));
    }

    @Test
    void testWriteConfig(@TempDir Path tempDir) throws Exception {
        FilePath baseDir = new FilePath(tempDir.toFile());
        CargoConfigUtils.writeConfig(baseDir, "https://nexus.corp/repository/cargo-group");

        FilePath configFile = baseDir.child(".cargo").child("config.toml");
        assertTrue(configFile.exists());
        String content = configFile.readToString();
        assertTrue(content.contains("replace-with = \"corporate-mirror\""));
    }
}
