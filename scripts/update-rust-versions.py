#!/usr/bin/env python3
"""
Version Catalog Generator for the Jenkins Rust Tool Plugin.

Fetches the latest official Rust toolchain releases from rust-lang/rust,
Mozilla sccache releases from mozilla/sccache, and cargo-binstall releases
from cargo-bins/cargo-binstall, then MERGES them into an existing versions.json
so previously published versions are never dropped.

The output file is used in two places:
  * src/main/resources/io/jenkins/plugins/rust/versions.json (bundled fallback)
  * the `catalog` branch of the plugin repository, refreshed on a schedule by
    .github/workflows/update-versions.yml and fetched dynamically by the plugin.
"""

import argparse
import datetime
import json
import os
import re
import sys
import urllib.request

RUST_REPO = "rust-lang/rust"
SCCACHE_REPO = "mozilla/sccache"
BINSTALL_REPO = "cargo-bins/cargo-binstall"

CHANNELS = ["stable", "beta", "nightly"]

SUPPORTED_TRIPLES = [
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-unknown-linux-musl",
    "aarch64-unknown-linux-musl",
    "armv7-unknown-linux-gnueabihf",
    "armv7-unknown-linux-musleabihf",
    "powerpc64le-unknown-linux-gnu",
    "s390x-unknown-linux-gnu",
    "riscv64gc-unknown-linux-gnu",
    "i686-unknown-linux-gnu",
    "x86_64-apple-darwin",
    "aarch64-apple-darwin",
    "x86_64-pc-windows-msvc",
    "aarch64-pc-windows-msvc",
    "i686-pc-windows-msvc",
]

SEED_RUST_VERSIONS = [
    "1.85.0",
    "1.84.1",
    "1.84.0",
    "1.83.0",
    "1.82.0",
    "1.81.0",
    "1.80.1",
    "1.80.0",
    "1.79.0",
    "1.78.0",
    "1.77.2",
    "1.76.0",
    "1.75.0",
]

SEED_SCCACHE_VERSIONS = [
    "0.18.0",
    "0.17.0",
    "0.16.0",
    "0.15.0",
    "0.14.0",
    "0.13.0",
    "0.12.0",
    "0.11.0",
    "0.10.0",
    "0.9.1",
    "0.9.0",
    "0.8.2",
]

SEED_BINSTALL_VERSIONS = [
    "1.26.0",
    "1.25.0",
    "1.24.0",
    "1.23.0",
    "1.22.0",
    "1.21.0",
    "1.20.0",
    "1.19.0",
    "1.18.0",
    "1.17.0",
    "1.16.0",
    "1.15.0",
]

SEMVER_REGEX = re.compile(r"^\d+\.\d+\.\d+$")

DEFAULT_MAX_RUST_VERSIONS = 60
DEFAULT_MAX_TOOL_VERSIONS = 40


def github_get(url, token=None):
    headers = {
        "User-Agent": "Jenkins-Rust-Plugin-Updater",
        "Accept": "application/vnd.github.v3+json",
    }
    if token:
        headers["Authorization"] = f"token {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[WARN] Could not fetch {url}: {e}", file=sys.stderr)
        return []


def fetch_repo_versions(repo, token=None, limit=30):
    print(f"[INFO] Querying GitHub Releases from {repo}...")
    releases = github_get(f"https://api.github.com/repos/{repo}/releases?per_page={limit}", token)
    versions = []
    for release in releases:
        if not isinstance(release, dict):
            continue
        if release.get("draft") or release.get("prerelease"):
            continue
        tag = (release.get("tag_name") or "").strip().lstrip("vV")
        if SEMVER_REGEX.match(tag):
            versions.append((tag, (release.get("published_at") or "")[:10]))
    return versions


def build_platform_map(version):
    platforms = {}
    for triple in SUPPORTED_TRIPLES:
        ext = ".zip" if "windows" in triple else ".tar.gz"
        filename = f"rust-{version}-{triple}{ext}"
        url = f"https://static.rust-lang.org/dist/{filename}"
        platforms[triple] = {
            "url": url,
            "filename": filename,
        }
    return platforms


def build_rust_release_entry(version, release_date=""):
    return {
        "releaseDate": release_date,
        "platforms": build_platform_map(version),
    }


def version_key(v):
    try:
        return [int(x) for x in str(v).split(".")]
    except ValueError:
        return [0, 0, 0]


def sort_versions_dict(versions_dict):
    sorted_keys = sorted(versions_dict.keys(), key=version_key, reverse=True)
    return {k: versions_dict[k] for k in sorted_keys}


def load_existing(path):
    if path and os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return data
        except (OSError, ValueError) as e:
            print(f"[WARN] Could not read existing catalog {path}: {e}", file=sys.stderr)
    return {}


def merge_rust_versions(existing, fresh_tuples, max_versions):
    merged = {ver: data for ver, data in existing.items() if SEMVER_REGEX.match(str(ver))}
    for seed_ver in SEED_RUST_VERSIONS:
        if seed_ver not in merged:
            merged[seed_ver] = build_rust_release_entry(seed_ver)
    for ver, rel_date in fresh_tuples:
        if ver not in merged:
            merged[ver] = build_rust_release_entry(ver, rel_date)
        else:
            if rel_date and not merged[ver].get("releaseDate"):
                merged[ver]["releaseDate"] = rel_date
            platforms = merged[ver].setdefault("platforms", {})
            for triple, pdata in build_platform_map(ver).items():
                platforms.setdefault(triple, pdata)
    ordered = sort_versions_dict(merged)
    if max_versions > 0:
        keys = list(ordered.keys())[:max_versions]
        return {k: ordered[k] for k in keys}
    return ordered


def merge_tool_versions(existing, seed_list, fresh_tuples, max_versions):
    fresh_list = [v for v, _ in fresh_tuples]
    combined = (
        set(v for v in existing if SEMVER_REGEX.match(str(v)))
        | set(seed_list)
        | set(fresh_list)
    )
    ordered = sorted(combined, key=version_key, reverse=True)
    return ordered[:max_versions] if max_versions > 0 else ordered


def default_catalog_path():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidate1 = os.path.join(
        script_dir, "..", "src", "main", "resources", "io", "jenkins", "plugins", "rust", "versions.json"
    )
    candidate2 = os.path.join(
        script_dir,
        "..",
        "Plugins",
        "rust-tool-jenkins-plugin",
        "src",
        "main",
        "resources",
        "io",
        "jenkins",
        "plugins",
        "rust",
        "versions.json",
    )
    if os.path.exists(os.path.dirname(candidate1)):
        return os.path.abspath(candidate1)
    return os.path.abspath(candidate2)


def main():
    parser = argparse.ArgumentParser(
        description="Update the Rust toolchain, sccache, and cargo-binstall versions catalog."
    )
    parser.add_argument("--catalog", "-c", help="Path to versions.json (read as merge base, then updated in place).")
    parser.add_argument("--token", "-t", help="GitHub API personal access token.")
    parser.add_argument("--limit", "-l", type=int, default=30, help="Number of releases to query per repository.")
    parser.add_argument(
        "--max-rust-versions",
        type=int,
        default=DEFAULT_MAX_RUST_VERSIONS,
        help="Maximum Rust release versions to keep in the catalog (0 = keep all).",
    )
    parser.add_argument(
        "--max-tool-versions",
        type=int,
        default=DEFAULT_MAX_TOOL_VERSIONS,
        help="Maximum sccache/cargo-binstall versions to keep in the catalog (0 = keep all).",
    )
    args = parser.parse_args()

    target_file = os.path.abspath(args.catalog) if args.catalog else default_catalog_path()

    existing = load_existing(target_file)
    existing_versions = existing.get("versions", {}) if isinstance(existing.get("versions"), dict) else {}
    existing_sccache = existing.get("sccacheVersions", []) if isinstance(existing.get("sccacheVersions"), list) else []
    existing_binstall = (
        existing.get("binstallVersions", []) if isinstance(existing.get("binstallVersions"), list) else []
    )

    rust_fresh = fetch_repo_versions(RUST_REPO, token=args.token, limit=args.limit)
    sccache_fresh = fetch_repo_versions(SCCACHE_REPO, token=args.token, limit=args.limit)
    binstall_fresh = fetch_repo_versions(BINSTALL_REPO, token=args.token, limit=args.limit)

    merged_rust = merge_rust_versions(existing_versions, rust_fresh, args.max_rust_versions)
    merged_sccache = merge_tool_versions(
        existing_sccache, SEED_SCCACHE_VERSIONS, sccache_fresh, args.max_tool_versions
    )
    merged_binstall = merge_tool_versions(
        existing_binstall, SEED_BINSTALL_VERSIONS, binstall_fresh, args.max_tool_versions
    )

    unchanged = (
        merged_rust == existing_versions
        and merged_sccache == existing_sccache
        and merged_binstall == existing_binstall
        and existing.get("schemaVersion") == 1
        and existing.get("channels") == CHANNELS
    )
    if unchanged:
        print(
            f"[INFO] Catalog already up to date ({len(merged_rust)} Rust versions, "
            f"{len(merged_sccache)} sccache versions, {len(merged_binstall)} cargo-binstall versions)."
        )
        print(f"       {target_file}")
        return

    output_data = {
        "schemaVersion": 1,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "channels": CHANNELS,
        "totalVersions": len(merged_rust),
        "versions": merged_rust,
        "sccacheVersions": merged_sccache,
        "binstallVersions": merged_binstall,
    }

    os.makedirs(os.path.dirname(target_file), exist_ok=True)
    with open(target_file, "w", encoding="utf-8") as f:
        json.dump(output_data, f, indent=2)
        f.write("\n")

    added = [v for v in merged_rust if v not in existing_versions]
    print(
        f"[SUCCESS] Wrote {len(merged_rust)} Rust versions, {len(merged_sccache)} sccache versions, "
        f"and {len(merged_binstall)} cargo-binstall versions to:"
    )
    print(f"          {target_file}")
    print(f"\nNew Rust versions this run: {', '.join(added) if added else 'none'}")
    print("\nAvailable versions summary:")
    for ver in list(merged_rust.keys())[:8]:
        print(f"  - Rust {ver} (Platforms: {len(merged_rust[ver]['platforms'])})")
    print(f"  - sccache: {', '.join(merged_sccache[:5])}{' ...' if len(merged_sccache) > 5 else ''}")
    print(f"  - cargo-binstall: {', '.join(merged_binstall[:5])}{' ...' if len(merged_binstall) > 5 else ''}")


if __name__ == "__main__":
    main()
