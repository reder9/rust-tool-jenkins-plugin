# Rust Tool and Pipeline Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/rust-tool.svg)](https://plugins.jenkins.io/rust-tool)
[![GitHub release](https://img.shields.io/github/v/release/jenkinsci/rust-tool-plugin.svg?label=changelog)](https://github.com/jenkinsci/rust-tool-plugin/releases/latest)
[![Jenkins Security Scan](https://github.com/jenkinsci/rust-tool-plugin/workflows/Jenkins%20Security%20Scan/badge.svg)](https://github.com/jenkinsci/rust-tool-plugin/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Automatically installs, provisions, and configures **Rust toolchains** (`rustc`, `cargo`, `rustup`) across Linux, macOS, and Windows agents with first-class Jenkins Pipeline integration (`withRust`), automated component and cross-compilation target provisioning, workspace-isolated caches, and enterprise crates.io mirror support.

---

## Features

- **Automated Bootstrap (`rustup`)**: Downloads platform-native standalone `rustup-init` without shell scripts or curl-piping, provisioning `stable`, `beta`, `nightly`, or pinned releases (`1.85.0`).
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

## License

Licensed under the [MIT License](LICENSE.md).
