# Rust Tool and Pipeline Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/rust-tool.svg)](https://plugins.jenkins.io/rust-tool)
[![GitHub release](https://img.shields.io/github/v/release/jenkinsci/rust-tool-plugin.svg?label=changelog)](https://github.com/jenkinsci/rust-tool-plugin/releases/latest)
[![Jenkins Security Scan](https://github.com/jenkinsci/rust-tool-plugin/workflows/Jenkins%20Security%20Scan/badge.svg)](https://github.com/jenkinsci/rust-tool-plugin/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Automatically installs, provisions, and configures **Rust toolchains** (`rustc`, `cargo`, `rustup`) across Linux, macOS, and Windows agents with first-class Jenkins Pipeline integration (`withRust`), automated component and cross-compilation target provisioning, workspace-isolated caches, and enterprise crates.io mirror support.

---

## Features

- **Automated Bootstrap (`rustup`)**: Downloads platform-native standalone `rustup-init` without shell scripts or curl-piping, provisioning `stable`, `beta`, `nightly`, or pinned releases (`1.85.0`).
- **Self-Updating Version Catalog**: Scheduled GitHub Actions workflow syncs upstream `rust-lang/rust`, `mozilla/sccache`, and `cargo-bins/cargo-binstall` releases to the `catalog` branch, allowing Jenkins instances to discover newly published versions automatically without requiring a plugin update.
- **Fast Tool Acceleration (`binstall: true`)**: Provisions standalone `cargo-binstall` binaries across Linux, macOS, and Windows to download and install pre-compiled CLI tools in seconds.
- **Shared Compilation Cache (`sccache: true`)**: Auto-provisions Mozilla's standalone `sccache` binary, sets `RUSTC_WRAPPER = "sccache"`, and configures shared agent compiler caches to dramatically accelerate recurring builds.
- **Pre-Configured Global Tools & Custom Homes**: Seamlessly resolve named installations (`tool: 'rust-stable'`) or explicit filesystem paths (`home: '/opt/rust'`).
- **Host System Runtime Probing (`system: true`)**: Automatically discovers pre-installed `cargo`, `rustc`, and `rustup` on agent nodes (PATH, `~/.cargo/bin`, Windows Program Files) for zero-download execution.
- **Automated Tool Crates (`crates: '...'`)**: Installs specified tool crates (e.g. `cargo-nextest,cargo-audit`) via `cargo binstall` (or `cargo install`) before step execution.
- **Cryptographic SHA-256 Integrity Verification**: Automatically verifies upstream `.sha256` checksums for `rustup-init`, `sccache`, and standalone archives; supports explicit `expectedSha256` enforcement for zero-trust enterprise compliance.
- **Cross-Platform & Alpine/Musl Support**: Native target resolution across Linux (glibc and musl/Alpine), macOS (Intel and Apple Silicon), and Windows (MSVC).
- **Automatic `rust-toolchain.toml` Detection**: Automatically parses repository `rust-toolchain.toml` or legacy `rust-toolchain` files when toolchain is omitted or set to `'auto'`, extracting channel, components, and targets without boilerplate.
- **Air-Gapped Standalone Mode**: Direct download of official static toolchain archives (`rust-<version>-<target>.tar.gz` / `.zip`) from `static.rust-lang.org/dist/` for locked-down corporate networks.
- **Declarative Pipeline Step (`withRust`)**: Context wrapper that manages `PATH`, `RUSTC`, `CARGO`, `RUSTUP_HOME`, and `CARGO_HOME` automatically.
- **Component & Target Auto-Provisioning**: Install `clippy`, `rustfmt`, `llvm-tools-preview`, `miri`, and cross-compilation targets (`wasm32-unknown-unknown`, `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-gnu`) on demand.
- **Corporate Mirror Compliance**: Automatic generation of `.cargo/config.toml` crate source replacements for JFrog Artifactory, Sonatype Nexus, or private registries.
- **Concurrency & Cache Isolation**: Option to isolate `CARGO_HOME` within the workspace (`isolateCargoHome: true`), preventing SQLite locking conflicts across multi-executor agents.
- **Configuration as Code (JCasC)**: Fully configurable via YAML in Jenkins Configuration as Code.

---

## Pipeline Examples

### Basic Usage
```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                withRust('stable') {
                    sh 'cargo --version'
                    sh 'cargo test --all'
                }
            }
        }
    }
}
```

### Pre-Configured Tool or System Rust
```groovy
// Use a pre-configured tool defined in Jenkins Global Tool Configuration
withRust(tool: 'rust-stable') {
    sh 'cargo test'
}

// Or probe host agent for existing rustc / cargo (zero network download)
withRust(system: true) {
    sh 'cargo --version'
}
```

### Cryptographic Checksum Integrity Enforcement
```groovy
// Strictly enforce SHA-256 integrity for toolchain bootstrap
withRust(
    toolchain: '1.85.0',
    expectedSha256: 'be4ba7b777100c851ab268e95f70f405d28d7813ba60a9bdcf4e88c88acf8602'
) {
    sh 'cargo test'
}
```

### High-Performance CI with `sccache` and `cargo-binstall`
```groovy
// Speed up builds with Mozilla sccache and pre-compiled cargo tools
withRust(
    toolchain: 'stable',
    sccache: true,                     // Auto-provisions Mozilla sccache & sets RUSTC_WRAPPER
    binstall: true,                    // Auto-provisions cargo-binstall binary
    crates: 'cargo-nextest,cargo-audit', // Installs binary tools in seconds without compiling
    isolateCargoHome: true
) {
    sh 'cargo nextest run'             // Next-generation fast test runner
    sh 'cargo audit'                   // Security dependency vulnerability scanner
}
```

### Full Enterprise CI Pipeline
```groovy
node('linux-x86_64') {
    stage('Lint & Build') {
        withRust(
            toolchain: '1.85.0',
            components: 'clippy,rustfmt',
            targets: 'wasm32-unknown-unknown',
            mirrorUrl: 'https://artifactory.corp.internal/cargo-virtual',
            isolateCargoHome: true
        ) {
            sh 'cargo fmt --check'
            sh 'cargo clippy -- -D warnings'
            sh 'cargo build --release --target wasm32-unknown-unknown'
            sh 'cargo test'
        }
    }
}
```

---

## Global Tool Configuration

Navigate to **Manage Jenkins** &rarr; **Tools** &rarr; **Rust**:
1. Click **Add Rust**.
2. Give it a name (e.g. `Rust 1.85.0`).
3. Under **Install automatically**, select **Install from rustup**:
   - **Toolchain**: `1.85.0` (or `stable`, `nightly`)
   - **Components**: `clippy,rustfmt`
   - **Targets**: `wasm32-unknown-unknown`
   - **Mirror URL**: `https://artifactory.corp/cargo-virtual` (optional)

---

## Configuration as Code (JCasC)

```yaml
tool:
  rust:
    installations:
      - name: "rust-stable"
        properties:
          - installSource:
              installers:
                - rustup:
                    toolchain: "stable"
                    components: "clippy,rustfmt"
```

---

## Self-Updating Version Catalog & Air-Gapped Overrides

The plugin includes a bundled [`versions.json`](src/main/resources/io/jenkins/plugins/rust/versions.json) catalog and automatically refreshes it every 24 hours from the `catalog` branch (maintained weekly by [`.github/workflows/update-versions.yml`](.github/workflows/update-versions.yml)).

- **Air-Gapped / Custom Catalog File**: Place a custom `rust-versions.json` in `$JENKINS_HOME/rust-versions.json` or pass `-Dio.jenkins.plugins.rust.versionsFile=/path/to/versions.json`.
- **Disable Remote Catalog Fetch**: Pass `-Dio.jenkins.plugins.rust.dynamicFetch=false` to use only the bundled classpath catalog.
- **Manual Local Refresh**: Run `python scripts/update-rust-versions.py` (or `.\scripts\update-rust-versions.ps1`).

---

## License

Licensed under the [MIT License](LICENSE).
