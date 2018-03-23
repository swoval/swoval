'use strict';
const platform = require("os").platform()
if (!(platform === "darwin")) {
  console.log("Not running install script on non-OSX platform (" + platform + ")")
  return;
}
const fs = require("fs")
const md5 = require("md5")
const md5sum = md5(["src/swoval_apple_file_system.hpp", "src/swoval_apple_file_system_api_node.cc"]
  .reduce((a, f) => a + fs.readFileSync(f), ""))
const existingMD5Sum = fs.existsSync("md5sum") ? fs.readFileSync("md5sum", {"encoding": "utf8"}) : ""

if (md5sum === existingMD5Sum) {
  console.log("No source files have changed -- not building native library.")
  return;
}

const spawnSync = require( 'child_process' ).spawnSync;
const cmake = spawnSync('cmake-js', ['compile']);
console.log(`stderr: ${cmake.stderr.toString()}`);
console.log(`stdout: ${cmake.stdout.toString()}`);

if (!fs.existsSync("lib")) fs.mkdirSync("lib")
fs.copyFileSync("build/Release/swoval_apple_file_system.node", "lib/swoval_apple_file_system.node")
fs.writeFileSync("md5sum", md5sum)
