lib/ —— 可选 ImageIO 解码插件目录（宿主内建主题）
=========================================================================

一句话
------
JDK 自带 ImageIO 只认 JPEG/PNG/BMP/GIF/TIFF/WBMP，**不含 WebP**。
WebP 有两条路可走，**任选其一**：

  路线①（本目录）：放一个 ImageIO 读取器插件 jar → 重启 → WebP 进入"可解码格式"白名单；
  路线②（免插件）  ：让本程序在**导入时**用本机 ffmpeg 把 WebP/AVIF/HEIC 自动转成 PNG（见下）。


路线① 放插件（本目录）
----------------------
放什么：按 ServiceLoader 规范注册的 ImageIO 读取器（ImageReaderSpi）。常见两种：

  A. TwelveMonkeys ImageIO 的 WebP 模块
     Maven 坐标（大致）：com.twelvemonkeys.imageio:imageio-webp
     注意：它不是单文件——同族还需要 imageio-core、common-lang、common-io、common-image 等 jar，
     这些 jar 都要一起放进本目录。
  B. sejda / luciad 的 webp-imageio
     一般是**单个 jar**（内含各平台本地库，运行时自行释放），最省事。

怎么拿：本机若不能联网，就在能联网的机器上从 Maven Central 搜索上面的 artifact 名下载 jar，
        或用任意 Maven/Gradle 工程 `mvn dependency:copy-dependencies` 把依赖一起拷出来。
        （仓库不收录第三方 jar：.gitignore 已忽略 lib/*.jar，请自行获取并遵守其许可证。）

放好后：**重启程序**。自检看 log/Modifylog.txt 里的这一行：
        【主题-可解码图片格式: … （lib/ 命中 N 个 jar，注册 M 个读取器）】
        出现 webp 即成功；独立页"格式说明…"里也会显示同样的信息。
效果：图库里原显示"[无法解码·跳过贴图]"的 WebP 会恢复缩略图/尺寸，并可正常贴到窗口上。


路线② 用本机 ffmpeg 自动转换（不需要插件）
----------------------------------------
前提：本机 PATH 里有 ffmpeg（或手动指定路径）。
行为：导入图片时，先按内容解码；**解不出来的**才调用 ffmpeg 转成 PNG 入库（只在这一刻用到 ffmpeg，
      运行时零依赖；转码临时文件导入后即删除）。
开关：独立页 →“格式说明…”对话框里的复选框（勾选=启用）。
配置键：theme.media.ffmpeg = auto（默认，找到就用）/ off（禁用）；
        theme.media.ffmpeg.path = C:\...\ffmpeg.exe（PATH 里没有时显式指定）。
局限：转出来的 PNG 通常比 WebP 大；只取第一帧（动图/视频请用"动态背景"入口）。


其它零成本办法
--------------
用 Windows"照片/画图"、任意浏览器或看图软件把 WebP **另存为 PNG/JPG**，再导入即可（最稳）。


注意事项
--------
* 本目录默认为空；仓库不收录第三方 jar。
* jar 只需读取器（ImageReaderSpi）；编码器（WebP 写出）本程序不用。
* 插件 jar 只影响"能解码哪些图片格式"，与插件体系（plugins/ 目录）互不相干。
* 放错/损坏的 jar、缺失的 ffmpeg，都只会在日志里记一行，不影响程序启动。
* 维护工具：`tools/theme-smoke/ConvertUndecodableImages.java`
  可把图库里**已经导入但打不开**的图片（如你之前导入的 WebP）批量转成 PNG 条目，
  并把全局/各类别的引用自动重定向过去（原文件保留）。
