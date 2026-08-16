#!/usr/bin/env node

import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { createWriteStream, existsSync, mkdirSync, readFileSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DEFAULT_PACKAGE = "com.tauritavern.client.debug";
const DEVTOOLS_SOCKET_PREFIX = "webview_devtools_remote_";

function fail(message) {
    throw new Error(message);
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function runAdb(args, { timeoutMs = 15000 } = {}) {
    const adb = resolveAdb();
    return new Promise((resolve, reject) => {
        const child = spawn(adb, args, {
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stdout = "";
        let stderr = "";
        const timer = setTimeout(() => {
            child.kill("SIGKILL");
            reject(new Error(`adb timed out after ${timeoutMs}ms: adb ${args.join(" ")}`));
        }, timeoutMs);
        child.stdout.on("data", (chunk) => {
            stdout += chunk;
        });
        child.stderr.on("data", (chunk) => {
            stderr += chunk;
        });
        child.on("error", (error) => {
            clearTimeout(timer);
            reject(error);
        });
        child.on("close", (code) => {
            clearTimeout(timer);
            if (code === 0) {
                resolve(stdout);
            } else {
                reject(
                    new Error(
                        `adb ${args.join(" ")} exited with ${code}: ${stderr.trim() || stdout.trim()}`,
                    ),
                );
            }
        });
    });
}

function resolveAdb() {
    const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    const platformTools = androidHome ? path.join(androidHome, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb") : null;
    if (platformTools && existsSync(platformTools)) {
        return platformTools;
    }
    return "adb";
}

function adbArgs(serial, args) {
    return serial ? ["-s", serial, ...args] : args;
}

async function findDevices() {
    const output = await runAdb(["devices"]);
    return output
        .split(/\r?\n/)
        .slice(1)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
            const [serial, ...stateParts] = line.split(/\s+/);
            return { serial, state: stateParts.join(" ") };
        });
}

export function parseProcNetUnix(text) {
    const entries = [];
    for (const line of String(text).split(/\r?\n/)) {
        const columns = line.trim().split(/\s+/);
        if (columns.length < 8) {
            continue;
        }
        const inode = columns[6];
        if (!/^\d+$/.test(inode)) {
            continue;
        }
        entries.push({
            inode,
            path: columns.slice(7).join(" "),
        });
    }
    return entries;
}

export function parseFdSymlinks(text) {
    const entries = [];
    for (const line of String(text).split(/\r?\n/)) {
        const match = line.match(/->\s*socket:\[(\d+)\]/);
        if (match) {
            entries.push({ inode: match[1] });
        }
    }
    return entries;
}

export function selectWebViewDevtoolsSocketForPid(procNetUnixText, fdListText, pid) {
    const normalizedPid = String(pid).trim();
    if (!/^\d+$/.test(normalizedPid)) {
        throw new Error(`Package PID is not a single integer: ${JSON.stringify(pid)}`);
    }

    const unixEntries = parseProcNetUnix(procNetUnixText);
    const pidInodes = new Set(parseFdSymlinks(fdListText).map((entry) => entry.inode));
    const candidates = [];
    const seen = new Set();
    for (const entry of unixEntries) {
        const socketPath = entry.path.startsWith("@") ? entry.path.slice(1) : entry.path;
        if (!socketPath.startsWith(DEVTOOLS_SOCKET_PREFIX)) {
            continue;
        }
        if (!pidInodes.has(entry.inode)) {
            continue;
        }
        const key = `${entry.inode}:${socketPath}`;
        if (!seen.has(key)) {
            seen.add(key);
            candidates.push(socketPath);
        }
    }

    if (candidates.length === 0) {
        throw new Error(
            `No webview_devtools_remote_* socket found for PID ${normalizedPid}; ` +
                "expected a debuggable build with WebView CDP enabled.",
        );
    }
    if (candidates.length > 1) {
        throw new Error(
            `Expected exactly one webview_devtools_remote_* socket for PID ${normalizedPid}, found ${candidates.length}: ${candidates.join(", ")}`,
        );
    }
    return candidates[0];
}

async function waitFor(check, { timeoutMs = 30000, intervalMs = 500, description = "condition" } = {}) {
    const deadline = Date.now() + timeoutMs;
    let lastError;
    while (Date.now() < deadline) {
        try {
            const value = await check();
            if (value) {
                return value;
            }
        } catch (error) {
            lastError = error;
        }
        await sleep(intervalMs);
    }
    throw new Error(
        `Timed out waiting for ${description}${lastError ? `: ${lastError.message}` : ""}`,
    );
}

async function pickFreeTcpPort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.unref();
        server.on("error", reject);
        server.listen(0, "127.0.0.1", () => {
            const address = server.address();
            const port = typeof address === "object" && address ? address.port : 0;
            server.close(() => resolve(port));
        });
    });
}

class CdpClient {
    constructor(webSocketUrl) {
        this.webSocketUrl = webSocketUrl;
        this.socket = null;
        this.nextId = 1;
        this.pending = new Map();
        this.opened = null;
    }

    async connect(timeoutMs = 10000) {
        if (!globalThis.WebSocket) {
            throw new Error("CDP requires a Node.js runtime with a global WebSocket client (Node >= 22).");
        }
        this.socket = new WebSocket(this.webSocketUrl);
        this.opened = new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("CDP WebSocket open timed out")), timeoutMs);
            this.socket.addEventListener("open", () => {
                clearTimeout(timer);
                resolve();
            });
            this.socket.addEventListener("error", (event) => {
                clearTimeout(timer);
                reject(new Error(`CDP WebSocket error: ${event.message || "connection failed"}`));
            });
        });
        this.socket.addEventListener("message", (event) => {
            const message = JSON.parse(String(event.data));
            if (message.id && this.pending.has(message.id)) {
                const { resolve, reject } = this.pending.get(message.id);
                this.pending.delete(message.id);
                if (message.error) {
                    reject(new Error(`${message.error.message} (${message.error.code})`));
                } else {
                    resolve(message.result);
                }
            }
        });
        await this.opened;
        return this;
    }

    send(method, params = {}) {
        if (!this.socket || this.socket.readyState !== 1) {
            return Promise.reject(new Error("CDP WebSocket is not open"));
        }
        const id = this.nextId;
        this.nextId += 1;
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            this.socket.send(JSON.stringify({ id, method, params }));
            setTimeout(() => {
                if (this.pending.has(id)) {
                    this.pending.delete(id);
                    reject(new Error(`CDP command timed out: ${method}`));
                }
            }, 20000).unref?.();
        });
    }

    close() {
        if (this.socket) {
            try {
                this.socket.close();
            } catch {
                // Ignore close failures; ADB forward cleanup is authoritative.
            }
            this.socket = null;
        }
    }
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`GET ${url} returned HTTP ${response.status}`);
    }
    return response.json();
}

async function cdpEvaluate(cdp, expression) {
    const result = await cdp.send("Runtime.evaluate", {
        expression,
        returnByValue: true,
        awaitPromise: true,
    });
    if (result.exceptionDetails) {
        const description = result.exceptionDetails.exception?.description || result.exceptionDetails.text;
        throw new Error(`Runtime.evaluate failed: ${description}`);
    }
    return result.result?.value;
}

async function waitForPageUrl(cdp, predicate, description, timeoutMs = 20000) {
    return waitFor(
        async () => {
            const url = await cdpEvaluate(cdp, "location.href");
            return predicate(url) ? url : null;
        },
        { timeoutMs, intervalMs: 250, description },
    );
}

function normalizePackagePid(output) {
    const pids = String(output)
        .trim()
        .split(/\s+/)
        .filter((value) => /^\d+$/.test(value));
    if (pids.length !== 1) {
        throw new Error(`Expected exactly one PID, got ${JSON.stringify(pids)} from ${JSON.stringify(output.trim())}`);
    }
    return pids[0];
}

function parseDumpsysVersion(output) {
    const versionName = output.match(/versionName=([^\s]+)/)?.[1];
    const versionCode = output.match(/versionCode=([^\s]+)/)?.[1];
    return { versionName: versionName ?? null, versionCode: versionCode ? Number(versionCode) : null };
}

function parseWebViewProvider(output) {
    const text = String(output);
    const current = text.match(
        /Current WebView package \(name, version\):\s*\(([^,]+),\s*([^)]+)\)/i,
    );
    const valid = text.match(/Valid package\s+(\S+)\s+\(versionName:\s*([^,\s]+)/i);
    const packageName = current?.[1] ?? valid?.[1] ?? text.match(/packageName\s*=\s*([^\s,\]]+)/i)?.[1];
    const versionName = current?.[2] ?? valid?.[2] ?? text.match(/versionName\s*=\s*([^\s,\]]+)/i)?.[1];
    return {
        packageName: packageName?.trim() || null,
        versionName: versionName?.trim() || null,
    };
}

function systemUiFlagNames(value) {
    const flags = [];
    if ((value & 0x0002) !== 0) flags.push("HIDE_NAVIGATION");
    if ((value & 0x0004) !== 0) flags.push("FULLSCREEN");
    if ((value & 0x0200) !== 0) flags.push("LAYOUT_HIDE_NAVIGATION");
    if ((value & 0x0400) !== 0) flags.push("LAYOUT_FULLSCREEN");
    if ((value & 0x1000) !== 0) flags.push("IMMERSIVE_STICKY");
    if ((value & 0x0800) !== 0) flags.push("LAYOUT_STABLE");
    return flags;
}

function parseSystemUiVisibility(...dumps) {
    const text = dumps.join("\n");
    const numeric =
        text.match(/mSystemUiVisibility\s*=\s*(0x[0-9a-fA-F]+)/) ??
        text.match(/mLastSystemUiFlags\s*=\s*(0x[0-9a-fA-F]+)/) ??
        text.match(/systemUiVisibility\s*=\s*(0x[0-9a-fA-F]+)/);
    if (numeric) {
        const value = Number.parseInt(numeric[1], 16);
        return {
            raw: numeric[1],
            value,
            flags: systemUiFlagNames(value),
        };
    }

    // Android 12+ exposes the live per-window system UI state as named flags,
    // e.g. `vsysui=LAYOUT_STABLE IMMERSIVE_STICKY ...`.
    const named = text.match(/vsysui=([A-Z_ ]+)/);
    if (named) {
        return {
            raw: named[1].trim(),
            value: null,
            flags: named[1].trim().split(/\s+/).filter(Boolean),
        };
    }
    return null;
}

async function collectAdbDiagnostics(serial, pkg) {
    const [release, sdk, packageDump, webviewDump, windowDump, windowFullDump, activitiesDump, wmSize, wmDensity] =
        await Promise.all([
            runAdb(adbArgs(serial, ["shell", "getprop", "ro.build.version.release"])),
            runAdb(adbArgs(serial, ["shell", "getprop", "ro.build.version.sdk"])),
            runAdb(adbArgs(serial, ["shell", "dumpsys", "package", pkg])),
            runAdb(adbArgs(serial, ["shell", "dumpsys", "webviewupdate"])),
            runAdb(adbArgs(serial, ["shell", "dumpsys", "window", "windows"])),
            runAdb(adbArgs(serial, ["shell", "dumpsys", "window"])),
            runAdb(adbArgs(serial, ["shell", "dumpsys", "activity", "activities"])),
            runAdb(adbArgs(serial, ["shell", "wm", "size"])),
            runAdb(adbArgs(serial, ["shell", "wm", "density"])),
        ]);

    const version = parseDumpsysVersion(packageDump);
    const webViewProvider = parseWebViewProvider(webviewDump);
    const resumed = activitiesDump.match(/mResumedActivity:[^\n]*/)?.[0]?.trim() ?? null;
    const currentFocus =
        windowDump.match(/mCurrentFocus=Window\{[^\n]*/)?.[0]?.trim() ??
        windowFullDump.match(/mCurrentFocus=Window\{[^\n]*/)?.[0]?.trim() ??
        windowFullDump.match(/mFocusedApp=[^\n]*/)?.[0]?.trim() ??
        null;
    const systemUi = parseSystemUiVisibility(windowDump, windowFullDump);

    return {
        packageName: pkg,
        versionName: version.versionName,
        versionCode: version.versionCode,
        androidRelease: release.trim() || null,
        androidSdk: Number.parseInt(sdk.trim(), 10) || null,
        webViewPackage: webViewProvider.packageName,
        webViewVersion: webViewProvider.versionName,
        display: {
            size: wmSize.trim() || null,
            density: wmDensity.trim() || null,
        },
        immersiveSystemUi: systemUi,
        activityResumed: resumed,
        windowFocused: currentFocus,
    };
}

async function getUniquePid(serial, pkg) {
    return waitFor(async () => {
        const output = await runAdb(adbArgs(serial, ["shell", "pidof", pkg]));
        if (!output.trim()) {
            return null;
        }
        return normalizePackagePid(output);
    }, { timeoutMs: 30000, intervalMs: 500, description: `unique PID for ${pkg}` });
}

async function readPidFdTable(serial, pkg, pid) {
    try {
        return await runAdb(adbArgs(serial, ["shell", "ls", "-l", `/proc/${pid}/fd`]));
    } catch (error) {
        // Android 11+ hides another app's /proc/<pid>/fd from the shell user.
        // Debug/E2E packages are debuggable, so `run-as` can observe their own
        // file descriptors without root. Production is never probed because it
        // has no WebView DevTools socket.
        try {
            return await runAdb(adbArgs(serial, ["shell", "run-as", pkg, "ls", "-l", `/proc/${pid}/fd`]));
        } catch {
            throw error;
        }
    }
}

async function discoverSocket(serial, pkg, pid, timeoutMs = 30000) {
    return waitFor(async () => {
        const [unixTable, fdTable] = await Promise.all([
            runAdb(adbArgs(serial, ["shell", "cat", "/proc/net/unix"])),
            readPidFdTable(serial, pkg, pid),
        ]);
        return selectWebViewDevtoolsSocketForPid(unixTable, fdTable, pid);
    }, { timeoutMs, intervalMs: 500, description: `WebView DevTools socket for PID ${pid}` });
}

async function launchPackage(serial, pkg) {
    await runAdb(
        adbArgs(serial, ["shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"]),
        { timeoutMs: 60000 },
    );
}

async function waitForResumed(serial, pkg) {
    return waitFor(async () => {
        const output = await runAdb(adbArgs(serial, ["shell", "dumpsys", "activity", "activities"]));
        return output.includes(`mResumedActivity`) && output.includes(pkg);
    }, { timeoutMs: 60000, intervalMs: 1000, description: `${pkg} resumed Activity` });
}

async function connectToMainPage(serial, pkg) {
    const pid = await getUniquePid(serial, pkg);
    const socketName = await discoverSocket(serial, pkg, pid);
    const localPort = await pickFreeTcpPort();
    const forwardKey = `tcp:${localPort}`;

    try {
        await runAdb(adbArgs(serial, ["forward", forwardKey, `localabstract:${socketName}`]));

        // The DevTools HTTP endpoint may become reachable before the WebView
        // publishes its first page target. Wait for exactly one target; zero
        // candidates keep polling and multiple candidates fail closed.
        const targetList = await waitFor(
            async () => {
                const list = await fetchJson(`http://127.0.0.1:${localPort}/json/list`);
                const pageTargets = list.filter(
                    (target) => target.type === "page" && target.webSocketDebuggerUrl,
                );
                if (pageTargets.length > 1) {
                    throw new Error(
                        `Expected exactly one main WebView page target, found ${pageTargets.length}: ${list.map((target) => target.url).join(", ")}`,
                    );
                }
                return pageTargets.length === 1 ? { list, pageTargets } : null;
            },
            {
                timeoutMs: 30000,
                intervalMs: 500,
                description: "unique WebView page target",
            },
        );

        const target = targetList.pageTargets[0];
        const versionInfo = await fetchJson(`http://127.0.0.1:${localPort}/json/version`);
        const cdp = await new CdpClient(target.webSocketDebuggerUrl).connect();
        await cdp.send("Page.enable");
        await cdp.send("Runtime.enable");
        return { pid, socketName, localPort, forwardKey, versionInfo, target, cdp };
    } catch (error) {
        await removeAdbForward(serial, forwardKey).catch(() => {});
        throw error;
    }
}

async function removeAdbForward(serial, forwardKey) {
    await runAdb(adbArgs(serial, ["forward", "--remove", forwardKey])).catch(() => {});
    const forwardList = await runAdb(adbArgs(serial, ["forward", "--list"])).catch(() => "");
    if (forwardList.includes(forwardKey)) {
        throw new Error(`ADB forward cleanup failed; ${forwardKey} is still present`);
    }
}

async function captureScreenshot(cdp, localPath) {
    const result = await cdp.send("Page.captureScreenshot", { format: "png" });
    if (!result.data) {
        throw new Error("Page.captureScreenshot returned no image data");
    }
    mkdirSync(path.dirname(path.resolve(localPath)), { recursive: true });
    createWriteStream(localPath).end(Buffer.from(result.data, "base64"));
}

async function recordScreen(serial, localPath, seconds = 5) {
    const remotePath = `/sdcard/tauritavern-e2e-${Date.now()}.mp4`;
    mkdirSync(path.dirname(path.resolve(localPath)), { recursive: true });
    await runAdb(
        adbArgs(serial, [
            "shell",
            "screenrecord",
            "--bit-rate",
            "8000000",
            "--time-limit",
            String(seconds),
            remotePath,
        ]),
        { timeoutMs: seconds * 1000 + 20000 },
    );
    await runAdb(adbArgs(serial, ["pull", remotePath, localPath]), { timeoutMs: 60000 });
    await runAdb(adbArgs(serial, ["shell", "rm", "-f", remotePath])).catch(() => {});
}

async function inspectOwnPage(cdp) {
    const pageState = await cdpEvaluate(
        cdp,
        `(() => ({
            url: location.href,
            sheldPresent: Boolean(document.getElementById('sheld')),
            insetsBridgePresent: typeof window.__TAURITAVERN_INSETS__ === 'object',
            systemUiBridgePresent: typeof window.TauriTavernAndroidSystemUiBridge === 'object',
            immersiveFullscreenEnabled:
                typeof window.TauriTavernAndroidSystemUiBridge?.isImmersiveFullscreenEnabled === 'function'
                    ? window.TauriTavernAndroidSystemUiBridge.isImmersiveFullscreenEnabled()
                    : null,
            viewport: {
                innerWidth,
                innerHeight,
                devicePixelRatio,
                visualViewportWidth: window.visualViewport?.width ?? null,
                visualViewportHeight: window.visualViewport?.height ?? null,
            },
        }))()`,
    );
    return pageState;
}

async function startFixtureServer() {
    const html = `<!doctype html>
<html>
  <head><meta charset="utf-8"><title>E2E external fixture</title></head>
  <body><h1>external-fixture</h1><p>This page intentionally has no #sheld.</p></body>
</html>`;
    return new Promise((resolve, reject) => {
        const server = createServer((request, response) => {
            response.writeHead(200, {
                "content-type": "text/html; charset=utf-8",
                "cache-control": "no-store",
            });
            response.end(html);
        });
        server.on("error", reject);
        server.listen(0, "127.0.0.1", () => {
            resolve(server);
        });
    });
}

async function runExternalFixtureCheck(serial, cdp) {
    const fixture = await startFixtureServer();
    const hostPort = fixture.address().port;
    const devicePort = await pickFreeTcpPort();
    let reverseKey = null;
    try {
        await runAdb(adbArgs(serial, ["reverse", `tcp:${devicePort}`, `tcp:${hostPort}`]));
        reverseKey = `tcp:${devicePort}`;
        const fixtureUrl = `http://127.0.0.1:${devicePort}/`;
        await cdp.send("Page.navigate", { url: fixtureUrl });
        const settledUrl = await waitForPageUrl(cdp, (url) => url.startsWith(fixtureUrl), `external fixture URL ${fixtureUrl}`);
        await sleep(750);

        // Instrument `document.getElementById('sheld')` and observe the page
        // for a quiet window. A leaked readiness poll evaluates a script that
        // calls exactly this API on the page, so any lookup in this window is
        // observable page-level interference.
        await cdpEvaluate(
            cdp,
            `(() => {
                window.__ttE2eNativeSheldLookups = 0;
                const original = Document.prototype.getElementById;
                Document.prototype.getElementById = function(id) {
                    if (id === 'sheld') {
                        window.__ttE2eNativeSheldLookups += 1;
                    }
                    return original.call(this, id);
                };
                return true;
            })()`,
        );
        await sleep(1500);
        const sheldLookups = await cdpEvaluate(cdp, "window.__ttE2eNativeSheldLookups");
        if (sheldLookups !== 0) {
            throw new Error(
                `External page observed ${sheldLookups} native #sheld readiness lookups during the quiet window`,
            );
        }

        const state = await cdpEvaluate(
            cdp,
            `(() => ({
                url: location.href,
                sheldPresent: Boolean(document.getElementById('sheld')),
                insetsBridgePresent: typeof window.__TAURITAVERN_INSETS__ === 'object',
                systemUiBridgePresent: typeof window.TauriTavernAndroidSystemUiBridge === 'object',
                insetTop: getComputedStyle(document.documentElement).getPropertyValue('--tt-inset-top').trim(),
                title: document.title,
            }))()`,
        );

        const expected = {
            url: settledUrl,
            sheldPresent: false,
            insetsBridgePresent: false,
            insetTop: "",
        };
        for (const [key, expectedValue] of Object.entries(expected)) {
            if (state[key] !== expectedValue) {
                throw new Error(
                    `External page contract violated for ${key}: expected ${JSON.stringify(expectedValue)}, got ${JSON.stringify(state[key])}`,
                );
            }
        }
        return { fixtureUrl, externalPage: state, sheldLookupsDuringQuietWindow: sheldLookups };
    } finally {
        if (reverseKey) {
            await runAdb(adbArgs(serial, ["reverse", "--remove", reverseKey])).catch(() => {});
        }
        await new Promise((resolve) => fixture.close(resolve));
    }
}

function inferBuildType(packageName) {
    if (packageName.endsWith(".debug")) {
        return "debug";
    }
    if (packageName.endsWith(".e2e")) {
        return "e2e";
    }
    return "release";
}

async function main() {
    const options = parseOptions(process.argv.slice(2));
    const buildMetadata = options.buildMetadata
        ? JSON.parse(readFileSync(path.resolve(options.buildMetadata), "utf8"))
        : null;
    const serial = options.serial ?? null;
    const devices = await findDevices();
    const selectedDevice = serial
        ? devices.find((device) => device.serial === serial)
        : devices.length === 1
          ? devices[0]
          : null;

    if (!selectedDevice) {
        const message = serial
            ? `Requested device ${serial} is not connected.`
            : devices.length === 0
              ? "No Android device/emulator is connected; arm64 runtime smoke cannot run on this host."
              : `Expected exactly one connected Android device, found ${devices.length}. Use --serial.`;
        if (options.requireDevice) {
            fail(message);
        }
        console.log(`SKIP: ${message}`);
        return { skipped: true, reason: message };
    }

    const serialId = selectedDevice.serial;
    const pkg = options.package || DEFAULT_PACKAGE;
    const session = {
        serial: serialId,
        pkg,
        forwardKey: null,
        cdp: null,
        launched: false,
        shouldRestart: false,
    };

    const cleanup = async ({ restart = session.shouldRestart } = {}) => {
        if (session.cdp) {
            session.cdp.close();
            session.cdp = null;
        }
        if (session.forwardKey) {
            await removeAdbForward(serialId, session.forwardKey).catch((error) => {
                console.error(`Forward cleanup failed: ${error.message}`);
            });
            session.forwardKey = null;
        }
        if (restart) {
            await runAdb(adbArgs(serialId, ["shell", "am", "force-stop", pkg])).catch(() => {});
            await launchPackage(serialId, pkg).catch(() => {});
            await waitForResumed(serialId, pkg).catch(() => {});
        }
    };

    const signalHandler = async () => {
        await cleanup();
        process.exit(130);
    };
    process.once("SIGINT", signalHandler);
    process.once("SIGTERM", signalHandler);

    try {
        if (options.apk) {
            await runAdb(adbArgs(serialId, ["install", "-r", options.apk]), { timeoutMs: 180000 });
        }
        await runAdb(adbArgs(serialId, ["shell", "am", "force-stop", pkg])).catch(() => {});
        await launchPackage(serialId, pkg);
        session.launched = true;
        await waitForResumed(serialId, pkg);

        const connection = await connectToMainPage(serialId, pkg);
        session.forwardKey = connection.forwardKey;
        session.cdp = connection.cdp;
        const cdp = connection.cdp;

        const adbDiagnostics = await collectAdbDiagnostics(serialId, pkg);
        const userAgent = await cdpEvaluate(cdp, "navigator.userAgent");
        const initialPage = await inspectOwnPage(cdp);
        const diagnostics = {
            ...adbDiagnostics,
            deviceSerial: serialId,
            pid: connection.pid,
            socketName: connection.socketName,
            localPort: connection.localPort,
            cdpBrowser: connection.versionInfo.Browser,
            cdpProtocolVersion: connection.versionInfo["Protocol-Version"],
            userAgent,
            userAgentFromDevtoolsVersion: connection.versionInfo["User-Agent"],
            webKitVersion: connection.versionInfo["WebKit-Version"],
            pageTarget: connection.target.url,
            currentUrl: initialPage.url,
            viewport: initialPage.viewport,
            sheldPresent: initialPage.sheldPresent,
            insetsBridgePresent: initialPage.insetsBridgePresent,
            systemUiBridgePresent: initialPage.systemUiBridgePresent,
            immersiveFullscreenEnabled: initialPage.immersiveFullscreenEnabled,
        };

        const actions = {};
        if (options.tap) {
            const [x, y] = options.tap.split(",").map(Number);
            if (!Number.isFinite(x) || !Number.isFinite(y)) {
                fail(`Invalid --tap value: ${options.tap}`);
            }
            await cdp.send("Input.dispatchTouchEvent", {
                type: "touchStart",
                touchPoints: [{ x, y }],
            });
            await cdp.send("Input.dispatchTouchEvent", {
                type: "touchEnd",
                touchPoints: [],
            });
            actions.tap = { x, y };
        }

        if (options.navigate) {
            await cdp.send("Page.navigate", { url: options.navigate });
            actions.navigatedTo = await waitForPageUrl(
                cdp,
                (url) => url === options.navigate || url.startsWith(`${options.navigate}#`),
                `navigation to ${options.navigate}`,
            );
            diagnostics.currentUrl = actions.navigatedTo;
            session.shouldRestart = true;
        }

        if (options.eval) {
            actions.evaluation = await cdpEvaluate(cdp, options.eval);
        }

        if (options.smoke || options.fixture) {
            const fixtureResult = await runExternalFixtureCheck(serialId, cdp);
            diagnostics.externalFixture = fixtureResult.externalPage;
            diagnostics.externalFixtureUrl = fixtureResult.fixtureUrl;
            diagnostics.externalSheldLookupsDuringQuietWindow = fixtureResult.sheldLookupsDuringQuietWindow;
            session.shouldRestart = true;

            if (options.screenshot || options.smoke) {
                const screenshotPath =
                    options.screenshot ||
                    path.join(REPO_ROOT, "dist", `e2e-smoke-${pkg}.png`);
                await captureScreenshot(cdp, screenshotPath);
                actions.screenshot = path.resolve(screenshotPath);
            }

            if (options.screenrecord) {
                await recordScreen(serialId, options.screenrecord, options.screenrecordSeconds);
                actions.screenrecord = path.resolve(options.screenrecord);
            }

            // Restore/restart and prove that an own page re-establishes the
            // #sheld gate and the insets bridge without leaving test state behind.
            await cleanup({ restart: true });
            session.shouldRestart = false;
            const restored = await connectToMainPage(serialId, pkg);
            session.forwardKey = restored.forwardKey;
            session.cdp = restored.cdp;
            const restoredPage = await waitFor(async () => {
                const state = await inspectOwnPage(restored.cdp);
                if (state.sheldPresent && state.insetsBridgePresent && state.systemUiBridgePresent) {
                    return state;
                }
                return null;
            }, {
                timeoutMs: 30000,
                intervalMs: 1000,
                description: "restored own page #sheld, insets bridge and system UI bridge",
            });
            diagnostics.restoredOwnPage = restoredPage;
            diagnostics.restoredCurrentUrl = restoredPage.url;

            // Smoke is a CI-style verification: after proving the app restores
            // to its own page, leave no process/package state behind.
            if (options.smoke) {
                await runAdb(adbArgs(serialId, ["shell", "am", "force-stop", pkg]));
                session.launched = false;
                diagnostics.stoppedAfterRestore = true;
            }
        } else if (options.screenshot) {
            await captureScreenshot(cdp, options.screenshot);
            actions.screenshot = path.resolve(options.screenshot);
        }

        if (options.screenrecord && !options.smoke && !options.fixture) {
            await recordScreen(serialId, options.screenrecord, options.screenrecordSeconds);
            actions.screenrecord = path.resolve(options.screenrecord);
        }

        const output = {
            packageName: pkg,
            buildType: buildMetadata?.buildType ?? inferBuildType(pkg),
            gitSha: buildMetadata?.gitSha ?? null,
            buildMetadataSha256: buildMetadata?.sha256 ?? null,
            diagnostics,
            actions,
        };

        if (options.json) {
            console.log(JSON.stringify(output, null, 2));
        } else {
            console.log(JSON.stringify(output, null, 2));
        }
        return output;
    } finally {
        process.removeListener("SIGINT", signalHandler);
        process.removeListener("SIGTERM", signalHandler);
        await cleanup();
    }
}

function parseOptions(argv) {
    const options = {
        package: DEFAULT_PACKAGE,
        serial: null,
        apk: null,
        navigate: null,
        eval: null,
        tap: null,
        screenshot: null,
        screenrecord: null,
        screenrecordSeconds: 5,
        smoke: false,
        fixture: false,
        json: false,
        requireDevice: false,
        buildMetadata: null,
    };

    for (let index = 0; index < argv.length; index += 1) {
        const value = argv[index];
        const readValue = (name) => {
            const next = argv[index + 1];
            if (!next) {
                fail(`Missing value for ${name}`);
            }
            index += 1;
            return next;
        };
        switch (value) {
            case "--package":
                options.package = readValue("--package");
                break;
            case "--serial":
                options.serial = readValue("--serial");
                break;
            case "--apk":
                options.apk = readValue("--apk");
                break;
            case "--navigate":
                options.navigate = readValue("--navigate");
                break;
            case "--eval":
                options.eval = readValue("--eval");
                break;
            case "--tap":
                options.tap = readValue("--tap");
                break;
            case "--screenshot":
                options.screenshot = readValue("--screenshot");
                break;
            case "--screenrecord":
                options.screenrecord = readValue("--screenrecord");
                break;
            case "--screenrecord-seconds":
                options.screenrecordSeconds = Number(readValue("--screenrecord-seconds"));
                break;
            case "--smoke":
                options.smoke = true;
                options.fixture = true;
                break;
            case "--fixture":
                options.fixture = true;
                break;
            case "--json":
                options.json = true;
                break;
            case "--build-metadata":
                options.buildMetadata = readValue("--build-metadata");
                break;
            case "--require-device":
                options.requireDevice = true;
                break;
            case "--help":
                console.log(`Usage: node scripts/android-webview-preflight.mjs [options]

Options:
  --package <id>              Android package (default: ${DEFAULT_PACKAGE})
  --serial <serial>           ADB device serial; exactly one device required otherwise
  --apk <path>                Install this APK before launching
  --navigate <url>            Navigate the main frame via CDP
  --eval <expression>         Runtime.evaluate and include the returned value
  --tap <x>,<y>               Dispatch one CDP touch tap
  --screenshot <path>         Page.captureScreenshot to a local PNG
  --screenrecord <path>       adb screenrecord and pull to a local MP4
  --screenrecord-seconds <n>  screenrecord duration (default: 5)
  --smoke                     External fixture + screenshot + restore own page
  --fixture                   External fixture without default screenshot
  --build-metadata <path>     Include buildType/gitSha/SHA-256 from metadata JSON
  --require-device             Fail when no unique ADB device is attached
  --json                       Emit machine-readable JSON`);
                process.exit(0);
            default:
                fail(`Unknown option: ${value}`);
        }
    }
    return options;
}

const isMain =
    process.argv[1] &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (isMain) {
    main()
        .then(() => process.exit(0))
        .catch((error) => {
            console.error(`FAIL: ${error.stack || error.message || error}`);
            process.exit(1);
        });
}
