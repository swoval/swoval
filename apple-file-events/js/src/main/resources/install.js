'use strict';
if (require("os").platform() === "darwin") {
  const spawnSync = require( 'child_process' ).spawnSync;
  const cmake = spawnSync('cmake-js', ['compile']);
  console.log(`stderr: ${cmake.stderr.toString()}`);
  console.log(`stdout: ${cmake.stdout.toString()}`);
}
