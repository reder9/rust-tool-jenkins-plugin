# AGENTS.md — Jenkins Rust Tool & Pipeline Plugin (`rust-tool`)

## Overview
- **Plugin ID**: `rust-tool`
- **Repository**: `reder9/rust-tool-jenkins-plugin` (`jenkinsci/rust-tool-plugin`)
- **Java Package**: `io.jenkins.plugins.rust`
- **Jenkins Baseline**: `2.555.x` (`org.jenkins-ci.plugins:plugin:6.2250.va_d01d559a_209`, Java 17 / 21+)

Provides global Rust toolchain management (`RustInstallation`), automated cross-platform installation via `rustup` or standalone archives (`RustupInstaller`, `StandaloneRustInstaller`), companion tool installers (`SccacheInstaller`, `CargoBinstallInstaller`), `rust-toolchain.toml` auto-detection, and the `withRust` Pipeline step (`WithRustStep`).

---

## Build, Test & Verification Commands

### Prerequisites
- **JDK**: Java 21 (recommended) or Java 17+
- **Maven**: Apache Maven `3.9+`

### Core Maven Commands
```bash
# Full build, unit/integration tests, SpotBugs, and Enforcer checks (always run before opening a PR)
mvn clean verify

# Run unit tests quickly (skipping slow InjectedTest Jelly scan during development)
mvn test -Dtest=!InjectedTest

# Run a specific test class
mvn test -Dtest=WithRustStepTest
mvn test -Dtest=RustToolchainFileParserTest

# Run SpotBugs and Maven Enforcer checks directly
mvn spotbugs:check enforcer:enforce

# Package the .hpi plugin artifact without running tests
mvn clean package -DskipTests

# Start a local Jenkins instance at http://localhost:8080/jenkins
mvn hpi:run
```

---

## Architecture & Source Layout

- `src/main/java/io/jenkins/plugins/rust/`
  - `RustInstallation.java` — `ToolInstallation` (`EnvironmentSpecific`, `NodeSpecific`) defining global Rust toolchains.
  - `RustInstaller.java`, `RustupInstaller.java`, `StandaloneRustInstaller.java` — Tool installers supporting `rustup` profiles/targets/components and offline-friendly standalone Rust release tarballs/MSIs with SHA-256 verification (`RustChecksumUtils.java`).
  - `SccacheInstaller.java` & `CargoBinstallInstaller.java` — Companion installers for `sccache` compiler caching (`RUSTC_WRAPPER`) and `cargo-binstall`.
  - `RustToolchainFileParser.java` — Parses `rust-toolchain` and `rust-toolchain.toml` files in the workspace.
  - `CargoConfigUtils.java` — Configures Cargo registry credentials and sparse-registry settings safely.
  - `RustPlatformUtils.java`, `RustProbeUtils.java`, `RustVersionCatalog.java` — Agent OS/target-triple detection, binary probing, and version catalog resolution (`versions.json`).
  - `WithRustStep.java` & `WithRustStepExecution.java` — Pipeline step (`withRust`) injecting `PATH`, `CARGO_HOME`, `RUSTUP_HOME`, `RUSTFLAGS`, and `RUSTC_WRAPPER`.
- `src/main/resources/io/jenkins/plugins/rust/`
  - `versions.json`, `Messages.properties`, and Jelly configuration views (`RustInstallation/config.jelly`, `RustupInstaller/config.jelly`, `StandaloneRustInstaller/config.jelly`, `WithRustStep/config.jelly`).
- `src/test/java/io/jenkins/plugins/rust/`
  - `WithRustStepTest.java`, `RustInstallationTest.java`, `RustInstallerTest.java`, `RustToolchainFileParserTest.java`, `CargoConfigUtilsTest.java`, `RustChecksumUtilsTest.java`, `RustPlatformUtilsTest.java`, `RustProbeUtilsTest.java`, `RustVersionCatalogTest.java`, `SccacheInstallerTest.java`, `CargoBinstallInstallerTest.java`.

---

## Coding, Remoting & Security Guardrails

1. **Jenkins Remoting & Agent File Operations**:
   - Always use `hudson.FilePath` and `hudson.Launcher` for file inspection, archive extraction, SHA-256 verification, and command execution on build agents. Never use `java.io.File` on agent paths.
   - Ensure temporary Cargo credential files created during `withRust` execution are cleaned up in a `finally` / `TailCall` block and never logged to `TaskListener`.
2. **Download Integrity & Security**:
   - Verify SHA-256 checksums via `RustChecksumUtils` when downloading standalone toolchains or companion binaries.
   - Enforce `@POST` and permission checks on any `doCheck*` or `doFill*` Descriptor endpoints that perform I/O.
3. **UI & Dependency Standards**:
   - Never use raw emojis in UI strings or Jelly templates; use Jenkins Symbols or SVG icons.
   - Never call native browser `alert()`, `confirm()`, or `prompt()`.
   - Keep BOM/parent POM updates separate from feature/bugfix PRs unless required by an API change.
