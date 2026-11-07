// Verification shim: the real React module is injected by the Node runner
// before the compiled ClojureScript loads.  This avoids Closure wrapping the
// CommonJS package and keeps one React instance for the whole render.
var React = global.__HC_REACT__;
module.exports = React;
module.exports.default = React;
