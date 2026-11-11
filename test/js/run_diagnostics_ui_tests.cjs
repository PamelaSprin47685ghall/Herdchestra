// Focused Node renderer for hc.client.diagnostics.ui-test.
//
// cljs.main wraps CommonJS foreign libraries in a file-local var, so the
// generated shim files assign `module$...` instead of reaching `goog.global`.
// This runner rewrites those assignments, then injects one React instance
// before loading the compiled test entry point.  It edits only the build
// output under `out`, never source or production files.
const fs = require("fs");
const path = require("path");

const root = process.cwd();
const out = path.resolve(root, process.argv[2] || "out-diagnostics-ui");
const shims = [
  "test/js/react_shim.js",
  "test/js/react_dom_shim.js",
  "test/js/react_dom_server_shim.js",
];

for (const shim of shims) {
  const file = path.join(out, shim);
  if (!fs.existsSync(file)) continue;
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
global.__HC_REACT_DOM_SERVER__ = require(path.join(
  root,
  "node_modules/react-dom/server"
));

// cljs.test reports failures through process.exitCode and a summary line.
// A runner that only requires the entry point can still exit 0 when that
// write is lost, and async tests finish after require returns.  Watch the
// summary and force the final status at beforeExit.
let hcTestFailures = Number(process.exitCode) || 0;
let hcTestOutput = "";
const hcNoteTestOutput = (args) => {
  hcTestOutput = (hcTestOutput + " " + args.map(String).join(" ")).slice(-2048);
  const match = hcTestOutput.match(/(\d+)\s+failures?,\s*(\d+)\s+errors?/i);
  if (match && Number(match[1]) + Number(match[2]) > 0) {
    hcTestFailures = Math.max(hcTestFailures, 1);
  }
};
for (const method of ["log", "error"]) {
  const original = console[method];
  console[method] = (...args) => {
    hcNoteTestOutput(args);
    original(...args);
  };
}
process.on("beforeExit", () => {
  if (hcTestFailures > 0) process.exitCode = 1;
});

try {
  require(path.join(out, "main.cjs"));
} catch (error) {
  console.error(error && error.stack ? error.stack : String(error));
  process.exitCode = 1;
}
