import fs from "node:fs";
import path from "node:path";
import {
    install,
    detectBrowserPlatform,
    resolveBuildId,
    computeExecutablePath,
    Browser,
    BrowserTag,
} from "@puppeteer/browsers";

// Browsers are only needed for `wxt dev`; CI builds and tests do not use them.
if (process.env.CI) {
    console.log("CI detected, skipping browser download");
    process.exit(0);
}

const cacheDir = path.join(process.cwd(), ".cache", "browsers");
fs.mkdirSync(cacheDir, { recursive: true });

const platform = detectBrowserPlatform();
if (!platform) throw new Error("Unsupported platform");

const targets = [Browser.CHROME, Browser.FIREFOX];
const out = {};

for (const browser of targets) {
    const buildId = await resolveBuildId(browser, platform, BrowserTag.STABLE);
    await install({ browser, buildId, cacheDir, platform });
    out[browser] = computeExecutablePath({ browser, buildId, cacheDir, platform });
    console.log(`${browser}: ${out[browser]}`);
}

fs.writeFileSync(path.join(cacheDir, "paths.json"), JSON.stringify(out, null, 2));
