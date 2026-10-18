# BetterRNP 代码签名

Windows 显示“无法验证发布者”或“未知发布者”，是因为 `BetterRNP.exe` 没有受 Windows 信任的 Authenticode 数字签名。这个问题不能通过应用代码或清单文件关闭，必须使用代码签名证书。

## 需要的证书

推荐选择：

- CA 签发的 OV Code Signing Certificate
- CA 签发的 EV Code Signing Certificate
- Microsoft Azure Trusted Signing

自签名证书只适合本机开发测试。除非用户手工把自签名根证书安装到“受信任的根证书颁发机构”，否则其他电脑仍会显示未知发布者，不能作为正式发布方案。

## 内置开发签名

项目提供默认的本地开发签名流程。执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\create-dev-signing-cert.ps1 -InstallTrust
```

该脚本会：

1. 创建 `BetterRNP Development` 代码签名证书。
2. 导出到被 Git 忽略的 `.signing/BetterRNP-Development.pfx`。
3. 把公开证书安装到当前用户的“受信任的根证书颁发机构”和“受信任的发布者”。
4. 允许后续 Release 构建和 `build-release.ps1` 自动签名。

默认 PFX 密码仅用于本地开发，不应视为生产秘密。私钥不会提交到仓库。

开发签名只对完成上述信任安装的当前 Windows 用户有效。其他电脑仍会提示未知或不受信任发布者。

签名脚本兼容 Windows PowerShell 5.1 与 PowerShell 7，并会显式加载
`Microsoft.PowerShell.Security` 签名模块；只安装了系统自带 PowerShell 5.1 的机器也可以直接使用。

### 随 Release 分发证书

使用开发签名构建时，`build-release.ps1` 会从实际用于签名的 PFX 导出公开证书：

```text
artifacts\BetterRNP-Development-Certificate.cer
```

该 `.cer` 不含私钥，可以安全地作为 Release 附件分发。用户下载后执行：

```powershell
powershell -ExecutionPolicy Bypass -File tools\install-release-cert.ps1 `
    -CertificatePath .\BetterRNP-Development-Certificate.cer
```

脚本会把证书加入当前用户的根证书和受信任发布者存储。此后由同一证书签名的 BetterRNP 发布包会显示发布者名称。

不要分发 `.pfx`。PFX 包含私钥，泄露后任何人都能伪造该发布者签名。

### 移除开发证书

```powershell
Get-ChildItem Cert:\CurrentUser\My, Cert:\CurrentUser\Root, Cert:\CurrentUser\TrustedPublisher |
    Where-Object Subject -Like '*BetterRNP Development*' |
    Remove-Item
```

## 构建时签名

`tools/build-release.ps1` 支持通过环境变量执行 SHA-256 签名和 RFC 3161 时间戳。

### 使用 PFX

```powershell
$env:BETTERRNP_SIGN_PFX = 'C:\secrets\BetterRNP-CodeSigning.pfx'
$env:BETTERRNP_SIGN_PASSWORD = '<pfx-password>'
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

### 使用证书存储中的指纹

先把代码签名证书安装到当前用户的个人证书存储，然后：

```powershell
$env:BETTERRNP_SIGN_THUMBPRINT = '<certificate-thumbprint>'
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

### 指定 signtool

如果 `signtool.exe` 不在 PATH，也没有安装 Windows SDK：

```powershell
$env:BETTERRNP_SIGNTOOL = 'C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64\signtool.exe'
```

### 时间戳服务器

默认使用：

```text
http://timestamp.digicert.com
```

可通过环境变量覆盖：

```powershell
$env:BETTERRNP_TIMESTAMP_URL = 'http://timestamp.digicert.com'
```

## 验证签名

```powershell
Get-AuthenticodeSignature .\artifacts\framework-dependent_v2.1.1\BetterRNP.exe |
    Format-List Status, StatusMessage, SignerCertificate
```

期望 `Status` 为 `Valid`。

## SmartScreen 说明

有效签名会把“未知发布者”替换为证书中的发布者名称。对于较新的发布者，SmartScreen 信誉仍可能需要时间积累；正式发布建议使用 EV 证书或 Azure Trusted Signing，并保持同一发布者身份。

未设置签名环境变量时，构建脚本会输出：

```text
CODE_SIGNING_SKIPPED
```

这表示生成的安装包仍未签名，不应作为正式公开发布版本。
