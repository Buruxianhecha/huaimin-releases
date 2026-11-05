# huaimin-releases

《怀民亦未寝》Android 客户端正式版本与发布记录仓库。

## 最新版本状态（2026-11-05）

- **最新已核验安装包：0.39.29（Build 62194）**，包名 `com.lin.huaimin`。
- 已从用户资料库取得 `Huaimin-0.39.29-Backup-Fix.apk`，并完成 APK 身份、ZIP 完整性、v2/v3 签名及 APK 内容摘要核验。
- APK 文件大小：`26924307` 字节。
- APK SHA-256：`504cb2b5cfc9913fd6736e42c80667e00d58cad7d14211a387519875f89c5b87`。
- 签名证书 SHA-256：`e3089362ec55c39f90b0d2f9560b03b20f868f1b1eecd4c89619e7442a865f3d`（v2/v3 均匹配）。

详细核验和发布状态见 [0.39.29-PENDING.md](./0.39.29-PENDING.md)，文件哈希见 [SHA256SUMS.txt](./SHA256SUMS.txt)。

## 自动更新指针

根目录 `latest.json` 目前仍指向 0.39.02。0.39.29 安装包已经完成本地验签，但尚未上传到可公开访问的 APK 下载地址，也尚未创建包含 APK 附件的 GitHub Release。因此**暂不切换自动更新清单**，避免客户端收到无法下载的新版本提示。

正式发布顺序：上传 APK → 验证公开下载地址、大小和 SHA-256 → 创建 GitHub Release 并校验附件 → 最后更新 `latest.json`。
