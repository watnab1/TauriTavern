#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

function fail(message) {
    console.error(`FAIL: ${message}`);
    process.exit(1);
}

function findBuildTool(tool) {
    const roots = [
        process.env.ANDROID_HOME,
        process.env.ANDROID_SDK_ROOT,
    ].filter(Boolean);
    const buildToolsRoots = roots
        .map((root) => path.join(root, "build-tools"))
        .filter((candidate) => existsSync(candidate));
    for (const buildToolsRoot of buildToolsRoots) {
        const versions = spawnSync("ls", [buildToolsRoot], { encoding: "utf8" })
            .stdout.split(/\s+/)
            .filter(Boolean)
            .sort()
            .reverse();
        for (const version of versions) {
            const candidate = path.join(
                buildToolsRoot,
                version,
                process.platform === "win32" ? `${tool}.bat` : tool,
            );
            if (existsSync(candidate)) {
                return candidate;
            }
        }
    }
    return tool;
}

function runBuffer(tool, args) {
    const result = spawnSync(tool, args, {
        maxBuffer: 1024 * 1024 * 1024,
    });
    if (result.error) {
        fail(`${tool}: ${result.error.message}`);
    }
    if (result.status !== 0) {
        fail(`${tool} ${args.join(" ")} exited with ${result.status}`);
    }
    return result.stdout;
}

function run(tool, args, { allowFailure = false } = {}) {
    const result = spawnSync(tool, args, {
        encoding: "utf8",
        maxBuffer: 512 * 1024 * 1024,
    });
    if (result.error) {
        if (allowFailure) {
            return { status: null, stdout: "", stderr: result.error.message };
        }
        fail(`${tool}: ${result.error.message}`);
    }
    if (result.status !== 0 && !allowFailure) {
        fail(
            `${tool} ${args.join(" ")} exited with ${result.status}\nSTDOUT:\n${result.stdout}\nSTDERR:\n${result.stderr}`,
        );
    }
    return { status: result.status, stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
}

function parseApkBadging(apk) {
    const aapt2 = findBuildTool("aapt2");
    const { stdout } = run(aapt2, ["dump", "badging", apk]);
    const badging = {};
    for (const line of stdout.split(/\r?\n/)) {
        if (line.startsWith("package:")) {
            badging.packageName = line.match(/name='([^']+)'/)?.[1] ?? null;
            badging.versionCode = Number(line.match(/versionCode='(\d+)'/)?.[1] ?? NaN);
            badging.versionName = line.match(/versionName='([^']+)'/)?.[1] ?? null;
        }
        if (line.startsWith("native-code:")) {
            badging.abis = [...line.matchAll(/'([^']+)'/g)].map((match) => match[1]);
        }
        if (line.startsWith("minSdkVersion:")) {
            badging.minSdk = Number(line.match(/'(\d+)'/)?.[1] ?? NaN);
        }
        if (line.startsWith("targetSdkVersion:")) {
            badging.targetSdk = Number(line.match(/'(\d+)'/)?.[1] ?? NaN);
        }
    }
    return badging;
}

function parseBooleanXmlAttribute(xml, name) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const text = String(xml);
    const direct = text.match(new RegExp(`android:${escaped}\\(0x[0-9a-fA-F]+\\)=(true|false)`));
    if (direct) {
        return direct[1] === "true";
    }
    const typed = text.match(new RegExp(`android:${escaped}\\(0x[0-9a-fA-F]+\\)=\\(type 0x12\\)(0x[0-9a-fA-F]+)`));
    if (typed) {
        return typed[1] !== "0x0";
    }
    return null;
}

function dumpManifestTree(input, { isApk }) {
    const aapt2 = findBuildTool("aapt2");
    const args = ["dump", "xmltree"];
    if (isApk) {
        args.push("--file", "AndroidManifest.xml");
    }
    args.push(input);
    return run(aapt2, args).stdout;
}

function verifyApkSigning(apk, { expectCertificateSubject }) {
    const apksigner = findBuildTool("apksigner");
    const result = run(apksigner, ["verify", "--verbose", "--print-certs", apk]);
    const combined = `${result.stdout}\n${result.stderr}`;
    const subject =
        combined.match(/Signer #1 certificate DN:\s*(.+)/)?.[1] ??
        combined.match(/V2 Signer: certificate DN:\s*(.+)/)?.[1] ??
        null;
    const digest =
        combined.match(/Signer #1 certificate SHA-256 digest:\s*(.+)/)?.[1] ??
        combined.match(/V2 Signer: certificate SHA-256 digest:\s*(.+)/)?.[1] ??
        null;
    if (expectCertificateSubject && !(subject || "").includes(expectCertificateSubject)) {
        fail(
            `Expected E2E signer subject to include ${JSON.stringify(expectCertificateSubject)}, got ${JSON.stringify(subject)}`,
        );
    }
    return { verified: true, certificateSubject: subject, certificateSha256: digest };
}

function parseBooleanLiteral(value) {
    return value === "true" ? true : value === "false" ? false : null;
}

/**
 * Verifies the final APK bytecode, not source text:
 *
 * - `MainActivity.configureWebViewDebugging` must call
 *   `WebView.setWebContentsDebuggingEnabled(Z)`.
 * - BuildConfig.DEBUG is true when the BuildConfig `<clinit>` parses "true".
 * - E2E_ENABLED is read from the BuildConfig field when R8 keeps it, or from
 *   the `const/4 ..., #int 1` literal R8 substitutes when the field is inlined.
 */
function verifyDexWebViewDebugging(apk) {
    const dexdump = findBuildTool("dexdump");
    const tempDir = mkdtempSync(path.join(os.tmpdir(), "tauritavern-apk-"));
    try {
        run("unzip", ["-q", "-o", apk, "classes*.dex", "-d", tempDir]);
        let debug = null;
        let e2eEnabled = null;
        let callSeen = false;
        let literalOne = false;

        for (const dexFile of readdirSync(tempDir).filter((name) => /^classes\d*\.dex$/.test(name))) {
            const dump = run(dexdump, ["-d", path.join(tempDir, dexFile)]).stdout;

            if (!callSeen) {
                const configure = dump.match(
                    /name\s*:\s*'configureWebViewDebugging'[\s\S]{0,4000}?invoke-static \{v[0-9a-f]+\}, Landroid\/webkit\/WebView;\.setWebContentsDebuggingEnabled:\(Z\)/,
                );
                if (configure) {
                    callSeen = true;
                    literalOne = /const\/4 v\d+, #int 1/.test(configure[0]);
                }
            }

            if (debug === null || e2eEnabled === null) {
                const buildConfig = dump.match(
                    /Class descriptor\s*:\s*'Lcom\/tauritavern\/client\/BuildConfig;'[\s\S]*?\nClass #/,
                );
                if (buildConfig) {
                    const chunk = buildConfig[0];
                    debug ??=
                        chunk.includes('const-string') &&
                        chunk.includes('"true"') &&
                        /sput-boolean v\d+, Lcom\/tauritavern\/client\/BuildConfig;\.DEBUG:Z/.test(chunk);
                    e2eEnabled ??= parseBooleanLiteral(
                        chunk.match(
                            /name\s*:\s*'E2E_ENABLED'[\s\S]{0,200}?value\s*:\s*(true|false)/,
                        )?.[1] ?? null,
                    );
                }
            }
        }

        if (!callSeen || debug === null) {
            fail(
                `${path.basename(apk)} does not contain the expected WebView debugging bytecode (BuildConfig.DEBUG / setWebContentsDebuggingEnabled)`,
            );
        }
        if (e2eEnabled === null) {
            // R8 inlined and removed the E2E_ENABLED field in the optimized
            // variant. The constant folded into the call argument below.
            e2eEnabled = literalOne;
        }
        return { debug, e2eEnabled, debuggingEnabled: debug || e2eEnabled };
    } finally {
        rmSync(tempDir, { recursive: true, force: true });
    }
}

function verifyNativeLibraryReference(apk, expected, buildType) {
    if (!expected.nativeLibReference) {
        return null;
    }
    const reference = path.resolve(REPO_ROOT, expected.nativeLibReference);
    if (!existsSync(reference)) {
        fail(`${buildType} native library reference not found: ${reference}`);
    }
    const referenceHash = createHash("sha256").update(readFileSync(reference)).digest("hex");
    const packagedHash = createHash("sha256")
        .update(runBuffer("unzip", ["-p", apk, expected.nativeLibraries[0]]))
        .digest("hex");
    if (packagedHash !== referenceHash) {
        fail(
            `${buildType} packaged native library does not match the ${buildType} Rust profile reference (${path.basename(reference)}): packaged=${packagedHash} reference=${referenceHash}`,
        );
    }
    return { packagedHash, referenceHash };
}

function verifyApk({ apk, buildType, expected, metadataOut, gitSha, workflowRunId }) {
    const badging = parseApkBadging(apk);
    const manifestXml = dumpManifestTree(apk, { isApk: true });
    const debuggable = parseBooleanXmlAttribute(manifestXml, "debuggable");
    const cleartext = parseBooleanXmlAttribute(manifestXml, "usesCleartextTraffic");
    const sha256 = createHash("sha256").update(readFileSync(apk)).digest("hex");

    const checks = [
        ["applicationId", badging.packageName, expected.applicationId],
        ["versionName", badging.versionName, expected.versionName],
        ["versionCode", badging.versionCode, expected.versionCode],
        ["debuggable", debuggable, expected.debuggable],
        ["cleartext", cleartext, expected.cleartext],
        ["abis", JSON.stringify(badging.abis), JSON.stringify(expected.abis)],
        ["minSdk", badging.minSdk, expected.minSdk],
        ["targetSdk", badging.targetSdk, expected.targetSdk],
    ];
    for (const [name, actual, wanted] of checks) {
        if (actual !== wanted) {
            fail(
                `${buildType} ${name} mismatch for ${path.basename(apk)}: expected ${JSON.stringify(wanted)}, got ${JSON.stringify(actual)}`,
            );
        }
    }

    const zipListing = run("unzip", ["-l", apk]).stdout;
    for (const lib of expected.nativeLibraries) {
        if (!zipListing.includes(lib)) {
            fail(`${buildType} APK is missing native library ${lib}`);
        }
    }

    const signing = verifyApkSigning(apk, {
        expectCertificateSubject: expected.certificateSubject,
    });
    const nativeLibrary = verifyNativeLibraryReference(apk, expected, buildType);
    const buildConfig = verifyDexWebViewDebugging(apk);
    for (const [name, actual, wanted] of [
        ["BuildConfig.DEBUG", buildConfig.debug, expected.buildConfigDebug],
        ["BuildConfig.E2E_ENABLED", buildConfig.e2eEnabled, expected.buildConfigE2eEnabled],
    ]) {
        if (actual !== wanted) {
            fail(`${buildType} ${name} mismatch: expected ${wanted}, got ${actual}`);
        }
    }
    if (buildConfig.debuggingEnabled !== true) {
        fail(`${buildType} WebView debugging is not enabled in final APK bytecode`);
    }

    const metadata = {
        gitSha,
        workflowRunId,
        applicationId: badging.packageName,
        versionName: badging.versionName,
        versionCode: badging.versionCode,
        buildType,
        abis: badging.abis,
        webViewDebuggingExpected: expected.webViewDebugging,
        buildConfig,
        sha256,
        minSdk: badging.minSdk,
        targetSdk: badging.targetSdk,
        nativeLibrarySha256: nativeLibrary?.packagedHash ?? null,
        nativeLibraryReferenceSha256: nativeLibrary?.referenceHash ?? null,
        signing,
        verifiedAt: new Date().toISOString(),
    };
    if (metadataOut) {
        writeFileSync(metadataOut, `${JSON.stringify(metadata, null, 2)}\n`);
    }

    console.log(
        JSON.stringify(
            {
                apk: path.basename(apk),
                ...metadata,
                signing,
            },
            null,
            2,
        ),
    );
    return metadata;
}

function parseRawBooleanXmlAttribute(xml, name) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const match = String(xml).match(new RegExp(`android:${escaped}\\s*=\\s*"(true|false)"`));
    return match ? match[1] === "true" : null;
}

function verifyReleaseManifest({ manifest, expected }) {
    const manifestXml = readFileSync(manifest, "utf8");
    const debuggable = parseRawBooleanXmlAttribute(manifestXml, "debuggable") ?? false;
    const cleartext = parseRawBooleanXmlAttribute(manifestXml, "usesCleartextTraffic") ?? false;
    const schemes = [...manifestXml.matchAll(/android:scheme\s*=\s*"([^"]+)"/g)].map((match) => match[1]);

    const applicationId = expected.applicationId;
    if (!manifestXml.includes(`package="${applicationId}"`)) {
        fail(`release manifest does not declare package ${applicationId}`);
    }

    if (debuggable !== expected.debuggable) {
        fail(`release manifest debuggable expected ${expected.debuggable}, got ${String(debuggable)}`);
    }
    if (cleartext !== expected.cleartext) {
        fail(`release manifest cleartext expected ${expected.cleartext}, got ${String(cleartext)}`);
    }
    if (schemes.includes("tauritavern-e2e")) {
        fail("release manifest contains the forbidden E2E custom scheme");
    }
    if (manifestXml.includes("tauritavern-e2e://open")) {
        fail("release manifest contains the forbidden E2E deep link");
    }

    console.log(
        JSON.stringify(
            {
                manifest: path.basename(manifest),
                buildType: "release",
                applicationId,
                debuggable,
                cleartext,
                e2eSchemeAbsent: true,
            },
            null,
            2,
        ),
    );
}

function parseArgs(argv) {
    const options = {
        apk: null,
        releaseManifest: null,
        buildType: null,
        gitSha: process.env.GITHUB_SHA ?? "local",
        workflowRunId: process.env.GITHUB_RUN_ID ?? "local",
        metadataOut: null,
    };
    for (let index = 0; index < argv.length; index += 1) {
        const value = argv[index];
        const next = () => argv[++index];
        switch (value) {
            case "--apk":
                options.apk = path.resolve(REPO_ROOT, next());
                break;
            case "--release-manifest":
                options.releaseManifest = path.resolve(REPO_ROOT, next());
                break;
            case "--build-type":
                options.buildType = next();
                break;
            case "--git-sha":
                options.gitSha = next();
                break;
            case "--workflow-run-id":
                options.workflowRunId = next();
                break;
            case "--metadata-out":
                options.metadataOut = path.resolve(REPO_ROOT, next());
                break;
            default:
                fail(`Unknown option: ${value}`);
        }
    }
    return options;
}

const options = parseArgs(process.argv.slice(2));

const tauriConfig = JSON.parse(
    readFileSync(
        path.join(REPO_ROOT, "src-tauri", "crates", "tauritavern", "tauri.conf.json"),
        "utf8",
    ),
);
const versionName = tauriConfig.version;
const version = versionName.split(".").map(Number);
const computedVersionCode = version[0] * 1000000 + version[1] * 1000 + version[2];
const tauriPropertiesPath = path.join(
    REPO_ROOT,
    "src-tauri",
    "crates",
    "tauritavern",
    "gen",
    "android",
    "app",
    "tauri.properties",
);
const versionCode = existsSync(tauriPropertiesPath)
    ? Number(
          readFileSync(tauriPropertiesPath, "utf8")
              .split(/\r?\n/)
              .find((line) => line.startsWith("tauri.android.versionCode="))
              ?.split("=")[1] ?? computedVersionCode,
      )
    : computedVersionCode;

if (options.apk) {
    const expectedByType = {
        debug: {
            applicationId: "com.tauritavern.client.debug",
            versionName: `${versionName}-debug`,
            versionCode,
            debuggable: true,
            cleartext: true,
            abis: ["arm64-v8a"],
            nativeLibraries: ["lib/arm64-v8a/libtauritavern_lib.so"],
            nativeLibReference: "src-tauri/target/aarch64-linux-android/debug/libtauritavern_lib.so",
            minSdk: 26,
            targetSdk: 36,
            webViewDebugging: true,
            buildConfigDebug: true,
            buildConfigE2eEnabled: false,
            certificateSubject: null,
        },
        e2e: {
            applicationId: "com.tauritavern.client.e2e",
            versionName: `${versionName}-e2e`,
            versionCode,
            debuggable: true,
            cleartext: true,
            abis: ["arm64-v8a"],
            nativeLibraries: ["lib/arm64-v8a/libtauritavern_lib.so"],
            nativeLibReference: "src-tauri/target/aarch64-linux-android/release/libtauritavern_lib.so",
            minSdk: 26,
            targetSdk: 36,
            webViewDebugging: true,
            buildConfigDebug: true,
            buildConfigE2eEnabled: true,
            certificateSubject: "TauriTavern E2E",
        },
    };
    if (!expectedByType[options.buildType]) {
        fail(`Unsupported --build-type for APK verification: ${options.buildType}`);
    }
    verifyApk({
        apk: options.apk,
        buildType: options.buildType,
        expected: expectedByType[options.buildType],
        metadataOut: options.metadataOut,
        gitSha: options.gitSha,
        workflowRunId: options.workflowRunId,
    });
} else if (options.releaseManifest) {
    verifyReleaseManifest({
        manifest: options.releaseManifest,
        expected: {
            applicationId: "com.tauritavern.client",
            debuggable: false,
            cleartext: false,
        },
    });
} else {
    fail("Provide --apk or --release-manifest");
}
