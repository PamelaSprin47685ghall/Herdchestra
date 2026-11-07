// Focused jsdom runner for the generated-card interaction smoke.
//
// React DOM decides at module load whether a DOM is available.  This runner
// installs jsdom before loading React/ReactDOM, then reuses the shim rewrite
// from the other Node renderers.  It does not edit source or production
// output.
const fs = require("fs");
const path = require("path");
const { JSDOM } = require("jsdom");

const root = process.cwd();
const out = path.resolve(root, process.argv[2] || "out-generated-card-dom");
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
  "test/js/f7_global_shim.js",
];
for (const shim of shims) {
  const file = path.join(out, shim);
  let source = fs.readFileSync(file, "utf8");
  source = source.replace(/var (module\$[A-Za-z0-9_$]+)=\{\};/g, "goog.global.$1={};");
  fs.writeFileSync(file, source);
}

fs.writeFileSync(path.join(out, "package.json"), '{"type":"commonjs"}\n');

global.__HC_REACT__ = require(path.join(root, "node_modules/react"));
global.__HC_REACT_DOM__ = require(path.join(root, "node_modules/react-dom"));
global.__HC_REACT_DOM_SERVER__ = require(path.join(root, "node_modules/react-dom/server"));
global.__HC_F7__ = require(path.join(
  root,
  "node_modules/framework7-react/framework7-react.js"
));

require(path.join(out, "test.js"));
