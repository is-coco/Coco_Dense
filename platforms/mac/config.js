const path = require("node:path");

module.exports = {
  id: "mac",
  isMac: true,
  isWindows: false,
  appUserModelId: "",
  biometricLabel: "Touch ID",
  biometricPromptEnable: "启用 Coco Dense 的 Touch ID 解锁",
  biometricPromptUnlock: "使用 Touch ID 解锁 Coco Dense",
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
    icon: "assets/app-icon.icns",
    category: "public.app-category.productivity",
    target: ["dmg", "zip"],
    identity: null,
  },
};
