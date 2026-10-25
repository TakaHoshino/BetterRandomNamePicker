# 设置存储

BetterRNP 将应用设置统一保存在当前用户注册表：

```text
HKEY_CURRENT_USER\Software\BetterRNP\Settings
```

## 分区

| 注册表子键 | 内容 |
|---|---|
| `General` | 界面语言、托盘行为、自动启动、上次方案 |
| `FloatingBall` | 悬浮球半径和透明度 |
| `Security` | 配置密码散列 |
| `Update` | 启动检查更新、更新下载源 |
| `Plugins\Disabled` | 被禁用的插件 ID |
| `Theme` | 背景开关、遮罩、图片指定、裁切和窗口类别设置 |

根键的 `SchemaVersion` 值记录设置结构版本。

## 旧设置迁移

程序首次启动新版本时会尝试读取：

- `data/config.properties`
- `data/themes/theme.properties`
- `data/update.json`
- `data/plugins.json`

对应的 `.bak` 文件也会作为恢复来源。只有全部设置成功写入注册表后，程序才删除旧设置文件；迁移失败时会保留文件并写入操作日志。

名单、数字范围、座位配置、主题图片、插件包和日志仍属于用户数据，继续保存在程序目录中。
