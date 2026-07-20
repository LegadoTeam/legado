#!/usr/bin/env node
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repositoryRoot = path.resolve(__dirname, '..', '..');
const bundlePath = path.join(
  repositoryRoot,
  'app/src/main/assets/web/help/js/highlight.min.js'
);
const markdownRoot = path.join(
  repositoryRoot,
  'app/src/main/assets/web/help/md'
);
const bundle = fs.readFileSync(bundlePath, 'utf8');
const context = vm.createContext({});

vm.runInContext(bundle, context, {
  filename: bundlePath,
  timeout: 10_000,
});

assert.ok(context.hljs, 'highlight.js did not expose hljs');
assert.deepStrictEqual(
  Array.from(context.hljs.listLanguages()).sort(),
  ['java', 'javascript', 'plaintext', 'xml']
);
assert.strictEqual(
  context.hljs.getLanguage('js'),
  context.hljs.getLanguage('javascript'),
  'js alias does not resolve to javascript'
);
assert.strictEqual(
  context.hljs.getLanguage('html'),
  context.hljs.getLanguage('xml'),
  'html alias does not resolve to xml'
);
assert.strictEqual(
  context.hljs.getLanguage('txt'),
  context.hljs.getLanguage('plaintext'),
  'txt alias does not resolve to plaintext'
);

const highlighted = vm.runInContext(
  "hljs.highlight('const answer = 42;', {language: 'js'}).value",
  context
);
assert.match(highlighted, /hljs-keyword/);
assert.match(highlighted, /hljs-number/);
assert.match(highlighted, /answer/);

const markdownLanguages = new Set();
for (const file of markdownFiles(markdownRoot)) {
  const markdown = fs.readFileSync(file, 'utf8');
  const fenceLanguage = /^[\t ]*```([A-Za-z0-9_+-]+)[\t ]*\r?$/gm;
  for (const match of markdown.matchAll(fenceLanguage)) {
    markdownLanguages.add(match[1].toLowerCase());
  }
}

assert.deepStrictEqual(
  Array.from(markdownLanguages).sort(),
  ['html', 'java', 'js', 'txt', 'xml']
);
for (const language of markdownLanguages) {
  assert.ok(
    context.hljs.getLanguage(language),
    `Unsupported help fence language: ${language}`
  );
}

console.log(
  `Help highlight bundle verified: ${bundle.length} bytes, ` +
    `${Array.from(markdownLanguages).sort().join(', ')}`
);

function* markdownFiles(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      yield* markdownFiles(entryPath);
    } else if (entry.isFile() && entry.name.toLowerCase().endsWith('.md')) {
      yield entryPath;
    }
  }
}
