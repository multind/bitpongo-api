# 智投宝移动端设计

日期：2026-08-14

状态：已完成分段确认，等待设计文档审阅

涉及仓库：`zhitoubaomobile`、`zhitoubaofront`、`zhitoubao`

## 1. 目标与范围

在 `/Volumes/ExternalDrive/Code/github/zhitoubaomobile` 新建独立 Flutter 工程，为现有
`zhitoubaofront` 提供面向 Apple App Store 和 Google Play 的移动端容器。

首版不是简单 WebView 壳，而是包含以下原生增强能力：

- 远程前端优先，内置前端兜底；
- 原生启动、加载、断网、超时、错误和重试界面；
- 下拉刷新、平台返回行为、外链、下载和系统分享；
- 受限的 JavaScript Bridge 与 URL 白名单；
- App 版本、平台、系统版本和安全区域信息桥接；
- App 内账号注销，配套后端停用、凭据清理和数据匿名化。

首版不接入消息推送、社交登录、扫码、Firebase、生物识别等当前业务未使用的能力。

## 2. 应用身份与平台基线

- 应用名称：`智投宝`
- Android applicationId：`com.multind.zhitoubao`
- iOS Bundle ID：`com.multind.zhitoubao`
- iOS 最低版本：15
- Android 最低版本：API 26（Android 8.0）
- Android targetSdk：实施时应用市场要求的最新版本
- 屏幕方向：手机竖屏为主
- Flutter：实施时最新 stable，并提交锁文件和工具链说明

所有直接依赖在实施时从官方包源解析最新稳定且相互兼容的版本，只引入实际使用的包，
不照搬参考工程中与智投宝无关的依赖。

## 3. 总体架构

移动端使用两个独立构建参数：

- `WEB_BASE_URL`：线上 `zhitoubaofront` 地址；
- `API_BASE_URL`：`zhitoubao` 后端地址。

开发启动示例：

```bash
flutter run \
  --dart-define=WEB_BASE_URL=http://localhost:5173 \
  --dart-define=API_BASE_URL=http://localhost:8000
```

当前尚无线上部署地址，因此未配置 `WEB_BASE_URL` 时直接加载内置前端。生产构建必须使用
HTTPS 远程地址；HTTP 只通过 debug 平台配置为明确的本地开发地址放行。

### 3.1 启动状态机

1. 展示原生启动页并读取构建配置。
2. 启动内置前端的回环 HTTP 服务。
3. 有 `WEB_BASE_URL` 且网络可用时，加载远程前端主文档。
4. 主文档加载超时、网络失败或渲染失败时，本次启动自动切换内置前端。
5. 未配置远程地址或启动时无网络时，直接加载内置前端。
6. 单次启动最多自动降级一次，防止远程和本地之间循环跳转。
7. 用户可主动“重试线上版本”；否则仅在下次冷启动重新尝试远程版本。

后端 API 请求失败只呈现业务或网络错误，不触发前端资源降级。

### 3.2 内置前端服务

`zhitoubaofront` 使用 Vue Router 的 `createWebHistory`。移动端通过
`127.0.0.1:<随机端口>` 提供 `assets/web_bundle`，并对非静态资源路由回退到
`index.html`，从而兼容深层路由、刷新和资源相对路径。

服务只监听回环地址。MIME 类型、缓存头、路径规范化和目录穿越检查由本地服务统一处理。

## 4. 原生交互

Flutter 原生层负责：

- 启动页、线性加载进度和首次加载遮罩；
- 断网、超时、主页面错误、兜底加载失败的分级错误页；
- 一键重试远程版本和继续使用内置版本；
- 下拉刷新；
- Android 返回键：优先 WebView 历史，历史为空时再退出；
- iOS 平台返回手势与安全区域适配；
- 外部 HTTPS 链接转交系统浏览器；
- 受支持图片的下载、保存和系统分享；
- 相机、相册和文件选择仅在实际操作时申请权限；
- 向可信页面提供 App 版本、平台、系统版本和安全区域信息。

页面状态切换必须保留明确的加载或错误反馈，不能出现无限白屏。生命周期恢复时不无条件重载
页面，避免丢失表单和登录状态。

## 5. WebView 与 Bridge 安全边界

- WebView 只允许应用白名单中的远程主机和本机回环兜底地址进行主框架导航。
- 非白名单 HTTPS 地址交给系统浏览器；其他协议默认拒绝，明确支持的系统协议单独列出。
- JavaScript Bridge 只对可信主框架启用，不向第三方页面或子页面暴露敏感原生能力。
- Bridge 使用固定命令、版本号、结构化参数、参数大小限制和明确返回值。
- 不提供执行任意原生方法、任意文件读写或任意 URL 请求的 Bridge 命令。
- 不忽略 TLS 证书错误。
- iOS 不启用全局 `NSAllowsArbitraryLoads`。
- Android release 不全局启用明文流量。
- 下载前验证协议、响应类型、文件大小和目标文件名。
- App 不采集或保存用户密码；现有登录 Token 继续由 H5 会话管理。

## 6. 内置前端同步与版本管理

移动端工程提供脚本，从相邻仓库
`/Volumes/ExternalDrive/Code/github/zhitoubaofront` 构建并同步前端：

1. 校验前端路径、依赖工具和构建参数；
2. 使用移动端专用环境变量构建前端；
3. 将产物镜像同步到 `assets/web_bundle`，删除旧的残留文件；
4. 生成包含前端提交、构建时间、版本和文件摘要的清单；
5. 校验 `index.html`、资源引用和 SPA 路由回退；
6. 失败时不留下半更新的内置包。

前端源代码不复制到移动端仓库。移动端提交可复现的构建脚本、内置构建产物和版本清单。
线上前端承担日常快速更新，内置前端随 App 发版更新并承担可用性兜底。

内置前端必须使用绝对 `API_BASE_URL`。不能沿用浏览器部署时的相对 `/api`，否则请求会错误地
发送到回环服务。

## 7. 账号注销

### 7.1 用户流程

`zhitoubaofront` 在“我的/账号设置”增加独立危险操作区域：

1. 用户点击“注销账号”；
2. 页面说明策略停止、交易所凭据删除和历史数据匿名保留等影响；
3. 用户输入当前密码并二次确认；
4. 调用 `DELETE /api/users/account`；
5. 只有成功响应后才清除 Token、用户缓存和 WebView 会话并返回登录页。

密码错误或服务端失败时保留当前登录状态并显示可操作的错误信息。

### 7.2 后端数据语义

通过 Flyway 为用户增加账号状态和 `deleted_at`。注销在一个数据库事务中完成：

- 再次校验当前用户密码；
- 将该用户全部运行中计划设置为不可执行状态；
- 清空交易所 Access Key、Secret Key、密码等敏感凭据；
- 将姓名、邮箱和密码不可逆匿名化；
- 保留以用户 ID 关联的匿名策略、订单和必要审计记录；
- 保留注销时间和非个人化的账号状态；
- 释放原邮箱，使其可以注册为一个新账号。

计划状态先在数据库中变为不可执行。事务提交后暂停对应调度任务；即使调度暂停暂时失败，
交易执行入口也必须以数据库计划状态为最终闸门，后台协调任务继续清理残留调度。

### 7.3 Token 立即失效

当前本地 JWT 只含用户 ID 和过期时间。认证解析需要增加账号存在且为 active 的校验，使注销前
签发的 JWT 在注销提交后立即失效。WordPress 兼容认证也必须经过本地账号状态检查，不能绕过
注销状态重新建立应用会话。

注销完成后不自动恢复账号；同邮箱再次注册会创建新的账号身份。

## 8. 工程结构

```text
zhitoubaomobile/
├── lib/
│   ├── app/
│   ├── config/
│   ├── webview/
│   ├── bridge/
│   └── services/
├── assets/web_bundle/
├── scripts/
├── test/
├── integration_test/
├── android/
└── ios/
```

工程保持单一应用层和显式服务边界，不为首版引入大型状态管理框架。依赖类别限于 WebView、
网络状态、外链、分享、文件路径、权限和应用信息等已确认能力。

## 9. 错误处理与可观测性

- 配置缺失在 debug 中明确提示；生产构建脚本拒绝非 HTTPS 远程配置。
- 远程主文档失败、内置服务失败、API 失败和下载失败使用不同错误类型。
- 日志记录加载来源、状态转换、错误类别和耗时，不记录 Token、密码、交易所密钥或完整个人信息。
- Bridge 对未知命令、畸形 JSON、过大参数和不可信来源返回可识别错误。
- 错误页允许复制不含敏感信息的诊断摘要，便于审核和客服排查。

## 10. 验证策略

### 10.1 Flutter

- `dart format`、`flutter analyze`、`flutter test`；
- 配置解析、URL 白名单、导航决策和降级状态机单元测试；
- Bridge 来源、命令和参数验证测试；
- 加载、错误、重试、下拉刷新等 Widget 测试；
- 断网、超时、HTTP/TLS 错误、恢复和内置回退集成测试；
- Android 模拟器运行及 AAB 构建；
- iOS 模拟器无签名构建及 Release 构建验证。

### 10.2 前端

- 移动端构建配置和绝对 API 地址测试；
- 内置包深层路由、刷新和静态资源测试；
- 注销说明、密码确认、失败保留登录态和成功清理会话测试。

### 10.3 后端

- 密码错误时无数据变更；
- 注销事务回滚；
- 活动计划停止且不可再执行；
- 交易所敏感凭据清空；
- 个人信息匿名化、历史记录保留、原邮箱可重新注册；
- 注销前 JWT 立即失效；
- 本地和 WordPress 兼容认证均不能恢复已注销身份。

## 11. 上架准备

- 提供可用的审核演示账号，并确保审核期间后端在线；
- 准备隐私政策、服务条款和 App 内账号注销说明；
- 在审核备注说明远程前端、内置兜底和原生增强能力；
- 准备应用图标、启动图、手机截图、应用描述和支持网址；
- 正式签名证书、Apple Team 和 Play signing 由发布账号配置；
- 发布前验证 IPv6-only 网络、权限说明、隐私清单和数据安全表单；
- 发布前确认金融及数字资产相关功能符合目标市场和开发者主体要求。

参考：

- Apple App Review Guidelines: <https://developer.apple.com/app-store/review/guidelines/>
- Google Play Functionality, Content, and User Experience:
  <https://support.google.com/googleplay/android-developer/answer/9898783>
- Google Play JavaScript Interface guidance:
  <https://support.google.com/googleplay/android-developer/answer/10768383>
- Flutter SDK archive: <https://docs.flutter.dev/install/archive>

## 12. 交付边界

实施将分别在三个仓库提交：

- `zhitoubaomobile`：Flutter 工程、原生平台配置、内置前端、同步脚本、测试和文档；
- `zhitoubaofront`：移动端构建配置、Bridge 适配和账号注销界面；
- `zhitoubao`：账号注销接口、迁移、认证状态校验和测试。

每个仓库独立验证和提交，避免跨仓库混合提交。正式上线域名、签名证书、商店账号、隐私政策
链接和审核演示凭据不写入源码，均通过发布配置或商店后台提供。
