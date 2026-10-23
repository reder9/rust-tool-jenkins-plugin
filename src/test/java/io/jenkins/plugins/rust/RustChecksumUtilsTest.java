package io.jenkins.plugins.rust;

import hudson.FilePath;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustChecksumUtilsTest {

    @Test
    void testParseSha256Formats() {
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        // Plain lowercase
        assertEquals(expected, RustChecksumUtils.parseSha256(expected));

        // Plain uppercase
        assertEquals(expected, RustChecksumUtils.parseSha256(expected.toUpperCase()));

        // Coreutils format with two spaces
        assertEquals(expected, RustChecksumUtils.parseSha256(expected + "  rustup-init"));

        // Binary mode with asterisk
        assertEquals(expected, RustChecksumUtils.parseSha256(expected + " *./rustup-init.exe"));

        // Checksum with surrounding whitespace and newline
        assertEquals(expected, RustChecksumUtils.parseSha256("\n  " + expected + "\n"));

        // Invalid or empty formats
        assertNull(RustChecksumUtils.parseSha256(null));
        assertNull(RustChecksumUtils.parseSha256(""));
        assertNull(RustChecksumUtils.parseSha256("short-hash-1234"));
        assertNull(RustChecksumUtils.parseSha256("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
    }

    @Test
    void testComputeAndVerifySha256(@TempDir File tempDir) throws Exception {
        FilePath testFile = new FilePath(tempDir).child("sample.txt");
        testFile.write("Hello Jenkins Rust Plugin!", "UTF-8");

        // Compute actual hash
        String computed = RustChecksumUtils.computeSha256(testFile);
        assertNotNull(computed);
        assertEquals(64, computed.length());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(baos, StandardCharsets.UTF_8);

        // Verify with matching expected hash
        RustChecksumUtils.verifySha256(testFile, computed, listener);
        assertTrue(baos.toString(StandardCharsets.UTF_8).contains("Verified SHA-256 integrity"));

        // Verify with mismatched expected hash throws IOException
        String bogusHash = "0000000000000000000000000000000000000000000000000000000000000000";
        IOException ex = assertThrows(IOException.class, () ->
                RustChecksumUtils.verifySha256(testFile, bogusHash, listener));
        assertTrue(ex.getMessage().contains("Verification FAILED"));
    }
}
