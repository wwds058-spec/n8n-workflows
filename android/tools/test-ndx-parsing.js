#!/usr/bin/env node
/**
 * Regression test for the NDX parsing in app/src/main/assets/index.html.
 *
 * OCR on a photographed form is never clean, so extractNDX() has to cope with
 * lookalike characters and a missed prefix. These cases are drawn from the
 * ways Tesseract actually garbles printed NDX numbers.
 *
 *   node android/tools/test-ndx-parsing.js
 */
const fs = require('fs');
const path = require('path');

const html = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html'),
  'utf8'
);
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].pop()[1];
const slice = (from, to) => script.slice(script.indexOf(from), script.indexOf(to));

// Pull in just the list and the parsing helpers; the rest needs a DOM.
const source =
  script.slice(script.indexOf('const NOT_COLLECTED'), script.indexOf(']);') + 3) +
  '\n' +
  slice('// ============ NDX PARSING ============', '// ============ RESULT DISPLAY ============');

const { extractNDX } = (new Function(source + '; return { extractNDX };'))();

const cases = [
  ['NDX3982584', 'NDX3982584', false, 'clean read'],
  ['EPIC NDX3982584 RANGA RAO', 'NDX3982584', false, 'surrounded by form text'],
  ['314\nNDX3982584\nRANGA RAO POLISETTY', 'NDX3982584', false, 'three-line band'],
  ['NDX398258A', 'NDX3982584', false, '4 read as A'],
  ['NDX39825B4', 'NDX3982584', false, '8 read as B'],
  ['N0X3982584', 'NDX3982584', false, 'D read as 0 in prefix'],
  ['MDX 3982584', 'NDX3982584', false, 'N read as M'],
  ['3982584', 'NDX3982584', false, 'prefix missed entirely'],
  ['NDX O437749', 'NDX0437749', false, '0 read as O'],
  ['NDX I085950', 'NDX1085950', false, '1 read as I'],
  ['NDX2S26154', 'NDX2826154', true, 'one char off a listed number'],
  ['NDX9999999', 'NDX9999999', false, 'well formed, not on the list'],
  ['random noise', null, false, 'no number present'],
  ['', null, false, 'empty input'],
];

let failed = 0;
for (const [input, wantNdx, wantCorrected, label] of cases) {
  const got = extractNDX(input);
  const gotNdx = got ? got.ndx : null;
  const gotCorrected = got ? !!got.corrected : false;
  const ok = gotNdx === wantNdx && gotCorrected === wantCorrected;
  if (!ok) failed++;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${label.padEnd(32)} ${String(gotNdx).padEnd(12)}` +
    `${gotCorrected ? ' (needs confirming)' : ''}`
  );
}

console.log(`\n${cases.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
