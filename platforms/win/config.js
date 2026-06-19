const path = require("node:path");

module.exports = {
  id: "win",
  isMac: false,
  isWindows: true,
  appUserModelId: "com.coco.cocodense",
  biometricLabel: "生物识别",
  biometricPromptEnable: "启用 Coco Dense 的生物识别解锁",
  biometricPromptUnlock: "使用生物识别解锁 Coco Dense",
  iconPath: path.join(__dirname, "..", "..", "assets", "app-icon.png"),
  browserWindow: {
    frame: false,
    titleBarStyle: "hidden",
    thickFrame: true,
    roundedCorners: true,
    backgroundColor: "#fbfcfe",
    autoHideMenuBar: true,
  },
  build: {
    icon: "assets/app-icon.ico",
    target: ["nsis"],
    artifactName: "${productName}-${version}-Setup.${ext}",
  },
};
