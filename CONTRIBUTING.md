# Contributing to the Rust Tool and Pipeline Plugin

Thank you for your interest in contributing to the Rust Tool and Pipeline Plugin!

## Reporting Issues
Please report issues and feature requests on [GitHub Issues](https://github.com/jenkinsci/rust-tool-plugin/issues).

## Developing and Testing
This project uses Maven and Java 21+.

- To build: `mvn clean verify`
- To run tests: `mvn test`
- To verify SpotBugs: `mvn spotbugs:check`
- To run a local interactive Jenkins development instance: `mvn hpi:run`

## Submitting Pull Requests
1. Fork the repository and create a feature branch.
2. Ensure all JUnit 5 tests pass and SpotBugs reports 0 bugs.
3. Open a pull request against the `main` branch.
4. Continuous Delivery will automatically release merged pull requests using JEP-229.
