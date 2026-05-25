# XApp Review & Scoring — 后端接口文档

---

## 一、总体架构

```
前端 → 合约（写操作 / 读 storage）
前端 → Indexer API（读索引数据）
链上事件 → Indexer → MongoDB → Indexer API
```

| 层 | 说明 |
|---|---|
| **合约** | XAppReviewManager / XAppScoring，处理写操作 + 读取 storage 详情 |
| **Indexer** | 监听链上事件，加工后写入 MongoDB |
| **API** | 从 MongoDB 读数据，提供分页查询接口 |

**原则：写操作走合约（消耗 gas），列表查询走 Indexer API（快、免费），单条详情可走合约 `getReview()` 获取实时数据。**

---

## 二、合约地址

环境对应的合约地址在 vechain-indexer 的 `application.yaml` 中配置：

| 合约 | 配置 key | 说明 |
|------|----------|------|
| XAppReviewManager | `XAPP_REVIEW_MANAGER_CONTRACT` | 评论管理 |
| XAppScoring | `XAPP_SCORING_CONTRACT` | 应用评分 |

前端通过 `@repo/config` 的 `getConfig()` 获取：
- `getConfig().xAppReviewManagerContractAddress`
- `getConfig().xAppScoringContractAddress`

---

## 三、合约交互

### 3.1 XAppReviewManager（评论管理合约）

**TypeChain 工厂**：`XAppReviewManager__factory`（来自 `@vechain/vebetterdao-contracts/typechain-types`）

#### 写操作（需要钱包签名，消耗 gas）

| 方法 | 参数 | 说明 |
|------|------|------|
| `createReview` | `(bytes32 appId, uint8 rating, string title, string content)` | 创建评论，返回 `reviewId` |
| `editReview` | `(uint256 reviewId, uint8 rating, string title, string content)` | 编辑评论，仅原作者 |
| `voteOnReview` | `(uint256 reviewId, uint8 voteType)` | 投票：1=UPVOTE, 2=DOWNVOTE, 3=REPORT |
| `unhideReview` | `(uint256 reviewId)` | 取消隐藏，需 MODERATOR_ROLE |

**约束**：
- `rating`: 1-5
- `title`: 最长 200 字符
- `content`: 最长 2000 字符
- 不能给自己的评论投票
- 同一用户对同一评论只能投一种票（重复投同一类型会 revert）
- 举报 ≥20 次自动隐藏

#### 读操作（免费，不消耗 gas）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `getReview` | `(uint256 reviewId)` | `Review struct` | 获取完整评论数据（含 title/content） |
| `getAppReviewIds` | `(bytes32 appId)` | `uint256[]` | 获取 App 的所有评论 ID |
| `getUserReviewIds` | `(address user)` | `uint256[]` | 获取用户的所有评论 ID |
| `getAppReviewStats` | `(bytes32 appId)` | `(uint256 count, uint256 avgRating)` | 评论数 + 平均分（×100） |
| `getUserVote` | `(uint256 reviewId, address user)` | `VoteType` | 查询用户对某评论的投票 |
| `totalReviews` | — | `uint256` | 评论总数 |

**Review struct 字段**：
```
id       uint256   评论 ID
appId    bytes32   App ID
reviewer address   评论者地址
rating   uint8     评分 1-5
title    string    标题
content  string    内容
createdAt  uint256  创建时间戳
updatedAt  uint256  更新时间戳
upvotes    uint256  赞成数
downvotes  uint256  反对数
reports    uint256  举报数
hidden     bool     是否隐藏
```

### 3.2 XAppScoring（应用评分合约）

**TypeChain 工厂**：`XAppScoring__factory`

#### 写操作

| 方法 | 参数 | 说明 |
|------|------|------|
| `recordScores` | `(uint256 round, bytes32[] appIds, uint256[] totalScores, tuple[] breakdowns)` | 批量写入评分，需 SCORER_ROLE |
| `finalizeRound` | `(uint256 round, bytes32[] rankedAppIds)` | 定稿排名 |
| `updateRoundRankings` | `(uint256 round, bytes32[] rankedAppIds)` | 更新已定稿的排名 |

#### 读操作

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `getRoundResults` | `(uint256 round)` | `(bytes32[] appIds, uint256[] totalScores)` | 轮次所有 App 的分数 |
| `getRoundRankings` | `(uint256 round)` | `bytes32[]` | 轮次排名（需已定稿） |
| `getLatestAppScore` | `(bytes32 appId)` | `(uint256 round, uint256 score)` | App 最新评分 |
| `getAppScore` | `(bytes32 appId, uint256 round)` | `uint256` | App 在某轮次的分数 |
| `getAppScoreHistory` | `(bytes32 appId)` | `ScoreEntry[]` | App 所有轮次评分历史 |
| `getAppScoredRounds` | `(bytes32 appId)` | `uint256[]` | App 参与的所有轮次 |
| `isRoundFinalized` | `(uint256 round)` | `bool` | 轮次是否已定稿 |

---

## 四、Indexer API

Base URL: `{indexer-host}/api/v1/xapp`

### 通用说明

**分页参数**（所有列表接口）：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码，从 0 开始 |
| `size` | int | 20 | 每页条数，最大 150 |
| `direction` | string | DESC | `ASC` / `DESC` |

**响应格式**：

```json
{
  "data": [...],
  "pagination": {
    "hasNext": true
  }
}
```

> 注意：字段是 `data`，不是 `content`。翻页用 `pagination.hasNext`。

### 4.1 获取 App 的评论列表

```
GET /api/v1/xapp/apps/{appId}/reviews
```

| 路径参数 | 类型 | 说明 |
|----------|------|------|
| `appId` | string | App ID（bytes32 hex，如 `0x6d25...77b4`） |

**data 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `reviewId` | number | 评论 ID |
| `appId` | string | App ID |
| `author` | string | 评论者地址（checksummed） |
| `rating` | number | 评分 1-5 |
| `title` | string | 标题（来自合约 storage） |
| `content` | string | 内容（来自合约 storage） |
| `upvotes` | number | 赞成数 |
| `downvotes` | number | 反对数 |
| `reports` | number | 举报数 |
| `isHidden` | boolean | 是否被隐藏 |
| `blockTimestamp` | number | 链上时间戳（Unix 秒） |

**示例**：

```json
{
  "data": [
    {
      "reviewId": 6,
      "appId": "0x6d255fc3390ee6b41191da315958b7d6a1e5b17904cc7683558f98acc57977b4",
      "author": "0x4F064B42dD4583754cd1E2D21C8FeF2786CbD4a9",
      "rating": 4,
      "title": "Great app",
      "content": "Really good experience using this app.",
      "upvotes": 2,
      "downvotes": 0,
      "reports": 0,
      "isHidden": false,
      "blockTimestamp": 1779424224
    }
  ],
  "pagination": { "hasNext": false }
}
```

### 4.2 获取单条评论

```
GET /api/v1/xapp/reviews/{reviewId}
```

返回单个 Review 对象，不存在返回 404。

### 4.3 获取用户的评论

```
GET /api/v1/xapp/users/{wallet}/reviews
```

返回该用户发表的所有评论，分页格式同上。

### 4.4 评分轮次列表

```
GET /api/v1/xapp/scoring/rounds
```

**data 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `round` | number | 轮次编号 |
| `appCount` | number | 参与评分的 App 数量 |
| `rankedAppCount` | number \| null | 排名 App 数（定稿后有值） |
| `isFinalized` | boolean | 是否已定稿 |
| `hasRankingsUpdated` | boolean | 是否更新过排名 |
| `blockTimestamp` | number | 链上时间戳（Unix 秒） |

### 4.5 单个评分轮次

```
GET /api/v1/xapp/scoring/rounds/{round}
```

返回单个 ScoringRound 对象，不存在返回 404。

---

## 五、前端参考架构

```typescript
// 合约：写操作 + 单条详情（实时数据）
import { XAppReviewManager__factory } from "@vechain/vebetterdao-contracts/typechain-types"

const address = getConfig().xAppReviewManagerContractAddress
const abi = XAppReviewManager__factory.abi

// 创建评论
encodeFunctionData("createReview", [appId, rating, title, content])

// 获取评论详情（实时，来自合约 storage）
useCallClause({ abi, address, method: "getReview", args: [reviewId] })

// API：列表查询（快速、已索引）
// GET /api/v1/xapp/apps/{appId}/reviews?page=0&size=10&direction=DESC
// 响应: { data: Review[], pagination: { hasNext: boolean } }
```
