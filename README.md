# JExpiryReminder（到期提醒 · Android 原生）

## 文档与截图隐私

- 本仓库的 README、界面分析文档及任何对外材料，**不嵌入真机截图，也不引用本地截图文件**；界面图均为按源码文案与色值还原的脱敏示意。
- 如确需使用截图，必须先对**壁纸、照片、状态栏通知、个人数据等背景信息**做裁切、模糊或替换，仅保留脱敏后的界面内容；未经处理不得写入文档或提交。
- 调试过程中产生的 `device-*.png`、`verify-*.png` 等临时截图仅用于本地核对，交付前必须清理。

专注「到期提醒」的独立 Android 应用，**Kotlin + Jetpack Compose** 原生实现。

## 构建

```bash
# 环境变量（本机已具备）：JAVA_HOME=.jdks/microsoft-jdk-17，ANDROID_HOME=AppData/Local/Android/Sdk
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk（约 33MB）
./gradlew testDebugUnitTest    # 99 个单元测试
./gradlew installDebug         # 连接设备安装
```

Gradle 走腾讯镜像、Maven 走阿里云镜像，无需代理。

## 功能

- **图像识别录入**：首页「添加」旁的相机按钮 → 拍照 / 相册选图（最多 6 张）→ 确定后本地识别
  → 带着预填结果进添加页；识别名称、生产日期、保质期、「有效期至」，多个候选由用户点选；
  **全程离线**（ML Kit bundled 中文模型 + 已移除库带入的网络权限），照片不离开设备
- **物品管理**：名称 / 空间 / 分类 / 生产日期 / 有效期（天·月·年，月末自动收敛）/ 到期日
  （可由生产日期+有效期推算，也可手动指定）/ 数量 / 备注；
  有效期按含首尾计算（11-10 生产、1 个月效期 → 12-09 到期）；
  支持**循环任务**（定期充电 / 清理 / 添置等）：设置周期后按「上次完成日 + 周期」推导到期日，
  首页点「完成本次」即从完成日重新计时，新周期提醒独立触发；
  新增时默认「食品」，若主页正按某分类筛选则自动预选该分类
- **空间归属**：物品归入某个空间（我的空间 / 爸妈家 / 公司…），也可以不归属任何空间（「未分配」，新增时默认如此）；
  顶部胶囊切换空间，默认「全部空间」（所有空间 + 未分配一起看），列表、统计卡、分类计数都跟着空间走；
  空间可改名、换色、删除（删除空间不会删除物品，空间里的物品会变成「未分配」）；
  **「全部空间」「我的空间」「未分配」的颜色都可改**，记在 data.json 的 `spaceColors` 里
- **提醒档位**：提前 60/30/15/7/3/1/0 天，可选多档；另可自定义任意 1~365 天
- **列表**：四档统计卡（已过期/7 天内/30 天内/正常）、搜索、分类筛选（进出编辑页后保留，
  下拉里每项显示该分类记录数）、按状态分组、侧滑删除（带确认）；
  长按进入多选后可批量改**空间**、批量改**分类**、批量分享、批量删除（带确认），也可一键全选
- **分类管理**：内置 5 分类 + 自定义分类，12 色取色板换色（内置分类颜色也可改）；
  分类下还有物品时不允许删除（删除按钮置灰，点击提示原因），删除空分类需二次确认
- **图片**：拍照 / 相册选图，自动等比压缩（最长边 1280，JPEG 85），每件最多 6 张
- **通知**：AlarmManager 精确闹钟（`setExactAndAllowWhileIdle`），关掉应用也会推送；
  开机广播自动重排；精确闹钟权限未授予时自动降级为非精确闹钟 + 前台检查兜底；
  去重键 `<fireDate>:<itemId>:<days>`，FNV-1a 派生稳定通知 id
- **隐私开关**（v1.27.20）：设置 → 通知与提醒 →「锁屏隐藏详情」，开启后锁屏与流体云只显示
  「到期提醒 / N 件物品即将到期」，物品名只在解锁后可见
- **备份**：SAF 导出/导入 zip（data.json + images/ + manifest.json），
  支持「覆盖」与「按 id 合并」两种模式
- **跨设备分享**：同一 Wi-Fi 下用 mDNS 自动发现另一台设备 → 发起方生成 4 位配对码，
  对方比对屏幕数字后接受 → 传输选中的物品（含图片），也可在空间下拉里「按空间分享」整空间发送；
  接收方先收到待接收批次，自己选择落到哪个空间（也可现场新建空间，默认「未分配」）后才真正入库；
  **入库前会检查同名物品**，由用户选择「直接新增 / 跳过同名 / 覆盖同名 / 人工逐条挑选」。
  全程局域网 TCP 直连，不经过任何服务器；发送的是副本，本机记录不受影响。
  配对成功后双方各自保存一把长期密钥，手动传输与自动同步的载荷都基于它加密；
  长期密钥不会明文过网：配对握手走 ECDH(P-256)，用配对码 + 双方临时公钥派生密钥，
  响应以 AES-256-GCM 密文回传（见下「安全设计」）；
  设置页「已配对设备」里可随时解除信任。自动发现被路由器挡住时可手动填对方 IP。
- **空间同步**：两台设备协商把同一个空间合并成一份（双向）。发起方选本机空间 → 配对 →
  上传快照；接收方选择自己的哪个空间参与合并 → 本机先合并 → 把合并后的快照回传 → 发起方再合并。
  匹配规则：名称（去空格、忽略大小写）+ 到期日相同视为同一件，同一件以 `updatedAt` 较新的为准；
  只增改、不删除。同样走局域网 TCP，不经过服务器
- **自动同步**（设置页开关，默认关闭）：开启后应用进程常驻 mDNS 广播/发现，每 20 秒与同一 Wi-Fi 下的同伴
  交换一次增量；进程被回收时由 30 分钟一次的 AlarmManager 闹钟兜底唤醒。
  独立服务类型 `_jexpiry-auto._tcp.`，不经过服务器，**不改动现有手动分享链路**。
  请求带 `HMAC-SHA256` 认证与 nonce 防重放，载荷（物品/分类/空间/图片索引）走 AES-256-GCM 加密；
  **只与已配对设备同步**——未配对的设备既不读也写不进本机，需先在「跨设备分享」里配对一次。
- **共同唯一 ID + 修改时间新者胜**（v1.24.0）：每条物品持久化 `syncId`，创建时生成、随编辑与同步传递、
  永不随改名/改日期变化；旧数据缺字段时由本地 id 稳定派生补发。
  - 认同一条记录的顺序：`syncId` → 本地 `id` → 稳定业务键（归一化名称 + 到期日 + 数量）；
    最后一层用于两台设备离线各自录入同一件物品，命中后双方把 `syncId` 收敛为同一个值。
  - 判定新旧以 **`updatedAt`（统一按 UTC 解析）** 为准，新者覆盖旧者；时间相同才用 `rev`、`createdAt`、
    内容指纹做确定性破平。`rev` 只用于增量水位，不再决定业务胜负。
  - 本机较新时抬升 `rev` 但不改 `updatedAt`，下一轮把本机版本回传给对方完成收敛。
  - **不再用名称相似度制造人工冲突**：名称相似但到期日不同就是两件不同物品，直接各自保留；
    升级到 v1.24.0 时会把旧规则留下的「疑似重复」按新规则重算并自动收敛、清空；
    设置页的「待合并冲突」与智能合并仅保留给历史记录，正常同步不会产生新冲突。
  - **自动同步会补齐图片**：文本合并后按「本地缺哪些文件」向对端拉取（协议命令 `imgs`），
    新物品保留图片引用，历史上图片为空的物品借 `imageIndex` 自动修复。单轮最多 80 张 / 40MB。
- **增量同步**：`items[].rev` + 顶层 `revision` 组成 Lamport 水位，只交换「对方未确认收到」的增量；
  两边都没改动时是一次空握手，不写库、不通知。
- **桌面小组件**（v1.23.0）：顶部大字「N **到期列表**」+ 橙色圆形「+」（点开新增页），
  每条行是浅色圆角卡片：左侧 **分类色条与软件内列表卡片完全一致**（食品橙/药品红/证件蓝…，
  含用户改色与自定义分类）、名称、**所在空间 + 添加日期**，右侧两行显示**剩余天数 + 到期日期**。
  它不是待办清单，因此**不设勾选框**。列表由启动器原生上下滑动，点条目进编辑页。
  **卡片、文字、行底色完全跟随应用浅色 / 深色主题**（由「外观与交互」统一控制），
  不再提供独立背景透明度与自定义背景图；切换主题时自动刷新桌面卡片
- **语音输入已移除**：不再内置 Vosk 中文模型，也没有麦克风入口。
  `RECORD_AUDIO` 权限、`queries`（`RecognitionService` / `RECOGNIZE_SPEECH`）、
  `com.alphacephei:vosk-android` 依赖与 `assets/model-cn` 一并删除，APK 体积回落约 66MB；
  录入只保留键盘输入与离线 OCR 预填
- **新增页布局**（v1.27.18 起单卡紧凑）：三张分组卡片合并为一张 18dp 圆角单卡，
  控件高度压到 44dp、chip 26dp、缩略图 64dp，主流机型一屏放下；
  保存按钮改为主页同款磨砂胶囊浮在内容之上，`imePadding` 保证键盘弹出时仍可见
- **设置页五个平铺分区**（v1.27.x）：通知与提醒 / 外观与显示 / 跨设备同步 / 数据与备份 / 关于；
  不再折叠，分区标题配分割线，精确闹钟、忽略电池优化与自启动说明合并进「后台运行保障」
- **交互**：主页按返回键直接回桌面（不再要求按两次）；图片查看器点图片/背景任意位置缩小淡出后最小化，
  双击与双指缩放保留
- **离线 OCR 增强**：识别进度页显示读到的行数；添加页可展开「识别原文」，点一行补进备注，
  识别不准时提供人工兜底，全程仍是 ML Kit bundled 中文模型
- **分类与空间也参与同步**（v1.15.0）：每轮同步附带分类/空间及其颜色、改名覆盖、顺序的快照；
  归并规则：同 id 保留本机、同名不同 id 视为同一项并建立映射、本机没有的自动新增，
  远端物品上的 `category` / `spaceId` 在入库前改成本机 id，避免「同步过来变成未分类/未分配」。
  颜色与改名**本机优先**，只补远端有而本机没有的键，不会来回覆盖
- **页面动效**（v1.26.0 收敛）：进二级页仍保留短距离横向层级感；**保存 / 删除成功后不再模拟
  返回手势的缩放+侧滑**，改为平台式淡入淡出（单 Activity 内没有可直接调用的 Activity 转场动画），
  避免「保存像被拖回去」的生硬感。手势返回仍走预测性返回；主页列表增删/移动保留
  `Modifier.animateItem()` 位移动画
- **返回手势**（v1.27.8 起）：ColorOS 17 实测不分发预测性返回进度（`onBackProgressed` 恒为 0），
  改为应用自驱动的左边缘拖拽——32dp 手势条内页面 1:1 右移、底层主页可见并随进度压暗，
  松手按位移（>33%）或速度（>1000px/s）提交或回弹；左上角返回与三键导航共用整页滑出转场，
  主页不拦截返回（直接回桌面走系统动画）
- **主页视觉**（v1.26.0）：参考通知中心样式，圆角层级 18/24/28dp、浅灰底 + 纯白卡片；
  标题栏圆形按钮、底部悬浮胶囊工具条与独立绿色圆形新增按钮；不再做假的「凝光」描边或彩色角落
- **流体云 / 实时动态**（v1.26.0 修复）：7 天内到期物品合成一条 promoted ongoing 通知。
  真机排查发现旧版用 `CATEGORY_REMINDER` + 默认 `VISIBILITY_PRIVATE` 时，系统只记录
  `requestPromotedOngoing=true`，不给 `FLAG_PROMOTED_ONGOING`；改为 `CATEGORY_STATUS` +
  `VISIBILITY_PUBLIC` + 非彩色化后，真机已出现 `PROMOTED_ONGOING` 标记。设置页新增
  「流体云 / 实时动态诊断」，显示系统是否允许发布、是否已请求提升 / 已提升，并提供
  「去开启」到系统实时活动开关与「重发」。自定义模板/多端卡片才需要 OPPO 泛在服务白名单
- **开机自启动**：开关打开时，开机后恢复 30 分钟一次的局域网自动同步；设置页同步状态显示「已配对 N 台」
- **通知提速**：一次排程窗口 60 → 200 条；当天已过推送时刻但还没提醒的档位改在 5 秒后补发；
  48 小时内的提醒额外排一条 +90 秒备份闹钟（去重键防重复）；补排等待 3 分钟 → 45 秒

## 数据格式

- data.json **v12**：`items` / `categories` / `categoryColors` / `categoryLabels` / `categoryOrder` /
  `spaces` / `spaceColors` / `revision`（`DATA_VERSION = 12`）
- v11 → v12：物品增加 `items[].repeatEnabled`（循环任务标记，缺省 false）；循环物品复用
  `productionDate` 作上次完成日、`shelfLife` + `shelfLifeUnit` 作周期，到期日始终按「起始日 + 周期 − 1 天」推导；
  旧数据缺字段按普通物品读取，不回改历史到期日
- v9 → v10：物品增加 `items[].syncId`（共同唯一 ID）；旧数据缺字段按空读取，首次落盘由
  `id` 派生 `"s_" + id` 补发，不需要人工迁移；两台设备离线各自录入的同一物品在同步时收敛到同一个 syncId
- v8 → v9：物品增加 `items[].rev`（增量水位）、顶层增加 `revision`（本机逻辑时钟）；
  旧数据缺字段按 0 读取，首次落盘补发，不需要人工迁移
- v5 → v6：`items[].spaceId` 缺字段时归内置默认空间（`default`）；`spaceId` 为空串表示「不归属空间」
- v6 → v7：空间颜色覆盖表 `spaceColors`，键是空间 id、「未分配」用 `__unassigned__`、「全部空间」用 `__all__`；
  缺字段时各用内置默认色
- v7 → v8：去掉内置默认空间，未维护的空间读取时视为未分配（不改写历史数据）
- 图片存相对路径 `images/<id>.jpg`，随备份打包
- v3 的内联 base64 图片加载时自动迁移成文件
- 设置在 SharedPreferences `settings` 的单个 JSON 里，**v5** 起新增 `autoSyncEnabled`（缺省 false）；
  **v6** 新增 `liveUpdateEnabled` / `autoSyncOnBoot`（缺省均为 true）；
  **v7** 新增 `hideNotificationDetail`（缺省 false），旧版的 `predictiveBackEnabled` 已废弃、不再读取
- 待合并冲突单独存 `auto_sync_conflicts.json`；每台设备的同步水位与「已同步 id」存
  `auto_sync_state.json`（`recvRev` / `sentRev`），都不进 data.json、不改数据版本

## 数据目录

`<内部存储>/files/JExpiryReminder/modules/expiry/`
（`data.json` + `images/` + `auto_sync_conflicts.json` + `auto_sync_state.json`）。
跨设备传输的临时包在 `cache/transfer/`（out 发送、in 接收），收完或离开页面即清理。

## 结构

```
app/src/main/java/com/jimmy/expiryreminder/
├── App.kt / MainActivity.kt      # 入口、导航（Home/Edit/Settings/Categories/Spaces/Transfer）、通知权限
├── core/
│   ├── Dates.kt                  # 日期工具（ISO 日期、加月收敛、剩余天数）
│   ├── Models.kt                 # 数据模型（含空间）+ 容错 JSON 解析 + ExpiryCalc（推算/FNV-1a）
│   ├── ExpiryStore.kt            # 仓库单例（StateFlow + Mutex 落盘、合并逻辑）
│   ├── ImageStore.kt             # 图片落盘/压缩/迁移
│   ├── SettingsStore.kt          # 设置（主题/通知开关/推送时刻/自动同步开关）
│   ├── Backup.kt                 # zip 打包与解析（java.util.zip + SAF）
│   ├── Transfer.kt               # 跨设备物品包（zip 打包 / manifest 解析 / 落库）
│   ├── TransferNet.kt            # 局域网 TCP 服务端 + 客户端 + mDNS 发现
│   ├── PairCrypto.kt             # 配对握手密码学（ECDH P-256 + HKDF + AES-GCM）
│   ├── TransferManager.kt        # 传输会话（配对码、发送进度、待接收批次）
│   ├── Similarity.kt             # 名称归一化与二元组相似度（纯函数）
│   ├── AutoSyncMerge.kt          # 合并计划 + 智能合并 + 待合并冲突持久化
│   ├── SyncStateStore.kt         # 每台设备的同步水位、增量筛选、已同步 id
│   └── AutoSyncEngine.kt         # 自动同步引擎（独立 mDNS/TCP + 增量同步 + 闹钟排程）
├── notify/
│   ├── ReminderService.kt        # 提醒计划计算 + AlarmManager 排程 + 前台兜底
│   ├── Receivers.kt              # AlarmReceiver（触发通知）/ BootReceiver（开机重排）
│   └── AutoSyncReceiver.kt       # 自动同步的 30 分钟定时兜底
├── ocr/                          # 离线识别（纯逻辑，全部可单测）
│   ├── OcrEngine.kt              # ML Kit 中文识别器封装（单例、串行）
│   ├── TextNormalizer.kt         # 全角/零宽/中文数字日期归一
│   ├── DateExtractor.kt          # 日期正则 + 关键词 + 打分 + 合理性校验
│   ├── NameExtractor.kt          # 版面分析取品名（字号 + 黑名单）
│   ├── ResultMerger.kt           # 多图聚类与合并
│   ├── PrefillMapper.kt          # 合并结果 → 预填草稿
│   └── OcrModels.kt              # OcrLine / 候选 / PrefillDraft
├── widget/
│   ├── ExpiryWidgetProvider.kt   # 2×2 / 4×2 / 4×4 三种尺寸的小组件 + 本地数据读取
│   ├── WidgetTheme.kt            # 小组件浅色/深色配色（跟随应用主题）
│   ├── WidgetPrefs.kt            # 仅清理旧版本遗留的自定义背景图
│   └── ExpiryWidgetService.kt    # RemoteViewsService：可上下滑动的列表
└── ui/
    ├── Theme.kt / Common.kt      # 主题、异步图片加载
    ├── HomeScreen.kt             # 列表页（含识别入口）
    ├── PickImagesScreen.kt       # 选图页（拍照 + 相册，≤6）
    ├── OcrProgressScreen.kt      # 识别进度页
    ├── EditScreen.kt             # 编辑/新增页（支持预填与候选）
    ├── ImageViewer.kt            # 全屏图片查看器
    ├── CategoryScreen.kt         # 分类管理
    ├── SpaceScreen.kt            # 空间管理
    ├── TransferScreen.kt         # 跨设备传输（发现 / 配对 / 发送 / 接收）
    ├── ConflictsScreen.kt        # 待合并冲突（逐条手动合并）
    └── SettingsScreen.kt         # 设置页
```

## 平台能力调研结论（2026-11-15 · ColorOS 17 / 真我 GT8 Pro）

- **桌面小组件**：ColorOS 17 的「万能卡片 / 原子组件」基于标准 Android AppWidget 协议，
  支持自由拖动改尺寸、2×2 / 4×2 等规格与自适应小组件；**没有额外的私有「开放端口」**，
  统一走 AppWidget/RemoteViews，第三方也不需要 OPPO SDK。载入失败通常不是系统没适配，
  而是布局里用了 RemoteViews 不支持的类（例如普通 `View`）或单次事务超过 binder 上限——
  本轮 v1.18.1 就是把遮罩层从 `View` 换成 `FrameLayout` 修掉的。
  因此按 AOSP 标准实现：`appwidget-provider` 的 `targetCellWidth/Height`（API 31+）+
  `minWidth/minHeight` + `resizeMode="horizontal|vertical"`，列表用 `RemoteViews + ListView`
  （启动器原生滚动），并在 `onAppWidgetOptionsChanged` 里重绑适配器。
- **ColorOS 端侧 AI / OCR 接口**：ColorOS 17 的端侧大模型、Persona X、Agent Matrix、X-OmniClaw
  属于系统级能力 / 合作方生态 / 研究项目，没有对普通第三方 App 公开、可离线直接调用的 OCR/AI SDK，
  部分能力还依赖云端推理。按「不联网、本机优先」的约束**不接入**，继续用 ML Kit bundled 中文模型，
  并新增「识别原文」核对作为人工兜底。
- **小布助手**：系统级语音助手，第三方接入走技能平台 / 快应用 / 快捷指令（需商务接入与服务侧能力），
  没有公开 Android SDK 让普通 App 直接接收语音指令。**不接入**；语音建记录方案已在本次改版中整体下线。
- **ColorOS 17 离线语音**：官方的离线语音包（约 110–300MB）是给小布助手用的，**没有对第三方
  App 开放独立识别 SDK**；第三方可用的离线入口只有 Android 的 `SpeechRecognizer` 端侧识别
  （`createOnDeviceSpeechRecognizer`，API 31+，需 `RECORD_AUDIO`），真机实测 ColorOS 未向第三方
  开放 `RecognitionService`，内置 Vosk 又会让 APK 增大约 66MB，因此语音输入最终**整块移除**。
- **流体云实测修正**：ColorOS 17 / Android 17 真机上，旧通知虽然带
  `android.requestPromotedOngoing=true`、ONGOING_EVENT、BigTextStyle 与高重要性渠道，
  但 `category=reminder` + `vis=PRIVATE` 时系统没有给 `FLAG_PROMOTED_ONGOING`。
  把汇总通知改成 `CATEGORY_STATUS` + `VISIBILITY_PUBLIC` + 非彩色化后，
  `dumpsys notification` 已显示 `flags=ONGOING_EVENT|ONLY_ALERT_ONCE|PROMOTED_ONGOING`，
  设置页诊断也显示「已提升为实时动态 / 状态栏胶囊」。系统设置里的
  「实时活动会以流体云展示，不受通知设置的影响」需保持开启；自定义 UPK 流体云模板
  才需要 OPPO 泛在服务框架 / 企业白名单。
- **预测性返回**：Android 14+ 直接在 `onBackInvokedDispatcher` 注册
  `OnBackAnimationCallback`（`onBackStarted` / `onBackProgressed` / `onBackCancelled` /
  `onBackInvoked`），比 Compose `PredictiveBackHandler` 少一层转译，设置页、新增页都能拿到
  0→1 的进度；Android 13 及以下才用 Compose handler。Manifest 需
  `android:enableOnBackInvokedCallback="true"`，主页不拦截返回，系统才会给回到桌面的预测动画。
- **ColorOS 17 流体设计（Living Flow）**：官方公开的是设计理念——凝光视效（柔和半透明、分层光照）、
  流体动效（状态之间连续移动/合并）、柔性反馈（控件有弹性、动画跟随手势速度），
  **没有面向第三方 App 的公开设计规范或 SDK**。因此按其三层原则做近似：
  更大的圆角与浅蓝白底（凝光）、页面/列表转场（流体）、预测性返回跟手（柔性反馈），
  不引入私有渲染接口。
- **OPPO 云服务家庭版**：HeyTap/OPPO 云的家庭共享属于系统级服务（照片、联系人、便签、整机备份），
  开放平台面向应用商店/游戏/支付/主题等，**没有对普通第三方 App 开放的云盘/家庭共享 API**。
  即便存在，也会违反本项目「只允许同 Wi-Fi 同步」的安全约束，因此不接入；跨设备仍走局域网 TCP。

## 安全边界

- 唯一的数据同步通道是**同一 Wi-Fi 内的 TCP 直连**（自动同步 `_jexpiry-auto._tcp.` + 手动分享协议），
  没有云端、蓝牙、账号、推送或任何其他同步方式；
- **身份**：只有手动配对过的设备才能参与同步。配对时接收端生成 32 字节长期密钥（重复配对复用原值），
  双方各存一份；未配对设备的请求一律拒绝且不回数据。设置页「已配对设备」可解除信任；
- **认证**：每条同步/取图请求带 `HMAC-SHA256(secret, cmd|from|nonce|ts)`，时间窗 120 秒 + nonce 去重，
  响应同样签名；配对码用 `SecureRandom` 生成，同一 IP 每分钟最多 3 次尝试；
- **配对握手**：两端各生成一次性 EC P-256 密钥对，ECDH 出共享密钥，再用
  `HKDF-SHA256(共享密钥, salt=SHA-256(双方公钥+配对码))` 派生会话密钥；长期密钥与 token
  以 AES-256-GCM 密文回传。被动抓包只能拿到两把临时公钥，无法解出长期密钥；不支持该握手
  的旧客户端会被拒绝，不回退明文。残余风险：能主动劫持并替换公钥的中间人仍需 PAKE 才能彻底堵住；
- **加密**：自动同步载荷与图片走 AES-256-GCM；手动分享/空间同步的 zip 走 AES-256-CTR + HMAC-SHA256
  （流式，内存恒定），完整性校验不通过即丢弃，不落盘；
- **解包上限**：备份与传输包按「单条 8–12MB / 累计 256MB / 条目数 1000–2000」三重限制读取，
  避免 zip 炸弹把进程撑到 OOM；路径仍过 `normalizeImagePath` 白名单（禁绝对路径、`..`、非图片扩展名）；
- **落盘**：`data.json` 及其它状态文件走「临时文件 + fsync + rename」，覆盖前保留 `.bak`，
  解析失败自动回退，杜绝写一半导致的整库丢失；
- **系统备份排除**：`allowBackup=false` + `dataExtractionRules` 排除 files/sharedpref/database/cache，
  云备份与换机直传不会带走物品、图片与同步密钥；迁移只用应用内 SAF 导出；
- **权限**：仅 `POST_PROMOTED_NOTIFICATIONS` 一个普通权限与本轮无关地保留；
  小组件只读应用内部存储，Service `exported=false` + `BIND_REMOTEVIEWS`；已移除 `RECORD_AUDIO`；
- **可见性**：设置页「锁屏隐藏详情」开启后，通知与流体云在锁屏上只显示汇总文案；
- 冲突只在本机持久化，合并只增改不删除；`syncId` / `updatedAt` / `rev` 仅通过局域网 TCP 交换，不上传。

## 备注

- minSdk 26（自适应图标需要 v26；如需更低版本需补位图图标）
- **自动同步的后台边界**：没有引入前台服务（Android 16 对 `dataSync` 类型有 6 小时/24 小时配额与启动限制），
  采用「应用进程存活期间常驻发现 + 每 30 分钟 AlarmManager 兜底唤醒」。ROM 省电策略过严时闹钟可能延后，
  打开一次 App 或点「立即同步」即可补上；若要严格实时后台同步，后续应改走 user-initiated data transfer job
- **自动同步补图片的边界**：文本与图片分两步——先合并物品/分类/空间，再按缺失文件列表拉取；
  文件按相对路径 `images/<id>.jpg` 落地（临时 `.part` 后 rename），单张 ≤6MB、单轮 ≤40MB，
  超出部分留到下一轮。图片只在同一 Wi-Fi 的 TCP 连接上传输，不经过任何服务器
- **小组件外观不再单独持久化**：不再有 `bg_alpha` / `files/widget_bg.jpg`；旧版本遗留的背景图
  在应用启动时清理，颜色完全由当前应用主题决定
- 自动同步只增改、不删除；冲突仅在无法自动判定新旧时出现（当前规则下基本为空），历史冲突按 key 去重
- **识别零联网**：ML Kit 用 `com.google.mlkit:text-recognition-chinese`（bundled，模型随 APK），
  依赖会把 `INTERNET` / `ACCESS_NETWORK_STATE` 合并进来；这两个权限现在**保留**——
  跨设备分享要在局域网内直连（TCP + mDNS），仍然没有任何访问互联网的请求。
  不可换成 `play-services-mlkit-text-recognition-chinese`（需联网下模型）
- **APK 体积**：OCR 的 native 库每个 ABI 7–11MB，已用 `ndk.abiFilters` 只保留 `arm64-v8a` + `armeabi-v7a`，
  从 56MB 降到 29MB（release）。代价：x86 模拟器装不上，真机不受影响，需要模拟器调试时临时把 ABI 加回来
- **不再打包离线语音模型**：`assets/model-cn`（Vosk 中文小模型，约 66MB）与 `vosk-android` 依赖已删除，
  APK 体积从约 87MB 回落到约 29MB，安装后会释放原先解压到 `files/vosk-cn` 的空间
- AGP 9.1 内置 Kotlin，构建脚本不单独应用 `org.jetbrains.kotlin.android`
- 相机拍照经 FileProvider（`cache/camera/`）接收，落库前删除临时文件
- 图片压缩：等比缩放至最长边 1280，JPEG 质量 85
