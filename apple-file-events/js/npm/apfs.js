'use strict';
if (require("os").platform() === "darwin") {
  module.exports = require('./lib/swoval_apple_file_system.node')
}
