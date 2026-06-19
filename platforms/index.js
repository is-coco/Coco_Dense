const macConfig = require("./mac/config");
const winConfig = require("./win/config");

function getPlatformConfig(platform = process.platform) {
  if (platform === "darwin") return macConfig;
  if (platform === "win32") return winConfig;
  return platform === "darwin" ? macConfig : winConfig;
}

module.exports = {
  getPlatformConfig,
};
