// Focused Node renderer for the schedule workspace CLJS tests.
//
// cljs.main wraps CommonJS foreign libraries in a file-local var.  This script
// rewrites those generated shim files to expose their namespaces on
// `goog.global`, then injects one React instance before loading the compiled
// test entry point.  It does not edit source or production output.
const fs = require("fs");
const path = require("path");

const root = process.cwd();
const out = path.resolve(root, process.argv[2] || "out-schedule-ui");
const shims = [
  "test/js/react_shim.js",
  "test/js/react_dom_shim.js",
  "test/js/react_dom_server_shim.js",
];

for (const shim of shims) {
  const file = path.join(out, shim);
  let source = fs.readFileSync(file, "utf8");
  source = source.replace(
    /var (module\$[A-Za-z0-9_$]+)=\{\};/g,
    "goog.global.$1={};"
  );
  fs.writeFileSync(file, source);
}

fs.writeFileSync(path.join(out, "package.json"), '{"type":"commonjs"}\n');

global.$jscomp = {};
global.__HC_REACT__ = require(path.join(root, "node_modules/react"));
global.__HC_REACT_DOM__ = require(path.join(root, "node_modules/react-dom"));
global.__HC_REACT_DOM_SERVER__ = require(path.join(root, "node_modules/react-dom/server"));

require(path.join(out, "test.js"));
