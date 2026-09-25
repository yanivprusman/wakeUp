import type { NextConfig } from "next";

import path from 'node:path';
import fs from 'node:fs';

// Feedback-lib + launcher are workspace packages under /opt/automateLinux.
// Turbopack's root must contain the app AND the symlink targets, so it is
// /opt. Fail loud if the workspace is missing.
const turbopackRoot = path.resolve(process.cwd(), '../..');
const workspaceRoot = path.resolve(turbopackRoot, 'automateLinux');
if (!fs.existsSync(path.join(workspaceRoot, 'packages/feedback-lib/package.json'))) {
  throw new Error(`feedback-lib workspace guard: expected workspace at ${workspaceRoot}`);
}

const nextConfig: NextConfig = {
  turbopack: { root: turbopackRoot },
  transpilePackages: ['@claudecontrol/feedback-lib', '@addnewfeature/feedback-lib-launcher'],
  allowedDevOrigins: process.env.ALLOWED_DEV_ORIGINS?.split(',') ?? [],
  /* config options here */
};

export default nextConfig;
