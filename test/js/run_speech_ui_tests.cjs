// Focused jsdom runner for the speech input recording-handler tests.
//
// Same harness shape as run_session_controls_dom_tests.cjs: jsdom is
// installed before React/ReactDOM load, the foreign-lib shims are rewritten
// in place, and the real react-dom/client module is injected.  The speech
// input element needs a DOM plus fake media devices; those are installed by
// the test namespace itself.
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

const root = process.cwd();
const out = path.resolve(root, process.argv[2] || "out-speech-ui");
const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  url: "http://localhost/",
});
const win = dom.window;

global.window = win;
global.document = win.document;
global.navigator = win.navigator;
global.HTMLElement = win.HTMLElement;
global.Node = win.Node;
global.Event = win.Event;
global.MouseEvent = win.MouseEvent;
global.getComputedStyle = win.getComputedStyle;
global.requestAnimationFrame = (f) => setTimeout(f, 0);
global.cancelAnimationFrame = (id) => clearTimeout(id);
global.IS_REACT_ACT_ENVIRONMENT = true;

const shims = [
  "test/js/react_shim.js",
  "test/js/react_dom_shim.js",
  "test/js/react_dom_server_shim.js",
  "test/js/react_dom_client_shim.js",
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

global.__HC_REACT__ = require(path.join(root, "node_modules/react"));
global.__HC_REACT_DOM__ = require(path.join(root, "node_modules/react-dom"));
global.__HC_REACT_DOM_SERVER__ = require(path.join(root, "node_modules/react-dom/server"));
global.__HC_REACT_DOM_CLIENT__ = require(path.join(
  root,
  "node_modules/react-dom/client.js"
));
if (typeof global.__HC_REACT_DOM_CLIENT__.createRoot !== "function") {
  throw new Error("react-dom/client did not expose createRoot");
}
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
