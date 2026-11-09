// Focused Node renderer for the swarm dashboard CLJS tests.
//
// cljs.main wraps CommonJS foreign libraries in a file-local var.  This script
// rewrites those generated shim files to expose their namespaces on
// `goog.global`, then injects one React/Framework7 instance before loading the
// compiled test entry point.  It does not edit source or production output.
const fs = require("fs");
const path = require("path");

const root = process.cwd();
const out = path.resolve(root, process.argv[2] || "out-swarm-ui");
const shims = [
  "test/js/react_shim.js",
  "test/js/react_dom_shim.js",
  "test/js/react_dom_server_shim.js",
  "test/js/f7_global_shim.js",
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
global.__HC_F7__ = require(path.join(
  root,
  "node_modules/framework7-react/framework7-react.js"
));

// cljs.test reports failures through process.exitCode and a standard summary
// line.  A runner that only requires the compiled entry point can still exit
// 0 when that write is lost, and async tests finish after require returns.
// Keep the summary's failure count and force the final status at beforeExit.
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
  require(path.join(out, "test.js"));
} catch (error) {
  console.error(error);
  hcTestFailures = Math.max(hcTestFailures, 1);
  process.exitCode = 1;
}
