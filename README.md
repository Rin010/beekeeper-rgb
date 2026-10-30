# Beekeeper RGB 1.0.0

为 Redmi G Pro 27U（beekeeper）增加自定义装饰灯颜色、DDP / E1.31（sACN）网络控制，以及带版本校验的 MCU 固件管理。

```cpp
#include <std_disclaimer.h>

/*
 * Your warranty is now void.
 *
 * We are not responsible for bricked devices, dead SD cards,
 * thermonuclear war, or you getting fired because the alarm app failed. Please
 * do some research if you have any concerns about features included in this ROM
 * before flashing it! YOU are choosing to make these modifications, and if
 * you point the finger at us for messing up your device, we will laugh at you.
 *
 */
```

**MCU 同时参与显示与背光控制。刷写失败可能造成黑屏、背光或灯光异常，目前没有经过验证的纯软件救砖方案。安装 Magisk 模块不会自动刷写 MCU。**

## 支持范围

| 项目 | 要求 |
| --- | --- |
| 设备 | `beekeeper`，硬件产品 `otfcn1n` |
| 系统 | `OS3.0.109.0.UFFMAMN`，Android 14 / SDK 34 |
| 环境 | Magisk、Zygisk、已启用的 LSPosed（`zygisk_lsposed` 模块） |
| 原厂 MCU | 整条灯按 **1 颗 RGB 灯珠**进行网络控制 |
| 自定义 MCU | **24 颗灯珠独立控制** |

安装和刷写均有固定版本及文件校验，其他系统版本不能直接使用。

## 功能

- 在原系统装饰灯设置中输入 `#RRGGBB`，支持回车应用、预览和恢复预设。
- 自定义颜色沿用常亮或呼吸光效，切换这两种光效时保留颜色。
- DDP（UDP 4048）及 E1.31 / sACN（UDP 5568）RGB24 接收。
- 自动使用原厂单灯模式或已安装自定义固件的 24 灯珠模式。
- 约 3 秒无网络数据后恢复本地颜色与光效；手动修改灯光会退出网络接管。
- GUI 刷入自定义 MCU 应用固件或刷回内置原版应用镜像，包含风险提示、系统与镜像校验、后台强制 10 秒等待和进度显示。

## 安装

1. 从 [Releases](https://github.com/Rin010/beekeeper-rgb/releases) 下载 `Beekeeper-RGB-v1.0.0-magisk.zip`，在已启动的系统中通过 Magisk 安装。
2. 安装包已内置配套 LSPosed 模块 APK，并自动安装或更新，**无需再单独安装 APK**。LSPosed 框架本身需提前安装并启用。
3. 首次在 LSPosed 启用“装饰灯自定义颜色”，勾选推荐作用域 `com.xiaomi.mitv.settings` 和 `mitv.service`。
4. 重启后打开“设置 → 通用设置 → 氛围 → 炫彩灯”。“自定义颜色”和“网络灯控”位于“屏幕同色”上方。

安装普通模块可直接使用原厂 MCU 的整灯控制；需要逐灯珠控制时，再阅读 [MCU 固件管理](MCU.md) 并自行决定是否刷写。

## 网络灯控

在“网络灯控”页面启用接收、选择协议，按照页面显示的 IP、灯珠数量、Universe 和起始通道配置发送端。OpenRGB 可使用 DDP，保活建议为 1000 ms。原厂模式配置 1 LED，自定义模式配置 24 LEDs。

详细映射、协议范围和限制见 [NETWORK.md](NETWORK.md)。网络接收没有身份认证，只应在可信局域网内使用，不要将 UDP 端口暴露到互联网。

## 卸载

先关闭网络接收并选择“使用预设”，然后在 Magisk 删除模块、在 LSPosed 停用配套模块并卸载 APK，最后重启。

**卸载 Android 模块不会回滚已经刷入的 MCU 固件。** 刷回原版需要单独通过 MCU 固件管理执行，也有相同刷写风险。

## 构建与验证

见 [BUILDING.md](BUILDING.md)。仓库包含源码、固定补丁描述、固件生成器和协议单元测试，不包含签名私钥、个人设备地址、调试记录或备份。构建时需自行提供 SHA-256 匹配的厂商原版文件。

已在适配设备上验证原厂／自定义 MCU 的 GUI 双向刷写、原厂整灯控制、24 颗逐灯控制、OpenRGB/DDP、sACN 和断流恢复。此验证范围不代表其他机型或系统版本受支持。
