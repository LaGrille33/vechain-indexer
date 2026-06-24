# VBD生态系统透明化、基于价值的声誉层提案

## 目录
1. [档案评分（Profile Score）](#档案评分profile-score)
2. [活跃度评分（Activity Score）](#活跃度评分activity-score)
3. [分配效率评分（Distribution Efficiency Score）](#分配效率评分distribution-efficiency-score)
4. [社区评分（Community Score）](#社区评分community-score)
5. [总体评分（Total Score）](#总体评分total-score)

---

## 档案评分（Profile Score）

设 $M_{total}$ 为选填基础字段总数，$M_{completed}$ 为已填写的选填字段数：

$$StaticScore = \frac{M_{completed}}{M_{total}} \times 100 \quad (0 \le StaticScore \le 100)$$

此外引入产品与社交动态的更新**时效性因子 (Recency Factor)**：

* **产品更新时效 (Product Recency)**：设 $DaysSinceProductUpdate$ 为距离上一次有效“近况更新”的天数：
  $$\text{Product Recency} = \begin{cases} 1.0 & \text{若 } \le 14 \text{ 天} \\ 0.5 & \text{若 } 14 \sim 30 \text{ 天} \\ 0 & \text{若 } > 30 \text{ 天} \end{cases}$$
* **社交媒体时效 (Social Recency)**：设 $DaysSinceSocialUpdate$ 为距离上一次有效社交平台发布动态的天数：
  $$\text{Social Recency} = \begin{cases} 1.0 & \text{若 } \le 14 \text{ 天} \\ 0.5 & \text{若 } 14 \sim 30 \text{ 天} \\ 0 & \text{若 } > 30 \text{ 天} \end{cases}$$

组合时效因子及最终资料得分公式：

$$RecencyFactor = \frac{\text{Product Recency} + \text{Social Recency}}{2} \quad (0 \le RecencyFactor \le 1)$$

$$ProfileScore = StaticScore \times (0.8 + 0.2 \times RecencyFactor) \quad (0 \le ProfileScore \le 100)$$

*(注：基础填写占 80% 绝对权重，时效动态上下微调 $\pm20\%$)*。

---

## 活跃度评分（Activity Score）

### 指标定义

对于每个dApp，定义以下指标：

- **WAU_7d**：某个轮次内执行至少一个有效操作的独特钱包数量
  ```
  WAU_7d = 某个轮次内执行至少一个有效操作的钱包数
  ```

- **NewUsers_7d**：某个轮次内首次与该dApp交互的钱包数量
  ```
  NewUsers_7d = 某个轮次内首次交互的钱包数
  ```

- **Actions_7d**：某个轮次内执行的有效可奖励操作总数
  ```
  Actions_7d = 某个轮次内有效操作总数
  ```

### 对数缩放（Log Scaling）

为了降低超大型dApp的主导地位，使用自然对数：

```
WAU_scaled = ln(1 + WAU_7d)
NewUsers_scaled = ln(1 + NewUsers_7d)
Actions_scaled = ln(1 + Actions_7d)
```

### 百分位排名（Percentile Rank）

将每个指标转换为所有符合条件的dApp中的百分位排名：

- P_WAU = PercentileRank(WAU_scaled) 在所有符合条件的dApp中
- P_NewUsers = PercentileRank(NewUsers_scaled) 在所有符合条件的dApp中
- P_Actions = PercentileRank(Actions_scaled) 在所有符合条件的dApp中

#### 百分位排名定义
```
P(x_i) = (# { j | x_j < x_i } / N) × 100
其中：0 ≤ P(x_i) ≤ 100

说明：
- x_i = dApp i的指标值
- x_j = 另一个符合条件的dApp的指标值
- N = 符合条件的dApp总数
- # { j | x_j < x_i } = 指标值严格低于x_i的符合条件dApp数量
```

#### 并列处理（Tie Rule）
当多个dApp具有相同值时，使用以下规则以避免不稳定性：

```
如果T个dApp共享相同值x_i，则：
P(x_i) = (# { j | x_j < x_i } + 0.5 × T) / N × 100
```
说明：这为并列值分配了并列条目之间的中点百分位数。

### 活跃度评分计算

对于每个dApp：
```
ActivityScore = 0.5 × P_WAU + 0.3 × P_NewUsers + 0.2 × P_Actions
其中：0 ≤ ActivityScore ≤ 100
```

---

## 分配效率评分（Distribution Efficiency Score）

### 指标定义

对于每个dApp：
- **Alloc_7d**：过去7天分配的B3TR总量
- **Dist_7d**：过去7天分配出去的B3TR总量

### 分配效率计算

```
DistributionEfficiency = Dist_7d / Alloc_7d
```

#### 边界条件
- 如果 Alloc_7d = 0，则 DistributionEfficiency = 0
- 值被限制在0到1之间，防止扭曲效率指标：
  ```
  DistributionEfficiency = min(Dist_7d / Alloc_7d, 1)
  ```

#### 含义
- **1.0**：完全分配了分配额
- **0.5**：分配了一半
- **0**：未进行任何分配

### 转换为百分位排名

将分配效率转换为百分位排名：
```
P_DistributionEfficiency = PercentileRank(DistributionEfficiency)
范围：0-100
```

### 分配评分计算

对于每个dApp：
```
DistributionScore = P_DistributionEfficiency
其中：0 ≤ DistributionScore ≤ 100
```

---

## 社区评分（Community Score）

### 指标定义

对于每个dApp：
- **AvgRating**：所有有效星级评分的算术平均值（范围：1-5）
- **NumRatings**：有效评分的总数

#### 防止操纵（低样本保护）
为防止来自极小样本的操纵：
```
如果 NumRatings < R_min，则 CommunityScore = 0
其中：R_min = 20（可通过治理调整）
```

### 帮助性投票指标

- **HelpfulVotes**：所有评论中获得的赞成票总数
- **UnhelpfulVotes**：所有评论中获得的反对票总数

### 置信度因子（Confidence Factor）

为了降低低评分数的影响，应用置信度因子：

```
ConfidenceFactor = 1 - e^(-NumRatings/K)

说明：
- e = 欧拉常数
- K = 平滑常数（例如：100）

特性：
- 当 NumRatings 很小时，ConfidenceFactor 很小
- 当 NumRatings 增加时，ConfidenceFactor 趋近于 1
- 函数平滑增长，渐近逼近
```

### 帮助性比率和参与度因子

#### 帮助性比率（Helpful Ratio）
```
HelpfulRatio = HelpfulVotes / (HelpfulVotes + UnhelpfulVotes)

特殊情况：
如果不存在投票，则 HelpfulRatio = 0.5
```

#### 参与度因子（Engagement Factor）
```
EngagementFactor = 0.5 + 0.5 × HelpfulRatio
其中：0.5 ≤ EngagementFactor ≤ 1

含义：
- 评论被广泛标记为有帮助 → 小幅提升
- 评论被广泛标记为无帮助 → 小幅惩罚
- 没有参与 → 中立
```

### 调整评分（Adjusted Rating）

```
AdjustedRating = AvgRating × ConfidenceFactor × EngagementFactor
其中：0 ≤ AdjustedRating ≤ 5
```

### 社区评分计算

社区评分是调整评分在所有符合条件dApp中的百分位排名：

```
P_AdjustedRating = PercentileRank(AdjustedRating)

CommunityScore = P_AdjustedRating
其中：0 ≤ CommunityScore ≤ 100
```

#### 解释
- **高AvgRating + 高NumRatings** → 高CommunityScore
- **高AvgRating + 低NumRatings** → 中等CommunityScore
- **低AvgRating + 高NumRatings** → 低CommunityScore

---

## 总体评分（Total Score）

### 最终评分公式

```
TotalScore = 0.1 × ProfileScore 
           + 0.3 × ActivityScore 
           + 0.3 × DistributionScore 
           + 0.3 × CommunityScore

其中：0 ≤ TotalScore ≤ 100
```

### 权重分布
- **档案评分**：10%
- **活跃度评分**：30%
- **分配效率评分**：30%
- **社区评分**：30%

---

## 关键设计原则

1. **防止大型dApp垄断**：使用对数缩放和百分位排名
2. **样本数量保护**：在社区评分中应用置信度因子
3. **防止操纵**：最小评分阈值要求
4. **平衡激励**：多维度评分体系
5. **灵活治理**：关键参数可通过治理调整

---

**文档生成日期**：2025年
**文档类型**：VBD生态系统声誉层提案
