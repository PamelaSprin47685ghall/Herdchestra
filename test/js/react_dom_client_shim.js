// Verification shim: the real react-dom/client module is injected by the Node
// runner before the compiled ClojureScript loads.  Keeping the foreign-lib
// under test/js avoids compiling node_modules package.json files into the
// output tree, which Node would reject with ERR_INVALID_PACKAGE_CONFIG.
var ReactDOMClient = global.__HC_REACT_DOM_CLIENT__;
module.exports = ReactDOMClient;
module.exports.default = ReactDOMClient;
