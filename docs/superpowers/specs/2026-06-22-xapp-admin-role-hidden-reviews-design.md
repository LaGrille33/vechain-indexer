# XApp Reviews — 让 App admin 与全局管理员角色可见隐藏评论

- **日期**: 2026-06-22
- **状态**: 已确认,待实现
- **影响接口**: `GET /api/v1/xapp/apps/{appId}/reviews`(评论列表)
- **相关分支**: `feature/feature_appreview`

## 1. 背景

`GET /apps/{appId}/reviews` 目前通过 MongoDB 查询的 `$or` 子句控制隐藏评论的可见性:

```
isHidden == false  OR  author == <viewer>
```

即只有**发布该隐藏评论的作者本人**(以 `wallet` query 参数标识)才能看到自己的隐藏评论。

新需求:除作者外,**该 App 的管理员**与**评论合约的管理员/审核员角色**也应能在该列表中看到隐藏评论。

## 2. 链上权威来源(已确认)

代码库此前不索引任何 app admin / 角色信息。两类身份均通过实时链上读取判定(`eth_call` 等价的 `inspectClauses`),相关合约地址与 ABI 在 indexer 中均已配置。

### 2.1 该 App 自己的 admin(per-app)

- 合约: `X2EarnApps`(`/Users/jun/Documents/ws/vebetterdao/packages/contracts/contracts/x-2-earn-apps/X2EarnApps.sol`)
- 函数: `isAppAdmin(bytes32 appId, address account) → bool`(单地址 per-app admin)
- indexer 默认地址: `0xd0d5dc52b273dbed064dd61d980c92462865f5dc`
- 完整 ABI: `packages/indexer/src/main/resources/abis/b3tr/VeBetterDAO-x-2-earns-apps.json`(仅在 indexer 模块)

### 2.2 全局评论管理角色

- 合约: `XAppReviewManager`(`/Users/jun/Documents/ws/vebetterdao/packages/contracts/contracts/x-2-earn-apps/XAppReviewManager.sol`)
- 函数: `hasRole(bytes32 role, address account) → bool`(继承自 OpenZeppelin `AccessControlUpgradeable`)
- 覆盖角色:
  - `DEFAULT_ADMIN_ROLE` = `0x0000000000000000000000000000000000000000000000000000000000000000`
  - `MODERATOR_ROLE` = `keccak256("MODERATOR_ROLE")`(不可变;合约亦暴露 `MODERATOR_ROLE()` getter)
- indexer 默认地址: `0xd369454a6fdca471831ef87f488b1f67418a336a`
- ABI: `packages/common/src/main/resources/abis/b3tr/VeBetterDAO-x-app-review-manager.json`(**已在 common**,API 模块可直接加载,含 `hasRole`、`MODERATOR_ROLE`)

> 注: 这些是 `XAppReviewManager` 合约上的**全局**角色,不随 appId 变化;`unhideReview` 的链上权限正是 `MODERATOR_ROLE` 或 `DEFAULT_ADMIN_ROLE`,与本次可见性范围一致。

## 3. 当前实现(改动基线)

- Controller: `packages/api/src/main/kotlin/org/vechain/indexer/xapp/review/ReviewController.kt`(`getAppReviews`,`wallet` 经 `normalise` 后作为 `viewer` 传入 service)
- Service: `packages/api/src/main/kotlin/org/vechain/indexer/xapp/review/ReviewApiService.kt`(`getAppReviews`,4 分支 `when` 选择 repo 查询与 count)
- Repository: `packages/common/src/main/kotlin/org/vechain/indexer/xapp/review/repository/ReviewRepository.kt`(隐藏过滤全部在 `@Query` 字符串里)
- Review 文档: `packages/common/src/main/kotlin/org/vechain/indexer/xapp/review/Review.kt`(`isHidden: Boolean`)
- 运行时合约调用范式: `packages/api/src/main/kotlin/org/vechain/indexer/xapp/review/VoteDomainResolver.kt`(`AbiLoader.loadFunctions` → `ContractUtils.createClause` → `runBlocking { thorClient.inspectClauses(...) }` → `FunctionReturnDecoder.decode`)
- 单条评论接口 `GET /reviews/{reviewId}`(`getReview`)用 `findByIdOrNull`,**本就无条件返回隐藏评论**,不在本次范围。

## 4. 方案选择

**方案 A:请求时实时链上读取**(已采纳)。一次请求内批量 `inspectClauses` 发 3 个 clause 判定可见性。

已否决:
- 方案 B(新增 indexer 把 admin/角色写进 MongoDB):动件多、有漂移与回填成本,且查询仍多一次 DB lookup。
- 方案 C(在 metadata 刷新时顺带读 admin):admin 变更后有刷新延迟、仍需回填。

理由: admin/角色变更低频但要求准确,实时调用零漂移;改动最小、风险最低;成本是一次批量 bool 读取,完全复用既有 `VoteDomainResolver` 范式。

## 5. 可见性判定(核心)

`wallet`(`viewer`)非空时,一次 `inspectClauses` 批量发 **3 个 clause**(不同 `to` 地址可同批),各解码为 bool:

| # | 调用 | 目标地址 |
|---|------|----------|
| 1 | `X2EarnApps.isAppAdmin(appId, wallet)` | `X2EARN_APPS_CONTRACT` |
| 2 | `XAppReviewManager.hasRole(MODERATOR_ROLE, wallet)` | `XAPP_REVIEW_MANAGER_CONTRACT` |
| 3 | `XAppReviewManager.hasRole(DEFAULT_ADMIN_ROLE, wallet)` | `XAPP_REVIEW_MANAGER_CONTRACT` |

```
canSeeHidden = c1 || c2 || c3
```

任一为 true → 本次查询不加 `isHidden` 过滤(admin/角色看到全部,含隐藏);否则完全走现有逻辑。

## 6. 详细设计

### 6.1 新增组件 `ReviewHiddenVisibilityResolver`(API 模块)

镜像 `VoteDomainResolver`。位置: `packages/api/src/main/kotlin/org/vechain/indexer/xapp/review/ReviewHiddenVisibilityResolver.kt`。

- 注入: `ThorClient`、`@Value("\${business-event.substitutions.X2EARN_APPS_CONTRACT:\${X2EARN_APPS_CONTRACT:}}")`、`@Value("\${business-event.substitutions.XAPP_REVIEW_MANAGER_CONTRACT:\${XAPP_REVIEW_MANAGER_CONTRACT:}}")`。
- `fun canSeeHiddenReviews(appId: String, wallet: String): Boolean`:
  - 任一合约地址为空 → 返回 `false`(功能降级关闭,等同现状)。
  - 加载 ABI:`hasRole` 从 `abis/b3tr`(已有 common review-manager ABI);`isAppAdmin` 从 `abis/xapps`(新增最小片段,见 6.4)。
  - 构造 3 个 clause(bytes32 appId、address wallet、bytes32 role 的编码须与 ABI 类型对齐,参考 `ContractUtils.createClause` 与 `AddressUtils.toBigInt`)。
  - `runBlocking { thorClient.inspectClauses(clauses, BlockRevision.Keyword.BEST) }`,逐条 `isSuccessWithData` 校验 + `FunctionReturnDecoder.decode` 取 bool。
  - 异常或任一调用失败 → 返回 `false`(降级为公开视图,不阻断请求)。
- 角色常量: `DEFAULT_ADMIN_ROLE = 0x0`;`MODERATOR_ROLE = keccak256("MODERATOR_ROLE")` 硬编码并在注释写明来源;可选启动期读取合约 `MODERATOR_ROLE()` 校验。
- 缓存: `ConcurrentHashMap`,key = `appId:wallet`,TTL ~60s(避免翻页/前端刷新重复打节点)。admin/角色变更罕见,60s 延迟可接受(详见 §8)。
- `@Profile("b3tr", "xapp-review", "xapp-review-vote")`;以**可空**注入 `ReviewApiService`(同 `domainResolver` 模式,便于单测)。

### 6.2 Repository 改动(最小,新增 2 个方法)

`packages/common/src/main/kotlin/org/vechain/indexer/xapp/review/repository/ReviewRepository.kt`:

```kotlin
@Query("{ 'appId': ?0 }")
fun findAllByAppIdIncludingHidden(appId: String, pageable: Pageable): Slice<Review>

fun countByAppId(appId: String): Long   // 不过滤隐藏的总数
```

`canSeeHidden && effectiveAuthor != null` 的分支**直接复用**已有的 `findAllByAppIdAndAuthor` / `countByAppIdAndAuthor`(它们本就无 `isHidden` 子句)。

### 6.3 `ReviewApiService.getAppReviews` 分支(admin/角色短路在前,现有分支原样不动)

```kotlin
val canSeeHidden = viewer != null && (visibilityResolver?.canSeeHiddenReviews(appId, viewer) ?: false)

val slice = when {
    canSeeHidden && effectiveAuthor != null ->
        reviewRepository.findAllByAppIdAndAuthor(appId, effectiveAuthor, pageable)      // 复用
    canSeeHidden ->
        reviewRepository.findAllByAppIdIncludingHidden(appId, pageable)                 // 新增
    effectiveAuthor != null && viewer != null ->
        reviewRepository.findAllByAppIdAndAuthorWithViewerHidden(appId, effectiveAuthor, viewer, pageable)  // 现有
    effectiveAuthor != null ->
        reviewRepository.findAllByAppIdAndAuthor(appId, effectiveAuthor, pageable)       // 现有
    viewer != null ->
        reviewRepository.findAllByAppIdWithViewerHidden(appId, viewer, pageable)         // 现有
    else ->
        reviewRepository.findAllByAppId(appId, pageable)                                 // 现有
}
// count 的 when 结构同理:
//   canSeeHidden && effectiveAuthor != null -> countByAppIdAndAuthor
//   canSeeHidden                            -> countByAppId          // 新增
//   其余 4 个现有分支不变
```

`my_reviews` + 可见 hidden 的边角:`sortBy=my_reviews` 时 `effectiveAuthor = viewer`,落入第一条分支,看到自己评论(含隐藏),语义一致。

### 6.4 ABI 接线

- `hasRole`: 已在 common `abis/b3tr/VeBetterDAO-x-app-review-manager.json`,API 模块经 `AbiLoader.loadFunctions("abis/b3tr", listOf("hasRole"))` 可加载。
- `isAppAdmin`: 在 `packages/common/src/main/resources/abis/xapps/` 新增**仅含 `isAppAdmin` 的最小 ABI 片段**(如 `x2earn-apps.json`),独立目录避免与 indexer 的 `abis/b3tr/VeBetterDAO-x-2-earns-apps.json` 在 indexer classpath 上重复定义同名函数;`AbiLoader.loadFunctions("abis/xapps", listOf("isAppAdmin"))`。
- 实现期需确认 `AbiLoader` 的目录扫描方式,以及 bytes32 appId 的传参形式(devkit `Function.encodeToHex` 对 bytes32 通常接受 0x 前缀 hex 字符串或 32 字节 ByteArray;`normalise` 后的 appId 为去 `0x` 小写,需按需补回)。

### 6.5 配置(API `application.yaml`)

在 `packages/api/src/main/resources/application.yaml` 的 `business-event.substitutions` 下新增:

```yaml
X2EARN_APPS_CONTRACT: ${X2EARN_APPS_CONTRACT:0xd0d5dc52b273dbed064dd61d980c92462865f5dc}
XAPP_REVIEW_MANAGER_CONTRACT: ${XAPP_REVIEW_MANAGER_CONTRACT:0xd369454a6fdca471831ef87f488b1f67418a336a}
```

地址与 indexer 一致;任一为空 → 对应检查跳过(返回 false),功能可降级关闭。

## 7. `total` / 分页一致性

`canSeeHidden` 视图的 `total` 必须含隐藏评论,否则 `total`/`totalPages`/`hasNext` 错乱 → 用新增的 `countByAppId`(无 author)或已有 `countByAppIdAndAuthor`(有 author),与 slice 查询对齐。

## 8. 安全说明(需产品/调用方知晓)

`wallet` 是**未鉴权**的 query 参数——任何人都能传任意地址。现状下这已意味着任何人可冒充任意 author 看其隐藏评论(既有行为)。本次扩展沿用同一信任模型:理论上有人可传 admin/角色地址看隐藏评论。

可接受理由:隐藏评论本身敏感度低(举报 ≥20 自动隐藏,内容原本公开,作者本人已可见),且单条评论接口 `GET /reviews/{reviewId}` 本就无条件返回隐藏评论。若未来需更强保证,应引入签名/鉴权头,属另一独立工作(本次不做)。

## 9. 文档 / Swagger / 生成物

- `ReviewController` 的 `@Operation` description 补一句:the app's admin and the review-manager's admin/moderator roles also see hidden reviews。
- `docs/xapp-rating-review-api.md` §2.2 的 `wallet` 参数说明同步(admin/角色可见隐藏评论)。
- 按 CLAUDE.md 要求:`make dd-refresh-generated`,并提交重新生成的 `metrics/datadog/*.json` 与 `metrics/grafana/.../*.json`(若有 drift)。

## 10. 测试

- `ReviewApiService` 单测(mock `ReviewHiddenVisibilityResolver`):
  - `canSeeHidden=true` + 无 author → 走 `findAllByAppIdIncludingHidden`,`total` 含隐藏。
  - `canSeeHidden=true` + 有 author → 复用 `findAllByAppIdAndAuthor`,`total` 含隐藏。
  - `canSeeHidden=false` → 现有 4 分支行为不变(回归)。
  - resolver 抛异常 / 为 null → 降级 false,行为同 `canSeeHidden=false`。
- `ReviewHiddenVisibilityResolver` 单测:
  - 3 个 bool 的 8 种组合抽样,`||` 结果正确。
  - 异常 / 地址为空 → 返回 false。
  - 缓存命中(同一 `appId:wallet` 二次调用不打节点)。
- ABI 编码(bytes32 appId、address wallet、bytes32 role)与既有 `ReviewService.enrichWithContractData`、`VoteDomainResolver` 范式对齐。

## 11. 不在范围内

- 单条评论接口 `GET /reviews/{reviewId}`(已无条件返回隐藏评论)。
- 把 admin/角色索引进 MongoDB(方案 B/C)。
- `wallet` 参数的鉴权/签名加固(§8)。

## 12. 风险与注意事项

- **ABI 编码**: bytes32 / address 传参须严格匹配 devkit 预期;实现期以单元测试固定下来。
- **节点依赖**: admin/角色可见性依赖节点可用;失败时 fail-open 为 false(看不到隐藏),不阻断主流程。
- **缓存时效**: 60s TTL 内 admin/角色撤销不会立即生效;鉴于变更罕见且仅为读路径可见性(非安全边界),可接受。
