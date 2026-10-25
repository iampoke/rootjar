#!/usr/bin/env node
/*
 * Syntax-checks the panel's inline JavaScript without a browser.
 * Usage: node scripts/verify-js.js [path/to/index.html]
 * Exits 1 with the node --check output when the script block does not parse.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const htmlPath = process.argv[2] || path.join(__dirname, '..', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');

// Inline blocks only: the panel has no <script src> tags today, but skip any if they appear.
const blocks = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)];
if (blocks.length === 0) {
  console.error(`verify-js: no inline <script> block found in ${htmlPath}`);
  process.exit(1);
}

let failed = false;
blocks.forEach((m, i) => {
  const body = m[1];
  if (!body.trim()) return;
  const line = html.slice(0, m.index).split('\n').length;
  const tmp = path.join(os.tmpdir(), `rootjar-index-script-${i}.js`);
  fs.writeFileSync(tmp, body);
  const res = spawnSync(process.execPath, ['--check', tmp], { encoding: 'utf8' });
  if (res.status !== 0) {
    failed = true;
    console.error(`verify-js: inline script #${i + 1} (starts at index.html:${line}) failed:`);
    console.error((res.stderr || '').trim());
  }
  fs.unlinkSync(tmp);
});

if (failed) process.exit(1);
console.log(`verify-js: ${blocks.length} inline script block(s) in ${path.basename(htmlPath)} parse OK`);
