"use strict";
// Synthetic fixtures only. Load the desktop crypto functions without starting Electron.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const crypto = require("node:crypto");
const source = fs.readFileSync(path.join(__dirname, "..", "..", "shared", "main.js"), "utf8");
const snippet = source.slice(source.indexOf("function stableStringify"), source.indexOf("function defaultVault"));
const context = { crypto, Buffer, Date, activeDataKey: "", IV_LENGTH: 12, KEY_LENGTH: 32,
  SALT_LENGTH: 16, PBKDF2_ROUNDS: 250000,
  VAULT_FORMAT_V2: "coco-dense-envelope-v2", VAULT_FORMAT_V3: "coco-dense-envelope-v3" };
vm.createContext(context);
vm.runInContext(snippet, context);
const payload = { version: 1, createdAt: "2026-01-01T00:00:00.000Z", updatedAt: "2026-01-02T00:00:00.000Z",
  entries: [{ id: "sample-entry", site: "示例网站", account: "demo", password: "example-only-123",
    url: "https://example.com", tags: "测试", notes: "不是真实密码", folderId: "sample-folder",
    favorite: true, pinned: false, priority: "green", createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-02T00:00:00.000Z", deletedAt: "", lastUsedAt: "" }],
  settings: { folders: [{ id: "sample-folder", name: "演示", priority: "green" }] } };
for (const [name, dataKey] of [["v2", ""], ["v3", "sample-data-key"]]) {
  const wrapped = context.encryptJson("sample-master-password", payload, { dataKey });
  const readBack = context.decryptJson("sample-master-password", wrapped, { dataKey });
  if (readBack.entries[0].password !== payload.entries[0].password) throw new Error("Desktop roundtrip failed");
  fs.writeFileSync(path.join(__dirname, `${name}.json`), JSON.stringify(wrapped, null, 2));
}
