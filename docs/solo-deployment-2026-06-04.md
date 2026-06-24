# Solo 链部署信息

- **部署日期**: 2026-06-04
- **Thor Solo 节点地址**: `https://vebetterdaothortest.smartcarbonlink.com`
- **部署耗时**: 6 分 15 秒
- **Solidity 版本**: 0.8.20, 优化器已开启, runs: 1
- **助记词**: `denial kitchen pet squirrel other broom bar gas better priority spoil cross`

---

## 关键账户

| 角色 | 地址 | 账户序号 |
|---|---|---|
| 合约管理员 / 部署者 | `0xf077b491b355E64048cE21E3A6Fc4751eEeA77fa` | #0 |
| Vote2Earn 奖励池 | `0x435933c8064b4Ae76bE665428e0307eF2cCFBD68` | #1 |
| 多签签名者 3 | `0x0F872421Dc479F3c11eDd89512731814D0598dB5` | #2 |
| Stargate 节点持有者 1 | `0xF370940aBDBd2583bC80bfc19d19bc216C88Ccf0` | #3 |
| Stargate 节点持有者 2 | `0x99602e4Bbc0503b8ff4432bB1857F916c3653B85` | #4 |
| Stargate 节点持有者 3 | `0x61E7d0c2B25706bE3485980F39A3a994A8207aCf` | #5 |
| Stargate 节点持有者 4 | `0x361277D1b27504F36a3b33d3a52d1f8270331b8C` | #6 |
| Stargate 节点持有者 5 | `0xD7f75A0A1287ab2916848909C8531a0eA9412800` | #7 |
| Stargate 节点持有者 6 | `0xAbEf6032B9176C186F6BF984f548bdA53349f70a` | #8 |
| Stargate 节点持有者 7 | `0x865306084235Bf804c8Bba8a8d56890940ca8F0b` | #9 |
| TestApp 创建者 | `0x23e2B78d75e5B7f288BbD4464ace9b15AC180DD8` | #10 |
| TestApp1 创建者 | `0xB4101c41...` | #11 |
| TestApp2 创建者 | `0x2A03B90B...` | #12 |
| TestApp3 创建者 | `0x370e1389...` | #13 |
| TestApp4 创建者 | `0xb2cD6654...` | #14 |
| TestApp5 创建者 | `0x7fd7ec76...` | #15 |
| TestApp6 创建者 | `0xC8fd1cEF...` | #16 |
| TestApp7 创建者 | `0x43Abb3Dc...` | #17 |
| TestApp8 创建者 | `0x79276209...` | #18 |
| TestApp9 创建者 | `0x33714Fe0...` | #19 |
| TestApp10 创建者 | `0x54cA4C83...` | #20 |
| TestApp11 创建者 | `0x16054Af8...` | #21 |
| TestApp12 创建者 | `0x24d23fF5...` | #22 |
| TestApp13 创建者 | `0x644f20f6...` | #23 |
| TestApp14 创建者 | `0x841c3372...` | #24 |
| TestApp15 创建者 | `0xAb6c2e73...` | #25 |
| TestApp16 创建者 | `0xDCC7d54B...` | #26 |
| TestApp17 创建者 | `0x5748218C...` | #27 |
| TestApp18 创建者 | `0x72A93763...` | #28 |
| TestApp19 创建者 | `0xDd3B79b1...` | #29 |
| TestApp20 创建者 | `0x69ee99E7...` | #30 |

所有 10 个 Stargate 节点均以 **7 级** 质押（每个持有者 6 个节点，共 30 个）。

### 助记词派生说明

- **助记词**: `denial kitchen pet squirrel other broom bar gas better priority spoil cross`
- **派生路径**: VeChain HD 路径（`m/44'/818'/0'/0`）
- **派生账户数**: 110 个（#0 ~ #109）
- **每个账户初始余额**: 1,000,000 VET

---

## 合约地址（代理地址）

| 合约 | 地址 |
|---|---|
| B3TR | `0xfac5b9965899dc1249908b84c22d7db9efbcec07` |
| VOT3 | `0x084ea7fc00eb33772d5d307eac911122ecd1d6a0` |
| B3TRGovernor（治理合约） | `0x6a09fc5543d928e0275abd41f6a360ac83ed5909` |
| TimeLock（时间锁） | `0x9946f8c4a406d0905e241b5fef320c21f54d785c` |
| Treasury（金库） | `0x6b01c9a79923af77c35c642e6b8cd2ef5ec55edc` |
| Emissions（排放） | `0xd8f0f7743b216674e629961f1dbe16246af5f41b` |
| VoterRewards（投票奖励） | `0x1416310f7e786235132bdf4bb747194ddb315992` |
| XAllocationVoting（分配投票） | `0x9819ba0daaeae450e3d0f5cd4e3adafb2475a632` |
| XAllocationPool（分配池） | `0xe4a8605bea17d4055176bb1b81780f439d1cf61e` |
| GalaxyMember（银河会员） | `0xec2b29a9fd44a862ae6353a016063ce1ef60dc67` |
| X2EarnApps（X2Earn 应用） | `0xd0d5dc52b273dbed064dd61d980c92462865f5dc` |
| X2EarnCreator（X2Earn 创建者） | `0xb5143c8ffabb84f37a57e803e5208d0af1790685` |
| X2EarnRewardsPool（X2Earn 奖励池） | `0x1e34e053acf813f3eb1cdb2e3e8e255e62e8d5c0` |
| VeBetterPassport（护照） | `0xa3c381a9a0a8159d04db811c36a9c454b9c597ba` |
| NavigatorRegistry（导航者注册） | `0x23baca5c9a424be81aec36c1dbb641f2296bd1a4` |
| NodeManagement（节点管理） | `0x2b3747265b8b015d77353674f34ab11277eafcd8` |
| B3TRChallenges（B3TR 挑战） | `0xf0f84a5ebb0610651403ab93f8e4b00dfe351c26` |
| GrantsManager（拨款管理） | `0x900daffabd5b41f96cdeb722510015592a36ac65` |
| RelayerRewardsPool（中继奖励池） | `0xf135f1525e50ba727f625d42d4a9f15a620993d6` |
| DBAPool（DBA 池） | `0x712dd1158a4ff91efd53a83d7be6228fb7e8e91b` |
| Stargate（星门） | `0x62d3121e813f01f9be2e0a8d7db6ac0dd1178ea8` |
| StargateNFT（星门 NFT） | `0x5a42b7f5505bdc0cb0f2ced102aacb360b1743d7` |
| TokenAuction（代币拍卖 - 模拟） | `0x52b04ad7c42f947134c7d32653843919b5a7b7ad` |
| ClockAuction（时钟拍卖 - 模拟） | `0xfb7a9af3c98c7a6568de36b30289ca4917ea4a65` |
| ProtocolStakerMock（协议质押模拟） | `0xe8c5e71d9affa60ceb1854c2c4f4c02043227ddf` |
| B3TRMultiSig（B3TR 多签） | `0x0000000000000000000000000000000000000000`（未部署） |

---

## 实现合约地址

### B3TR
- 实现合约: `0x19b0c91df993f6feca360e7b24a0aa2c1e5da0f5`

### VOT3 (V1→最新)
- V1 实现: `0x0466f9385c6ef678a3a9e390074ed7b12136e19d`, 代理: `0x2e2da12910243d6946574d16f314aa09837a9957`
- 最新实现: `0x12cf59567c8df2038b2d95aac0ddd1f519502c5b`

### B3TRGovernor (V1→V9→最新)
- V1 实现: `0xfd46d967638c70736123e1f70d6f2d5aef90c487`, 代理: `0x6a09fc5543d928e0275abd41f6a360ac83ed5909`
- V9 实现: `0x3105adcabe2c7f57b9d23daea049d2d32b146062`
- 最新实现: `0x7a55e1397056504b58bf279981c433fa80099cf4`

### TimeLock（时间锁）
- 实现: `0x1908ee0f26f33f04b3b1bc218018f907130ea78d`, 代理: `0xcceac9cafafc371a1577ad49993446be07fc34aa`

### Treasury（金库）
- 实现: `0xa569d31ff8155dca5c73fee285cf5b46c8636c4e`, 代理: `0x60bf97a7285691a06859d007947e7eb07e16321d`

### Emissions（排放，V1→V2→最新）
- V1 实现: `0x36f2563cdd9feb8e57d88028cc2a9f474d97e99f`, 代理: `0x5ab441777897d99e128ee2a4be848963b41a208d`
- 最新实现: `0x9c48d89344646a4a043b6be5afb3f2e5f5716c2a`

### VoterRewards（投票奖励，V1→V5→最新）
- V1 实现: `0x5c40a01331fd5b6aa31a673c669f88883c0a6e00`, 代理: `0x559f28ac5988cf58a7dba4258434e12d78cbd29e`
- 最新实现: `0x52e1f2405fc4132d652b42f62154c9628756ac4a`

### XAllocationVoting（分配投票，V1→V8→最新）
- V1 实现: `0x37c03534f4785f05beada6be2204c530944bd7a5`, 代理: `0x9819ba0daaeae450e3d0f5cd4e3adafb2475a632`
- 最新实现: `0xab7e0213f1ec2138107f38c660211aef515c3b5d`

### XAllocationPool（分配池，V1→V6→最新）
- V1 实现: `0x5273d8d51a3ef843b36d6f06238a161de67d0ff0`, 代理: `0xe4a8605bea17d4055176bb1b81780f439d1cf61e`
- 最新实现: `0x2e00145a910c94ba8a7afc388cd27fb0f292ca98`

### GalaxyMember（银河会员，V1→V5→最新）
- V1 实现: `0x67006a4a0a8034c8a58241c4e7c1a257fc2e0ecd`, 代理: `0xec2b29a9fd44a862ae6353a016063ce1ef60dc67`
- 最新实现: `0x82f5f67fb878f68402232ad8f176d84d9440fe0a`

### X2EarnApps（X2Earn 应用，V1→V7→最新）
- V1 实现: `0xb68f0e59f4a6416e2cdd42c3d61b11b7d6a30117`, 代理: `0x6dce950e19708fcef65a9189a6549850d5013252`
- 最新实现: `0x67313e7a70f91b8a0c4f5e45b1e9f23535883150`

### X2EarnCreator（X2Earn 创建者，V1→最新）
- V1 实现: `0x832573ab46394cb6b762bde48579669f1be6aac1`, 代理: `0xee5e80ce8172b06c94c78604ab731cb5ffcda199`
- 最新实现: `0x8ff05abb12d54aae441800ee7cc6cb1264ef0037`

### X2EarnRewardsPool（X2Earn 奖励池，V1→V5→最新）
- V1 代理: `0x1e34e053acf813f3eb1cdb2e3e8e255e62e8d5c0`
- 最新实现: `0xd254e4ae99ce67c0877625873ff02f55b8fda7c8`

### VeBetterPassport（护照，V2→V4→最新）
- V2 实现: `0x2786f757cd793c699912d93fdb8be4d2af0f1c45`
- 最新实现: `0x941cd45e6567ae9a639aa8facadc9802a1355e1b`

### NavigatorRegistry（导航者注册）
- 代理: `0x23baca5c9a424be81aec36c1dbb641f2296bd1a4`

### NodeManagement（节点管理，V1→V3）
- V1 实现: `0xa2ab3a2e771673d394ccd5b50a7d2c4365cdbd1b`, 代理: `0x2d048e0a013707a2b3beb08412797539c0e410ae`
- V3 实现: `0xe0bf176b3b157c538d5beb0f71198a6f410ae964`

### B3TRChallenges（B3TR 挑战）
- 实现: `0xc165b8f66e52883c473ccc23470ad8a9b95077bb`, 代理: `0xf0f84a5ebb0610651403ab93f8e4b00dfe351c26`

### DBAPool（DBA 池，V1→V3）
- V2 实现: `0x02f315b07fc58ce30d6654127852f8e653a14397`
- 最新实现: `0xbb25ef5a0c19261f6a4076d79c25215123bccaff`

### RelayerRewardsPool（中继奖励池，V1→V2→最新）
- V1 代理: `0xf135f1525e50ba727f625d42d4a9f15a620993d6`
- 最新实现: `0x1b75a2ab7723c6c9f50adc7673eb6c1e1f3db0c0`

### Stargate（星门）
- 代理: `0x62d3121e813f01f9be2e0a8d7db6ac0dd1178ea8`

### StargateNFT（星门 NFT）
- 代理: `0x5a42b7f5505bdc0cb0f2ced102aacb360b1743d7`

---

## 库合约地址

### B3TRGovernor（治理）库

| 库 | 地址 |
|---|---|
| GovernorClockLogic | `0x7e6f3bbac18fc82ffc683cea69e62a38014df39a` |
| GovernorConfigurator | `0x5dd4f6628ff175486739494ca3944ce02ea99d6b` |
| GovernorDepositLogic | `0x1f241fca28ceab5e7548430e3c89ca30e0569d1a` |
| GovernorFunctionRestrictionsLogic | `0x73b86bdcb8f852919a97f8ec323c4dbf53010676` |
| GovernorProposalLogic | `0x3c35f56fbe0549d0d02aae58e739ba83d87ebd7e` |
| GovernorQuorumLogic | `0x3f0dd4c00e35e6d295fd6bc55f218dd1b1a0f286` |
| GovernorStateLogic | `0x5ac6b107be5a6f7b1369efd2b75bc5c21854ca44` |
| GovernorVotesLogic | `0xddf8d06b1d4731707816447e163fd8feb5156c4b` |

### VeBetterPassport（护照）库

| 库 | 地址 |
|---|---|
| PassportChecksLogic | `0x1eca2b1104918d22c83729ae788f2dfad9596db9` |
| PassportConfigurator | `0xc8a504d878f422c88f1fb7521ed251694a06106e` |
| PassportEntityLogic | `0x49d44c724ff5767e2a2e33e80e66a1a2f7d89165` |
| PassportDelegationLogic | `0x5f3c8882f64460e4531c1f261faca9a8e056739d` |
| PassportPersonhoodLogic | `0x57a637beef561c8f1debb78991807a1a36eedfa3` |
| PassportPoPScoreLogic | `0x0cb75c58299658c16d68e2f7093c90369a1f2d50` |
| PassportSignalingLogic | `0x4088b6a4bbb69732e1c8ccfecd0c5ac594f95f9a` |
| PassportWhitelistAndBlacklistLogic | `0xd36d182878a120dc7fba529a61658ae073d435ea` |

### X2EarnApps（X2Earn 应用）库

| 库 | 地址 |
|---|---|
| AdministrationUtils | `0xc68137bd6335aa0b4da82a655e3bd29b9a579804` |
| EndorsementUtils | `0xa1885e5d154c4e11910ddbdcb7f50e6716c5e7f6` |
| VoteEligibilityUtils | `0xdb06b6d6deb558c5b3bc7e117d931efd20885613` |
| AppStorageUtils | `0x3846ab640047cd5b36b3b68c7e39557ac1153f8c` |

### XAllocationVoting（分配投票）库

| 库 | 地址 |
|---|---|
| AutoVotingLogic | `0xaa65b52f177e0b18c4a8b5429b517c533038b1d2` |
| ExternalContractsUtils | `0x479acc9176cfe44550f0ec524b7a93fc337376ce` |
| VotingSettingsUtils | `0x71d5dfe9bd2a0fe516344fe583b5d6bc78cbfbe6` |
| VotesUtils | `0xdb4f82f688bef9767626af774791983d5af0acd9` |
| VotesQuorumFractionUtils | `0x51a9fbc0f6bc4da4a38da88ad3edfeee022f6a12` |
| RoundEarningsSettingsUtils | `0xd046790ee7312b7975dc63e6305fc5eb5460b47c` |
| RoundFinalizationUtils | `0x5c6b17e77708c64b8a46a5302a1ed6febbf59aa7` |
| RoundsStorageUtils | `0x2b610bd806093a3d62afa0299e5266856c8183b2` |
| RoundVotesCountingUtils | `0x4a10d8ddf06f798864406a2e20c40bee5ed038ad` |

### NavigatorRegistry（导航者注册）库

| 库 | 地址 |
|---|---|
| NavigatorStakingUtils | `0x6bc756ef35f297b62e3f99c929417c633be7524e` |
| NavigatorDelegationUtils | `0x9aa46f4f965e2a81831c3c6b03c2b2ed0a043246` |
| NavigatorVotingUtils | `0xef8a208550e34bfc1e694732b5c04d5f7d1a3c56` |
| NavigatorFeeUtils | `0x4031b68ee38bed4d1b9cd1bbc7aac9f836db7f53` |
| NavigatorSlashingUtils | `0x4600f8fdaca7541b98824dbd010f21bf85fe8008` |
| NavigatorLifecycleUtils | `0xf10824ff1d5ddf1703b0689bf59e402c1f8e2bdf` |

### B3TRChallenges（B3TR 挑战）库

| 库 | 地址 |
|---|---|
| ChallengeCoreLogic | `0x11aecdd177da415b2da8f6bb98b6321d9148f76e` |
| ChallengeSettlementLogic | `0x2a6297eac0bdc1ba97f7e84cd3339c65cb3157cf` |

---

## 已背书的 XApp

所有应用均以 1 分背书（阈值: 1）。10 个背书者可用，共 30 个节点。

| XApp | 背书节点 |
|---|---|
| Mugshot | #1001 (`0xf077b4...`) |
| Cleanify | #1004 (`0x435933...`) |
| GreenCart | #1007 (`0x0F8724...`) |
| Green Ambassador Challenge | #1010 (`0xF37094...`) |
| Oily | #1013 (`0x99602e...`) |
| EVearn | #1016 (`0x61E7d0...`) |
| Vyvo | #1019 (`0x361277...`) |
| Non Fungible Book Club (NFBC) | #1022 (`0xD7f75A...`) |
| TestApp | #1001 (`0xf077b4...`) |
| TestApp1 | `0xcb28e576...` |
| TestApp2 | `0x602b45b4...` |
| TestApp3 | `0x5a05bbb2...` |
| TestApp4 | `0x053d143d...` |
| TestApp5 | `0x142063c7...` |
| TestApp6 | `0x37e2baf2...` |
| TestApp7 | `0xd9ca8c9a...` |
| TestApp8 | `0x4de2cee9...` |
| TestApp9 | `0xae543b8a...` |
| TestApp10 | `0x603c3eca...` |
| TestApp11 | `0xb0d9e765...` |
| TestApp12 | `0xefcacdce...` |
| TestApp13 | `0xe933bae7...` |
| TestApp14 | `0x5de54cdb...` |
| TestApp15 | `0x8c677a05...` |
| TestApp16 | `0xf6d96582...` |
| TestApp17 | `0xeaf26c57...` |
| TestApp18 | `0xfd8e2583...` |
| TestApp19 | `0x1b15f94b...` |
| TestApp20 | `0xc45ee865...` |

---

## 部署后配置

- 排放（Emissions）已引导启动
- B3TR 已空投到各账户
- VOT3 已铸造（通过 B3TR 转换）
- GM NFT 最高等级设为 5
- X2Earn 节点冷却期: 1 轮
- 背书分数阈值: 1 分
- 合约角色已配置（铸造者、提议者、执行者、治理等）
- B3TRGovernor 白名单函数已设置
- GrantsManager（拨款管理）已配置
