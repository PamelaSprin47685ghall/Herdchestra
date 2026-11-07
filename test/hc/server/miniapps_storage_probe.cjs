#!/usr/bin/env node
// Evaluate the server-injected storage bootstrap in two fake windows that
// share one native localStorage.  This is a focused unit test of the
// generated JavaScript; the browser evidence exercises the same code in
// Chrome.
'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

function readScript(file) {
  const html = fs.readFileSync(file, 'utf8');
  const match = html.match(/<script data-hc-miniapp-storage="[^"]*">([\s\S]*?)<\/script>/);
  if (!match) throw new Error('injected storage script missing from ' + file);
  return match[1];
}

function nativeStorage() {
  const map = new Map();
  return {
    getItem(k) { return map.has(String(k)) ? map.get(String(k)) : null; },
    setItem(k, v) { map.set(String(k), String(v)); },
    removeItem(k) { map.delete(String(k)); },
    clear() { map.clear(); },
    key(i) { const keys = Array.from(map.keys()); return i >= 0 && i < keys.length ? keys[i] : null; },
    get length() { return map.size; }
  };
}

function run(script, native) {
  const win = { localStorage: native };
  const sandbox = { window: win, console };
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox, { filename: 'miniapp-storage.js' });
  return win;
}

const [fileA, fileB] = process.argv.slice(2);
const native = nativeStorage();
const a = run(readScript(fileA), native);
const b = run(readScript(fileB), native);
const prefixA = a.__hcMiniappStorage.prefix;
const prefixB = b.__hcMiniappStorage.prefix;

assert.notStrictEqual(a.miniappAppId, b.miniappAppId);
assert.notStrictEqual(prefixA, prefixB);
assert.strictEqual(prefixA, 'miniapp:' + a.miniappAppId + ':');
assert.strictEqual(prefixB, 'miniapp:' + b.miniappAppId + ':');

a.localStorage.setItem('probe', 'A');
b.localStorage.setItem('probe', 'B');
assert.strictEqual(a.localStorage.getItem('probe'), 'A');
assert.strictEqual(b.localStorage.getItem('probe'), 'B');
assert.strictEqual(a.miniappStorage.get('probe'), 'A');
assert.strictEqual(b.miniappStorage.get('probe'), 'B');

const keysBefore = [];
for (let i = 0; i < native.length; i++) keysBefore.push(native.key(i));
assert.ok(keysBefore.includes(prefixA + 'probe'));
assert.ok(keysBefore.includes(prefixB + 'probe'));
assert.strictEqual(JSON.stringify(a.__hcMiniappStorage.prefixedKeys()), JSON.stringify([prefixA + 'probe']));
assert.strictEqual(JSON.stringify(b.__hcMiniappStorage.prefixedKeys()), JSON.stringify([prefixB + 'probe']));

a.miniappStorage.set('extra', '1');
assert.strictEqual(a.miniappStorage.get('extra'), '1');
a.localStorage.clear();
assert.strictEqual(a.localStorage.getItem('probe'), null);
assert.strictEqual(a.localStorage.getItem('extra'), null);
assert.strictEqual(b.localStorage.getItem('probe'), 'B');
assert.strictEqual(native.getItem(prefixB + 'probe'), 'B');
assert.strictEqual(native.getItem(prefixA + 'probe'), null);

const keysAfter = [];
for (let i = 0; i < native.length; i++) keysAfter.push(native.key(i));
assert.ok(keysAfter.includes(prefixB + 'probe'));
assert.ok(!keysAfter.includes(prefixA + 'probe'));

console.log(JSON.stringify({
  ok: true,
  appA: a.miniappAppId,
  appB: b.miniappAppId,
  prefixA,
  prefixB,
  keysBefore,
  keysAfter
}));
