'use strict';
if (require("os").platform() === "darwin") {
  module.exports = require('./build/Release/swoval_apple_file_system.node')
}
