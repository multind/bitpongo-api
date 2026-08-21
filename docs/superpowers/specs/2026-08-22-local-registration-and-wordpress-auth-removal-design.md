# 本地注册与移除 WordPress 认证设计

## 背景与目标

当前后端同时包含本地账号登录和 WordPress 登录兼容代码，前端只提供登录页面，虽然已有本地注册接口，但没有完整的注册交互，也不会在注册成功后直接建立会话。项目尚未上线，因此本次不迁移 WordPress 用户、WordPress Token 或外部身份数据。

本次改造的目标是彻底删除 WordPress 认证链路，只保留本地账号和 JWT；补齐网页端注册功能，并把验证后的网页静态包同步到移动端。

## 范围

### 后端 `bitpongo-api`

- 删除 `/api/users/v1/login`。
- 删除 `WordPressAuthClient`、`HttpWordPressAuthClient`、`WordPressSession` 及其测试。
- `AuthenticatedUserResolver` 只接受本系统签发的 JWT，不再回退解析 WordPress Token。
- `UserApplicationService` 不再依赖 WordPress 客户端和外部身份墓碑仓库。
- 账号注销不再创建 WordPress 外部身份墓碑。
- 删除 `zhitoubao.wordpress` 配置。
- 新增 Flyway 迁移，删除 `deleted_external_identity` 表及 `user.auth_provider` 字段；不修改已经存在的历史迁移，避免 Flyway 校验和变化。
- 保留本地登录 `/api/users/login`。
- 调整 `/api/users/register`，注册成功后签发 JWT，并返回与登录相同的 `ApiResponse<LoginData>`。

### 网页端 `bitpongo-front`

- 新增独立 `/register` 页面。
- 注册表单包含姓名、邮箱、密码、确认密码和用户协议确认。
- 登录页增加“没有账号？立即注册”入口。
- 注册页增加“已有账号？去登录”入口。
- 注册成功后保存 JWT 和用户信息，然后跳转 `/member`。
- 中文、英文和繁体中文文案同步补齐，并保持现有类型约束。

### 移动端 `bitpongo-mobile`

- 不修改 Flutter 认证业务。
- 前后端验证通过后，使用现有构建脚本重新生成并同步 `assets/web_bundle`。
- 保留移动端工作树中现有的未提交修改，不覆盖或清理用户文件。

## 接口契约

### 注册请求

`POST /api/users/register`

```json
{
  "name": "张三",
  "email": "user@example.com",
  "password": "abc12345"
}
```

后端对邮箱执行首尾去空格及小写规范化。密码必须至少 8 位，且同时包含字母和数字。

### 注册与登录成功响应

注册和登录使用相同的响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "info": {
      "id": 1,
      "name": "张三",
      "email": "user@example.com"
    }
  }
}
```

注册在一个后端请求内完成用户创建和 JWT 签发，前端不再追加第二次登录请求。

## 组件与数据流

1. 注册页先校验必填字段、邮箱格式、密码规则、两次密码一致和用户协议确认。
2. 前端调用 `/users/register`。
3. 后端重复执行不可绕过的格式和密码规则校验。
4. `UserApplicationService` 在事务中规范化邮箱、检查重复用户、哈希密码并保存本地用户。
5. 保存成功后立即签发 JWT，返回 `LoginData`。
6. 前端用户仓库通过统一的会话写入方法保存 `token` 和 `info`，随后进入会员页面。
7. 后续受保护请求只由本地 JWT 解析器认证。

## 错误处理

- 姓名、邮箱或密码为空：返回参数校验错误。
- 邮箱格式不合法：返回“邮箱格式错误”。
- 密码少于 8 位或没有同时包含字母和数字：返回明确的密码规则提示。
- 两次密码不一致：前端阻止提交并显示提示。
- 未同意用户协议：前端阻止提交并显示提示。
- 邮箱已存在：返回“用户已存在”。
- 两个并发请求使用同一邮箱：捕获数据库唯一约束异常，并同样返回“用户已存在”，不得泄露数据库异常或返回 500。
- 原 WordPress Token 和 `/api/users/v1/login` 不再兼容。

## 数据库变更

新增下一版本 Flyway 迁移：

```sql
DROP TABLE IF EXISTS deleted_external_identity;
ALTER TABLE `user` DROP COLUMN auth_provider;
```

实体同时移除 `authProvider` 属性。历史迁移保持不变，空库和已经应用旧迁移的数据库都可顺序升级。

## 测试与验收

### 后端

- 注册成功时创建用户、哈希密码、规范化邮箱并返回可解析的本地 JWT。
- 密码规则边界测试：长度不足、缺少字母、缺少数字和有效密码。
- 重复邮箱和数据库唯一约束竞争都返回相同业务错误。
- `/api/users/v1/login` 不再作为公开接口。
- WordPress 客户端、配置和外部 Token 回退代码不存在。
- 账号注销仍能停止计划、清除交易所密钥并匿名化本地用户。
- Flyway 空库迁移和旧库兼容迁移测试通过。

### 网页端

- API 层按约定发送注册请求。
- 用户仓库在注册成功后持久化 `token` 和 `info`。
- 注册页面校验密码规则、确认密码和协议确认。
- 注册成功跳转 `/member`，失败显示后端错误且不写入会话。
- 登录页与注册页可互相导航。
- 单元测试、TypeScript 类型检查和生产构建通过。

### 移动端

- 网页静态包通过现有同步脚本生成，清单包含新的前端提交标识和文件哈希。
- 移动端验证脚本通过。
- 不把移动端原有未提交修改混入本次提交；静态包同步产生的文件单独提交。

## 非目标

- 不迁移或恢复 WordPress 用户。
- 不支持 WordPress Token。
- 不增加找回密码、邮件验证、验证码、第三方登录或管理员审批。
- 不重构与认证无关的前后端模块。
- 不修改移动端原生登录逻辑，因为当前认证页面来自内嵌网页。

## 交付与仓库边界

- 后端改动在当前认证功能分支上完成并独立提交。
- 网页端当前 `.eslintrc-auto-import.json` 的已有修改属于用户，实施时不得覆盖或纳入提交。
- 移动端当前 `README.md`、`ios/Podfile.lock`、`android/app/src/debug/res/` 和 `scripts/verify.sh` 的已有修改属于用户，实施时不得覆盖或纳入认证功能提交。
- 三个仓库分别运行验证并分别提交，最终明确报告每个仓库的测试、提交和推送状态。
