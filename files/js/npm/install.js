'use strict';
const platform = require("os").platform();
if (!(platform === "darwin")) {
  console.log("Not running install script on non-OSX platform (" + platform + ")");
  return;
}
const spawnSync = require("child_process").spawnSync;
if (spawnSync("which", ["cmake-js"]).stdout.toString() === "") {
  console.log("cmake-js is not installed. Run `npm install -g cmake-js` and try again.")
  return;
}
const cmake = spawnSync("cmake-js", ["compile"]);
console.log(`stderr: ${cmake.stderr.toString()}`);
console.log(`stdout: ${cmake.stdout.toString()}`);

const fs = require("fs");
if (!fs.existsSync("lib")) fs.mkdirSync("lib");
fs.copyFileSync("build/Release/swoval_apple_file_system.node", "lib/swoval_apple_file_system.node");
