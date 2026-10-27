# Coco Dense Android

`android` 分支维护全新编写的原生 Kotlin/Jetpack Compose 安卓端；桌面端在 `main` 分支维护。这里没有复制旧 Flutter 工程。最低 Android 12。数据格式与桌面端的 V2/V3 保险箱兼容；本分支保留 `shared/main.js` 供兼容测试使用。

## 手机界面

每次解锁先显示欢迎页，点“查看全部记录”或搜索后显示密码列表。保险箱首页可按分组或收藏筛选，长按分组可编辑名称与优先级；分组按红、黄、蓝、绿优先级排序。记录右侧按钮可一键复制密码，点记录进入详情。右下角可新增密码，新建与编辑使用居中浮动卡片。底部只保留保险箱与设置；回收站用于恢复误删记录，入口在设置页。同步、安全、数据钥匙、备份与更新也在设置页分别进入独立页面。安全页可设置本机九宫格图案快捷解锁；图案不能代替主密码，新设备仍需主密码。界面固定使用浅色，适配状态栏与导航栏。应用图标沿用桌面版 `assets/app-icon.png`；界面操作图标使用 MIT 许可的 [Tabler Icons](https://github.com/tabler/tabler-icons)，许可文件在 `third-party/Tabler-Icons-LICENSE.txt`。

## 本地构建

使用 Android Studio 打开此目录。Windows 项目路径含中文时，Gradle 测试工作进程可能启动失败，可在仓库目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\android\build-local.ps1
powershell -ExecutionPolicy Bypass -File .\android\build-local.ps1 -Release
powershell -ExecutionPolicy Bypass -File .\android\build-local.ps1 -DeviceTest
```

测试 APK 使用独立包名 `com.coco.dense.qa`，不会触碰正式版数据；设备测试只使用虚构密码与条目。测试包和正式版若同时安装，会出现两个同名图标。`-DeviceTest` 完成后会自动卸载测试包；手动安装测试包后也应在测试结束时卸载 `com.coco.dense.qa`。签名 APK：`app/build/outputs/apk/release/app-release.apk`。这些文件为本地构建产物，不提交到 Git。

## 跨端验证

虚构样本位于 `fixtures/v2.json`、`fixtures/v3.json`，可用 `node fixtures/generate-desktop.js` 重新生成。安卓单元测试读取桌面样本并写出 `app/build/android-v2-roundtrip.json` 与 `android-v3-roundtrip.json`；在仓库根目录运行 `node android/fixtures/verify-android.js`，验证桌面代码可读回安卓输出。

## WebDAV 试用顺序

1. 先为现有桌面保险箱导出一份加密备份，并确认能用主密码及数据钥匙解锁。
2. 三端都配置**独立测试路径**，例如 `/CocoDense-test/vault.json`，只放虚构条目。安卓新建空库时使用与测试云端相同的主密码；如果云端是 V3，先在安卓设置同一数据钥匙。
3. 分别测试新增、修改、删除、分组改名、离线修改后重连及同一条目冲突。服务端必须支持 `ETag` 与 `If-Match`/`If-None-Match`；否则应用停止上传。
4. 验证全部通过后，才把三端改为正式 `/CocoDense/vault.json`。新设备第一次连接先读取并合并云端，勿使用覆盖上传。

安卓端在解锁状态下新增、编辑、删除、恢复记录和修改分组后自动执行双向同步，也可在保险箱首页下拉或点顶部同步按钮。设置 → 同步可单独选择“下载云端”（只合并到本机，不上传）或“上传本机”（先读取云端、解决冲突后条件写入）。锁定或被系统结束时不在后台读取主密码做同步。安全问题、头像及本机生物识别属于设备本地数据。
桌面版当前默认每 5 分钟检查一次云端；若希望手机修改后约 1 分钟内在已打开的桌面版出现，需在桌面端把自动检查间隔设为 1 分钟。桌面版关闭时无法自动拉取；重新打开或手动同步后才会显示。手机编辑完成也需要网络可用、WebDAV 凭据正确且冲突已解决，才能成功上传。
同步设置默认显示桌面端使用的坚果云地址 `https://dav.jianguoyun.com/dav/` 和路径 `/CocoDense/vault.json`；用户名和应用密码仍需在本机填写，填完前不会自动同步。
如果手机先创建了空保险箱且主密码与桌面版不同，可在设置 → 同步 → 导入已有云端保险箱中输入桌面保险箱的主密码和数据钥匙。应用只读下载并验证云端文件，确认本机没有记录或分组后备份并导入；不向云端写入。导入后使用云端主密码解锁手机。此流程不适用于覆盖已有本机记录。

## 签名和更新

本地签名材料位于被 Git 忽略的 `android/signing/`。**请单独备份 `release.jks` 和 `credentials.properties`：丢失密钥后，旧版 APK 不能用新签名覆盖升级。** 安卓发布使用独立标签 `android-v0.1.9` 这样的格式，只从 `android` 分支构建 APK；桌面端仍使用 `main` 分支的 `v*` 标签。GitHub Release 构建需设置四个仓库 Secrets：`ANDROID_KEYSTORE_BASE64`（`release.jks` 的 Base64）、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不要提交这些值。应用内更新只查找带 `android-v` 标签和 APK 资产的发布。
