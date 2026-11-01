# 极米守护自愈（XGIMI Guard）

让极米投影仪上的 **Aerial TV 桌面守护**在被系统清理后**自动恢复**。

## 背景

极米 GMUI 会在待机/重启时清理第三方无障碍服务，导致 Aerial 的桌面守护
（`app.orchard.tv/.guard.HomeGuardService`）被踢、主页键回到原厂桌面。
本应用在设备本地完成自愈，无需电脑、无需 root、无需人在现场：

1. **开机**（BOOT_COMPLETED）：先确保网络 ADB（5555）已开启
   ——ADB 开关逻辑来自 [code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（MIT），
   原理与实验记录见该项目文档。
2. **自检 + 修复**：通过设备自身的 adbd（`127.0.0.1:5555`，纯 Java 实现的
   ADB 协议客户端）执行 `settings put secure enabled_accessibility_services ...`，
   把 Aerial 守护加回启用列表（保留极米语音服务 `com.xgimi.duertts`），
   并写 `accessibility_enabled=1`。
3. **巡逻**：AlarmManager 每 10 分钟重复第 2 步；打开本应用点
   「重新开启并检查」也会顺带执行一轮修复并在界面显示结果。

## 已验证机型

| 设备 | ADB 开关 | 守护自愈 |
| --- | --- | --- |
| 极米 Z7X 高亮版 | ✅ 原始验证 | 🧪 |
| 极米 Z6/Z6X/Z6X Pro | ✅ | ✅ 2026-11 实战验证 |
| 极米 H3S / Z9X / M1 / Z8X | ✅ | 🧪 |

`ro.adb.secure=0` 的固件上本应用静默工作；若 adbd 要求授权（Android 8.0
固件实测要求），首次会在投影仪屏幕弹调试授权框，勾选「始终允许」一次
即可，此后无感。

## Aerial TV 必须用「守护版」

本应用只负责把守护服务写回无障碍启用列表，真正接管 HOME 键的是 Aerial
TV 自带的无障碍服务 `app.orchard.tv/.guard.HomeGuardService`。**「标准版」
Aerial TV 没有这个组件**：设置表写进去也不生效，`dumpsys accessibility`
里查不到该包，桌面不会被接管。请安装带守护功能（守护版）的 Aerial TV。

自查方法（小草助手等工具的自定义命令）：

```sh
dumpsys accessibility | grep orchard
```

有输出 = 守护服务在系统里注册成功；零输出 = 装的是标准版或服务未绑定。

## 安装

U 盘拷贝 `app-debug.apk` → 投影仪文件管理器安装 → 打开一次（默认自动
开启 ADB 并执行首轮修复；在投影仪弹出的调试授权框中勾选「始终允许」）。

## 还原

卸载本应用即停止巡逻；已写入的 ADB 配置与无障碍设置保留。Aerial 内可
手动关闭守护，或用 `z7x-adb` 的「关闭 ADB」实验功能复位 ADB。

## 致谢与许可

- ADB 开关与 TV 界面骨架：[code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（MIT）
- Aerial TV：[pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)
- 本仓库新增代码同以 MIT 发布；与极米、联发科、Aerial 作者无隶属关系。
