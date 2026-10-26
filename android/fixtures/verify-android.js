"use strict";
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
for (const [name, dataKey] of [["v2", ""], ["v3", "sample-data-key"]]) {
  const file = path.join(__dirname, "..", "app", "build", `android-${name}-roundtrip.json`);
  const payload = context.decryptJson("sample-master-password", JSON.parse(fs.readFileSync(file, "utf8")), { dataKey });
  if (payload.entries[0].password !== "example-only-123") throw new Error(`${name} failed`);
  process.stdout.write(`${name}: Android → desktop compatible\n`);
}
