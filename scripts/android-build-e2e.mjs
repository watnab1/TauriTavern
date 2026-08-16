#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");
const androidProjectDir = path.join(
    repoRoot,
    "src-tauri",
    "crates",
    "tauritavern",
    "gen",
    "android",
);
const expectedApk = path.join(
    androidProjectDir,
    "app",
    "build",
    "outputs",
    "apk",
    "arm64",
    "e2e",
    "app-arm64-e2e.apk",
);

function run(command, args, { cwd = repoRoot, env = process.env } = {}) {
    const result = spawnSync(command, args, {
        cwd,
        env,
        stdio: "inherit",
    });
    if (result.error) {
        console.error(result.error.message);
        process.exit(1);
    }
    if (result.status !== 0) {
        process.exit(result.status ?? 1);
    }
}

function parseArgs(argv) {
    const args = [];
    let skipDebugBuild = false;
    let skipWebBuild = false;
    for (let index = 0; index < argv.length; index += 1) {
        const value = argv[index];
        if (value === "--skip-debug-build") {
            skipDebugBuild = true;
        } else if (value === "--skip-web-build") {
            skipWebBuild = true;
        } else if (value === "--") {
            args.push(...argv.slice(index + 1));
            break;
        } else {
            args.push(value);
        }
    }
    return { args, skipDebugBuild, skipWebBuild };
}

const { args, skipDebugBuild, skipWebBuild } = parseArgs(process.argv.slice(2));
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const gradlew =
    process.platform === "win32"
        ? path.join(androidProjectDir, "gradlew.bat")
        : path.join(androidProjectDir, "gradlew");

if (!skipDebugBuild) {
    // The Tauri CLI generates tauri.build.gradle.kts, tauri.settings.gradle,
    // assets and the Wry Kotlin sources while building the Debug APK. The E2E
    // variant then reuses that initialized project and swaps in a Release Rust
    // library. Keeping Debug first also matches CI, where both artifacts are
    // contract-checked and uploaded together.
    console.log("==> Initializing Android project and building Debug APK (prerequisite)");
    run(pnpm, ["android:build", "--debug", "--verbose", "--apk", "--split-per-abi", "--target", "aarch64"]);
}

const gradleEnv = {
    ...process.env,
    TAURITAVERN_SKIP_WEB_BUILD: skipWebBuild ? "1" : process.env.TAURITAVERN_SKIP_WEB_BUILD ?? "1",
};

console.log("==> Building Release-like E2E APK (arm64-v8a)");
run(gradlew, [":app:assembleArm64E2e", ...args], {
    cwd: androidProjectDir,
    env: gradleEnv,
});

if (!existsSync(expectedApk)) {
    console.error(`Expected E2E APK not found: ${expectedApk}`);
    process.exit(1);
}

const sha256 = createHash("sha256").update(readFileSync(expectedApk)).digest("hex");
console.log(`E2E APK: ${expectedApk}`);
console.log(`SHA256: ${sha256}`);
