// Verification shim for `framework7/lite`.
//
// The workbench shell DOM test mounts the plain `.wb-shell` chrome, not a
// Framework7 App.  `views/common` still calls `Framework7.use(...)` at load;
// this stub satisfies that one call without starting a router.  The real
// framework7-react components are injected separately by the Node runner.
var F7 = { use: function () {} };
module.exports = F7;
module.exports.default = F7;
