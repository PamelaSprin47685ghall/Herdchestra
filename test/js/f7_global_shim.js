// Verification shim: the real framework7-react module is injected by the Node
// runner before the compiled ClojureScript loads.
var F7 = global.__HC_F7__;
module.exports = F7;
module.exports.default = F7;
