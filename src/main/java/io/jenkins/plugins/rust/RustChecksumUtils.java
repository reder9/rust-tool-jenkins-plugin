package io.jenkins.plugins.rust;

import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for computing and verifying SHA-256 cryptographic checksums for downloaded toolchains and utilities.
 */
public final class RustChecksumUtils implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b");

    private RustChecksumUtils() {}

    /**
     * Extracts the 64-character SHA-256 hex string from standard checksum file contents.
     * Supports raw hex, coreutils text format ("&lt;hash&gt;  &lt;filename&gt;"), and binary format ("&lt;hash&gt; *&lt;filename&gt;").
     */
    public static String parseSha256(String checksumContent) {
        if (checksumContent == null || checksumContent.isBlank()) {
            return null;
        }
        Matcher matcher = SHA256_HEX_PATTERN.matcher(checksumContent.trim());
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ENGLISH);
        }
        return null;
    }

    /**
     * Computes the SHA-256 hex hash of a file on a local or remote agent node.
     */
    public static String computeSha256(FilePath file) throws IOException, InterruptedException {
        return file.act(new ComputeSha256Callable());
    }

    /**
     * Verifies that the SHA-256 of the given file matches expectedSha256.
     * Throws an IOException if there is a mismatch.
     */
    public static void verifySha256(FilePath file, String expectedSha256, TaskListener log)
            throws IOException, InterruptedException {
        String cleanExpected = parseSha256(expectedSha256);
        if (cleanExpected == null) {
            throw new IOException("[Rust Checksum] Invalid expected SHA-256 format: '" + expectedSha256 + "'");
        }

        String actualSha256 = computeSha256(file);
        if (!cleanExpected.equalsIgnoreCase(actualSha256)) {
            String msg = String.format("[Rust Checksum] Verification FAILED for '%s'! Expected SHA-256: %s, Actual SHA-256: %s",
                    file.getName(), cleanExpected, actualSha256);
            if (log != null) {
                log.getLogger().println(msg);
            }
            throw new IOException(msg);
        }

        if (log != null) {
            log.getLogger().println("[Rust Checksum] Verified SHA-256 integrity for " + file.getName() + " (" + actualSha256 + ")");
        }
    }

    /**
     * Attempts to download and verify the .sha256 checksum from the given checksumUrl.
     * Returns true if verification succeeded, false if checksum file was not found (HTTP 404).
     * Throws IOException if checksum was found but verification failed.
     */
    public static boolean downloadAndVerifySha256(FilePath file, String checksumUrl, TaskListener log, boolean required)
            throws IOException, InterruptedException {
        String checksumContent = fetchChecksum(checksumUrl, log);
        if (checksumContent == null) {
            if (required) {
                throw new IOException("[Rust Checksum] Required checksum file not found at: " + checksumUrl);
            }
            if (log != null) {
                log.getLogger().println("[Rust Checksum] No checksum file found at " + checksumUrl + " (skipping verification)");
            }
            return false;
        }

        String expected = parseSha256(checksumContent);
        if (expected == null) {
            if (required) {
                throw new IOException("[Rust Checksum] Could not parse valid 64-char SHA-256 hash from " + checksumUrl);
            }
            return false;
        }

        verifySha256(file, expected, log);
        return true;
    }

    private static String fetchChecksum(String checksumUrl, TaskListener log) {
        try {
            URL url = URI.create(checksumUrl).toURL();
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (conn instanceof HttpURLConnection http) {
                int code = http.getResponseCode();
                if (code == 404) {
                    return null;
                }
                if (code >= 400) {
                    if (log != null) {
                        log.getLogger().println("[Rust Checksum] Note: HTTP " + code + " when fetching " + checksumUrl);
                    }
                    return null;
                }
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException | RuntimeException e) {
            if (log != null) {
                log.getLogger().println("[Rust Checksum] Note: Could not fetch checksum from " + checksumUrl + " (" + e.getMessage() + ")");
            }
            return null;
        }
    }

    private static class ComputeSha256Callable extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 1L;

        @Override
        public String invoke(File f, VirtualChannel channel) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                }
                byte[] hashBytes = digest.digest();
                StringBuilder sb = new StringBuilder(64);
                for (byte b : hashBytes) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 algorithm not available", e);
            }
        }
    }
}
