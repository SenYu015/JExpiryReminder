# Changelog

## 1.27.25 (2026-11-17)

### 新增

* **循环任务**：物品可标记为循环使用（定期充电 / 清理 / 添置等），设置周期后按「上次完成日 + 周期」
  推导本周期到期日；首页列表提供「完成本次」，按下即以实际完成日重新计时，并重置该周期提醒记录；
  通知、流体云与小组件区分循环任务文案。旧数据缺字段按普通物品读取（data.json v12）

### 修复

* **有效期按含首尾计算**：到期日由「起始日 + 周期」改为「起始日 + 周期 − 1 天」，
  11-10 生产、1 个月有效期现在到期日为 12-09；OCR 反推有效期同步修正；历史到期日不回改

## 1.27.20 (2026-11-17)

### 安全

* **自动同步要求先配对**：新增长期信任表（每台设备一把 32 字节密钥，配对时由接收方生成）；
  未配对设备发来的同步、取图请求一律拒绝且不回任何数据；设置页可查看并解除信任
* **同步认证与防重放**：每条请求带 `HMAC-SHA256(secret, cmd|from|nonce|ts)`，
  服务端校验签名、120 秒时间窗与 nonce 去重，响应同样签名，避免被冒名顶替
* **载荷加密**：自动同步的物品、分类/空间、图片索引走 AES-256-GCM；
  图片按文件单独加密后再出网卡；手动分享与空间同步的 zip 走 AES-256-CTR + HMAC-SHA256 流式加解密
* **解包上限**：备份导入与传输包解包增加单条、累计、条目数三重上限，堵住 zip 炸弹导致的 OOM 闪退
* **系统备份排除**：`allowBackup=false` + `dataExtractionRules`，云备份与换机直传不再带走物品与同步密钥
* **数据落盘原子化**：`data.json` 改为「临时文件 + fsync + rename」，覆盖前保留 `.bak`，解析失败时自动回退
* **锁屏隐藏详情**（设置 → 通知与提醒）：开启后锁屏与流体云只显示「到期提醒 / N 件物品即将到期」
* **配对加固**：配对码改用 `SecureRandom`；同一 IP 每分钟最多 3 次配对尝试；服务端并发连接数量封顶

### 变更

* 边缘返回手势条 24dp → 32dp，滑动更容易触发
* 备份 manifest 的 `appVersion` 改为读取构建版本号（此前恒为 1.0.0）
* 移除已废弃的 `predictiveBackEnabled` 设置字段与读写逻辑

## 1.27.1 – 1.27.19 (2026-11-16)

该区间为快速迭代，未逐版记录，主要变更：

* **返回动效**：按钮返回改为整页滑出；手势探索后定稿为应用自驱动的左边缘拖拽
  （ColorOS 17 的 `onBackProgressed` 恒为 0，系统侧跟手不可用）
* **图片查看器**：由缩略图位置展开、无黑边浮层、末段交叉淡化为方形缩略图、纵向跟手关闭
* **首页**：固定块 + 渐变遮罩、磨砂控件统一（`frostedPanel`）、底部三钮等高浮起、列表色条固定尺寸
* **设置页**：去掉折叠分组改为平铺、合并权限与后台保障入口、移除无用项
* **流体云**：恢复 `FLAG_PROMOTED_ONGOING`（去掉自定义 RemoteViews）、胶囊收窄、进度条改单段连续色
* **小组件**：字号整体上调、新增相机键、主题联动
* **桌面快捷方式**：长按图标直达「新增物品 / 拍照识别」
* **编辑页**：三卡合并为单卡、控件高度压缩、悬浮保存按钮，主流机型一屏放下
* **移除语音输入**：删除 Vosk 离线模型（约 66MB）、麦克风入口与 `RECORD_AUDIO` 权限

## 1.27.0 (2026-11-16)


### 变更

* **语音:** 移除语音输入功能——删除麦克风入口（新增页标题栏快速录入、名称与备注输入框）、
  应用内语音弹窗、Vosk 识别引擎与「一句话拆名称/到期日/位置」解析
* **体积:** 不再打包 Vosk 中文离线模型（`assets/model-cn`，约 66MB）与 `vosk-android` 依赖，
  APK 由约 87MB 回落至约 29MB；升级时自动清理旧版本解压到 `files/vosk-cn` 的模型目录
* **权限:** 移除 `RECORD_AUDIO` 与语音相关的 `<queries>`，应用不再采集音频
* **设置:** 删除「语音」分区与「语音识别」条目

## [1.12.0](https://github.com/nickynull/JExpiryReminder/compare/v1.11.0...v1.12.0) (2026-11-16)


### Features

* **提醒:** 分类可配置新增时的默认提前提醒天数 ([c1cb1c3](https://github.com/nickynull/JExpiryReminder/commit/c1cb1c344d21194c2c060ad01168bccfa648cbf5))
* **空间:** 删除空间不再要求空间为空，物品自动转为「未分配」 ([e90e3f3](https://github.com/nickynull/JExpiryReminder/commit/e90e3f323296089f203b444f8d2f53f4420e48b0))
* **编辑:** 新增物品时提示同名/相似物品，可直接跳去编辑已有条目 ([50daed6](https://github.com/nickynull/JExpiryReminder/commit/50daed6c828d67a2779dcd74786e067ac800cbb6))

## [1.11.0](https://github.com/nickynull/JExpiryReminder/compare/v1.10.0...v1.11.0) (2026-11-15)


### Features

* 新增物品时自动带入主页当前筛选的空间 ([95acfe5](https://github.com/nickynull/JExpiryReminder/commit/95acfe51fb635ba0d455c626caadfea0af7ef150))

## [1.10.0](https://github.com/nickynull/JExpiryReminder/compare/v1.9.2...v1.10.0) (2026-11-15)


### Features

* 主页卡片图文重新排版——数量贴名称、缩略图与张数并排到右列 ([835adf9](https://github.com/nickynull/JExpiryReminder/commit/835adf943c10ce60295cd5d330cdf0dd58311d9d))

## [1.9.2](https://github.com/nickynull/JExpiryReminder/compare/v1.9.1...v1.9.2) (2026-11-15)


### Bug Fixes

* 跨设备分享搜不到设备时不再静默失败 ([f1f7539](https://github.com/nickynull/JExpiryReminder/commit/f1f75394666fba218ee00cc152fc0a492b43877b))

## [1.9.1](https://github.com/nickynull/JExpiryReminder/compare/v1.9.0...v1.9.1) (2026-11-15)


### Bug Fixes

* 从编辑页返回主页保留列表滚动位置 ([75ef308](https://github.com/nickynull/JExpiryReminder/commit/75ef308cbc8f65a87d34fb603c0fbac4ffd48359))

## [1.9.0](https://github.com/nickynull/JExpiryReminder/compare/v1.8.0...v1.9.0) (2026-11-14)


### Features

* 时刻与日期选择改为滚轮 + 输入框 ([f7e2c8d](https://github.com/nickynull/JExpiryReminder/commit/f7e2c8da6cf617789a31356bba98950435f760a2))
* 编辑页布局对齐优化——空间/分类、到期日期/数量同行各半，移除有效期灰底分组 ([fd4f1c3](https://github.com/nickynull/JExpiryReminder/commit/fd4f1c36e67fbd8a190b8b64b9b0ad48354205ef))

## [1.8.0](https://github.com/nickynull/JExpiryReminder/compare/v1.7.0...v1.8.0) (2026-11-14)


### Features

* 主页统计卡支持自定义显示 ([f2feca5](https://github.com/nickynull/JExpiryReminder/commit/f2feca556c43c8ad87c5191989b010076eb061a9))


### Bug Fixes

* 修复退出应用后提醒通知不弹 ([67bf5b3](https://github.com/nickynull/JExpiryReminder/commit/67bf5b3e06b8175fb4e78c41c4a9ad3994b102b9))

## [1.7.0](https://github.com/nickynull/JExpiryReminder/compare/v1.6.0...v1.7.0) (2026-11-13)


### Features

* 去掉内置默认空间，物品默认无空间归属 ([db1ebb5](https://github.com/nickynull/JExpiryReminder/commit/db1ebb5249cebc738ecfcf478c0b6b58eda90761))

## [1.6.0](https://github.com/nickynull/JExpiryReminder/compare/v1.5.0...v1.6.0) (2026-11-12)


### Features

* 空间归属、跨设备分享与空间同步 ([5346442](https://github.com/nickynull/JExpiryReminder/commit/5346442d789170c19ef54a7265ac80cf28c81bbf))

## [1.5.0](https://github.com/nickynull/JExpiryReminder/compare/v1.4.0...v1.5.0) (2026-11-12)


### Features

* 首页搜索移到底栏、输入框统一、提前提醒天数可自定义 ([1389e70](https://github.com/nickynull/JExpiryReminder/commit/1389e7018cab3f96f0122ecb6169cad29fae891d))

## [1.4.0](https://github.com/nickynull/JExpiryReminder/compare/v1.3.0...v1.4.0) (2026-11-12)


### Features

* 拍照识别录入（离线中文 OCR 预填名称与日期） ([9694129](https://github.com/nickynull/JExpiryReminder/commit/9694129d5e8c9caa520b9cba9eddb682dc235f58))

## [1.3.0](https://github.com/nickynull/JExpiryReminder/compare/v1.2.29...v1.3.0) (2026-11-12)


### Features

* 图片查看器（全屏翻页）+ 列表缩略图 + 图片加载失败占位 ([dd78aa3](https://github.com/nickynull/JExpiryReminder/commit/dd78aa321653b77ce9d76faa11214f0534729eab))
