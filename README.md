<p align="center">
  <img src="assets/app-icon.png" width="96" alt="Coco Dense" />
</p>

<h1 align="center">Coco Dense</h1>

<p align="center">
  <strong>本地优先、支持云同步的加密密码与资料保险箱</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.1.0-blue?style=flat-square" alt="version" />
  <img src="https://img.shields.io/badge/platform-macOS%20%7C%20Windows-lightgrey?style=flat-square" alt="platform" />
  <img src="https://img.shields.io/badge/runtime-Electron%2034-47848F?style=flat-square&logo=electron" alt="electron" />
  <img src="https://img.shields.io/badge/storage-local%20encrypted-success?style=flat-square" alt="storage" />
</p>

<p align="center">
  数据默认保存在本地，内容以 <strong>AES-256-GCM</strong> 加密存储。<br/>
  主密码不明文落盘，解锁链路包含主密码、数据钥匙、设备侧会话状态等多层保护。
</p>

---

## 项目定位

`Coco Dense` 是一个桌面端个人保险箱，当前重点覆盖这几类需求：

- 账号密码与敏感资料集中管理
- 本地加密存储，尽量降低明文暴露面
- 通过 WebDAV 做跨设备同步
- 支持文件夹组织、搜索、筛选、备份导入导出
- 支持应用内检查 GitHub Release 更新

当前仓库已经整理为：

- `shared/`：macOS 和 Windows 共用的业务逻辑、主进程逻辑、预加载层、渲染层 UI
- `platforms/mac/`：macOS 专属配置与打包配置
- `platforms/win/`：Windows 专属配置与打包配置

这意味着大部分功能改动只要发生在 `shared/`，两端都会一起生效。

---

## 当前功能清单

### 1. 保险箱与数据模型

- 支持创建加密保险箱
- 支持主密码 + 数据钥匙双层解锁
- 支持修改主密码
- 支持安全问题辅助恢复主密码
- 支持条目增删改查
- 支持字段：
  - 名称
  - 账号
  - 密码
  - 网址
  - 标签
  - 优先级
  - 备注
- 支持文件夹分组与条目归类
- 支持空库、新库、已加密库等不同状态处理

### 2. 搜索与整理

- 支持按名称搜索
- 支持拼音首字母模糊搜索
- 支持按文件夹筛选
- 支持按标签筛选
- 支持按优先级筛选
- 支持文件夹展开 / 收起
- 支持文件夹右键菜单管理

### 3. 安全能力

- 数据使用 `AES-256-GCM` 加密
- 主密码通过 `PBKDF2` 派生密钥
- 主密码不在渲染层长期缓存
- 数据钥匙单独参与保险箱解锁与同步
- 支持 Touch ID 解锁链路
- 支持剪贴板定时清空
- 支持自动锁定
- 支持手动锁定
- 自动锁定已改为后台静默执行，不强制抢前台
- 解锁失败有保护逻辑

### 4. 同步与备份

- 支持 WebDAV 同步
- 已兼容坚果云这类第三方 WebDAV 服务
- 支持双向同步
- 支持条目与文件夹一起同步
- 支持旧结构兼容读取
- 已避免“新装空库错误覆盖云端已有数据”的问题
- 支持导出加密保险箱文件
- 支持导入保险箱备份文件

### 5. 更新能力

- 解锁后自动检查 GitHub Release 更新
- 支持手动检查更新
- 支持展示版本号、更新日志、下载入口
- 支持“今日不再提醒”
- 支持下载后直接打开安装包
- 当前更新仓库已切换到：
  - `https://github.com/is-coco/Coco_Dense`

### 6. 桌面端交互

- 自定义标题栏
- 自定义下拉菜单
- 支持键盘导航与 Esc 收起
- 支持备注区局部复制
- 支持密码强度提示
- 支持随机密码生成

---

## 最近已修复的重要问题

以下内容对接手项目的人尤其重要，因为它们影响同步、安全和桌面行为：

- 修复了解锁时手动输入的数据钥匙会被 remembered data key 覆盖的问题
- 修复了双向同步只同步条目、不同步文件夹的问题
- 修复了新装或清本地缓存后，空库可能覆盖云端已有数据的问题
- 修复了手动锁定后窗口尺寸被异常改成 `760 x 540` 的问题
- 修复了自动锁定会把应用强制切回前台、打断用户操作的问题
- 修复了更新页和应用内更新逻辑仍指向旧仓库地址的问题
- 调整了 GitHub Release 工作流，现在只发布干净的安装包，不再混入 `blockmap` 和 `latest.yml`

---

## 当前版本与安装包

当前仓库版本：

- `0.1.0`

当前 Release 页面：

- `https://github.com/is-coco/Coco_Dense/releases`

当前保留的安装包资产：

- macOS Apple Silicon：`Coco.Dense-0.1.0-arm64.dmg`
- macOS Apple Silicon：`Coco.Dense-0.1.0-arm64.zip`
- Windows 安装包：`Coco.Dense-0.1.0-Setup.exe`

---

## 技术栈

| 模块 | 技术 |
|------|------|
| 桌面运行时 | Electron 34 |
| 前端 | HTML / CSS / JavaScript（原生） |
| 加密 | Node.js `crypto` |
| 搜索拼音 | `pinyin-pro` |
| 打包 | `electron-builder` |
| 发布 | GitHub Actions + GitHub Releases |

---

## 项目结构

```text
.
├── assets/                    # 图标与静态资源
├── platforms/
│   ├── mac/
│   │   ├── builder.json       # macOS 打包配置
│   │   └── config.js          # macOS 平台文案 / 能力配置
│   └── win/
│       ├── builder.json       # Windows 打包配置
│       └── config.js          # Windows 平台文案 / 能力配置
├── shared/
│   ├── build/
│   │   └── common.json        # electron-builder 共用配置
│   ├── renderer/
│   │   ├── index.html         # 主界面
│   │   ├── script.js          # 渲染层交互逻辑
│   │   └── styles.css         # 界面样式
│   ├── main.js                # 主进程核心逻辑
│   └── preload.js             # 预加载桥接层
├── main.js                    # 根入口，转发到 shared/main.js
├── preload.js                 # 根入口，转发到 shared/preload.js
├── package.json
└── .github/workflows/release.yml
```

---

## 对后续 AI / 开发者的接管说明

这是给新会话 AI 或新开发者看的重点：

### 1. 先看哪里

建议优先阅读这些文件：

- `shared/main.js`
- `shared/preload.js`
- `shared/renderer/index.html`
- `shared/renderer/script.js`
- `platforms/mac/config.js`
- `platforms/win/config.js`
- `.github/workflows/release.yml`

### 2. 代码分层约定

- 业务逻辑优先放在 `shared/`
- 只有平台专属行为才放到 `platforms/mac` 或 `platforms/win`
- 根目录 `main.js` / `preload.js` 只是薄入口，不应堆积业务逻辑

### 3. 哪些改动通常会双端同步

- 条目结构
- 文件夹逻辑
- 搜索与筛选
- 同步逻辑
- 导入导出
- 解锁流程
- 更新页面 UI

### 4. 哪些改动需要额外检查平台差异

- 生物识别 / Touch ID / Windows 生物识别
- 安装包格式
- 自动更新安装方式
- 系统权限调用
- 窗口行为与系统 API
- 图标与打包配置

### 5. 当前发布规则

- 打 tag `v*` 会触发 GitHub Actions
- 构建阶段显式使用 `--publish never`
- 只有最后的 `release` job 统一上传安装包
- 目前 Release 只保留 `.dmg`、`.zip`、`.exe`

### 6. 本地测试习惯

本项目当前协作习惯是：

- 修改完成后构建 mac 目录版：`npm run dist:mac:dir`
- 覆盖安装到：`/Applications/Coco Dense.app`
- 再直接打开应用验证

---

## 本地开发

```bash
npm install
npm start
```

---

## 构建命令

### macOS

```bash
npm run dist:mac
```

只生成本地可直接覆盖测试的 `.app` 目录：

```bash
npm run dist:mac:dir
```

### Windows

生成安装包：

```bash
npm run dist:win
```

生成本地便携构建：

```powershell
npm run dist:unpacked
```

---

## 发布流程

```bash
# 1. 修改版本号
npm version 0.1.1 --no-git-tag-version

# 2. 提交代码
git add .
git commit -m "Release v0.1.1"

# 3. 推送主分支
git push origin main

# 4. 打 tag 并推送
git tag v0.1.1
git push origin v0.1.1
```

GitHub Actions 会自动：

- 构建 macOS 安装包
- 构建 Windows 安装包
- 创建 / 更新对应 Release

---

## 版本日志

版本日志已单独整理到：

- [CHANGELOG.md](./CHANGELOG.md)

---

## 许可证

当前仓库默认按私有项目协作方式维护。若后续准备开放源码或商业化，建议再单独补充正式许可证文本。
