# 要塞對戰 Fortress Duel

Minecraft Fabric mod（1.26.2 / Fabric Loader 0.19.3）。玩家可以向任意玩家發起挑戰，接受後雙方被傳送到附近框出來的 n×n 競技場，各自守著一座烽火台核心——建造階段蓋牆、攻擊階段互轟，先摧毀對方核心者獲勝。

玩法與內容移植自同名的網頁版（雙人 P2P 攻城對戰），但改成即時制：建材不用買，自己挖、自己蓋。

## 玩法

1. `/duel challenge <玩家>` 發起挑戰，對方 `/duel accept <你>` 接受
2. 雙方被傳到附近一塊夠平的地，系統框出 48×48 的競技場、兩側各放一座烽火台核心（400 血）與一棟武器商店
3. 倒數 5 秒後開始輪替：**建造 60 秒**（可蓋方塊、不能攻擊）↔ **攻擊 60 秒**（不能蓋、可以開火）
4. 打掉對方的核心就獲勝。打完競技場會還原成開場前的樣子

動作列會顯示：階段與剩餘秒數、餘額、手上武器的彈藥（FPS 式的 `100/120`）。兩座核心的血量各佔一條 boss 血條，雙方都看得到。

## 指令

| 指令 | 說明 |
| --- | --- |
| `/duel challenge <玩家>` | 發起挑戰 |
| `/duel accept <玩家>` | 接受挑戰 |
| `/duel deny <玩家>` | 拒絕 |
| `/duel forfeit` | 投降 |
| `/duel solo` | 測試用：單人練習，對手是不會還手的靶子（OP） |
| `/duel reload` | 重讀設定（OP） |
| `/duel give <武器>` | 測試用：直接拿一把武器（OP） |
| `/duel spawn <怪物>` | 測試用：生一批怪（OP） |
| `/duel hire <職業>` | 測試用：直接雇一名工人，不用付錢（OP） |

## 設定

全部走 YAML，放在 `config/fortress-duel/`，第一次啟動會自動產生預設檔。改完 `/duel reload`。

| 檔案 | 內容 |
| --- | --- |
| `duel.yml` | 競技場尺寸、核心血量、階段長度、開場物資、經濟參數 |
| `weapons.yml` | 武器：傷害／濺射／穿甲／散佈／裝填／彈道／彈藥上限 |
| `mobs.yml` | 怪物：血量／攻擊／體型／賞金／掛哪些技能 |
| `skills.yml` | 怪物技能：分身、召喚、回血、順移、藥水效果 |
| `incidents.yml` | 突發事件：權重、效果、音樂 |
| `npcs.yml` | NPC：用哪個實體當殼、右鍵開哪間店 |
| `shops.yml` | 商店商品：武器、子彈、建材、工人與價格 |
| `buildings.yml` | 建築藍圖：調色盤 + 逐層字元圖 |
| `jobs.yml` | 工人：礦工／農夫的產出速率，礦脈／稻田的數量與位置 |

新增一種怪、一把武器、一個技能、一間店、一棟建築、一種職業都**不需要寫 Java**。

## 系統

- **經濟**：虛擬餘額。收入來自怪物賞金（付子彈、當下兌現）、工人的產出（付本金、慢慢回收）與每輪建造階段的固定收入
- **工人**：花錢雇礦工與農夫，他們自己走到場上的礦脈與稻田工作。節點每輪在你的陣地隨機補、採光就沒了，而且離熊貓圈有一段距離——工人得走出牆外。工人跟節點對手都打得掉，所以場上第一次有了熊貓以外的攻擊目標
- **武器**：彈丸自己積分、自己射線檢測，不是 MC 實體。方塊有血量（原版硬度 × 係數），累積傷害會顯示挖掘裂痕
- **怪物技能**：YAML 掛在怪身上，五種觸發時機（生成／定時／受傷／殘血／死亡）
- **NPC 商店**：用原版箱子的介面型別，客戶端不需要額外程式碼
- **建築**：開場兩側各蓋一份，南半場自動鏡射，結束時連同競技場一起還原

## 開發

```bash
./gradlew runServer      # 開發伺服器（run/server）
./gradlew runClient      # 客戶端，帳號 Alpha（run/client）
./gradlew runClientTwo   # 第二個客戶端，帳號 Bravo（run/client2）
./gradlew build          # 打包，產物在 build/libs/
```

要測對戰至少需要兩個玩家，所以內建了第二個客戶端的啟動設定。只是要試武器、怪物、技能或商店的話不用開兩個視窗——`/duel solo` 一個人就能進場，對手是一座不會還手的靶子。

## 音樂家與加歌

場上除了軍火商，還站著一個**音樂家**（`npcs.yml` 的 `musician`）。右鍵他開店，點一格買一張**光碟**，
不用錢；拿在手上右鍵才播放，**場上兩邊都聽得到**，放完之前再按沒有作用。光碟是買斷的：
不會用掉、放完可以再放，對戰結束也不收回，所以「什麼時候放」是玩家自己的決定。
他的貨架不寫在 `shops.yml` 裡——是從 `songs.yml` 的曲目表生出來的。

**懶得手動做的話**：[設定工作台](https://xinbow99.github.io/fortress-duel-editor/)把整套流程收成一個拖放——
丟音檔進去，它幫你轉檔、量長度、產出 `songs.yml` 與資源包 zip，連 sha1 都算好。純瀏覽器端，
檔案不會上傳到任何地方。

手動的話就兩步：

```bash
# 1. 轉成 OGG 丟進去，檔名 = 音效 id（只能用小寫英數與底線）
ffmpeg -i 你的歌.mp3 -c:a libvorbis -q:a 4 -ar 44100 \
  src/main/resources/assets/fortress-duel/sounds/wow.ogg
```

```yaml
# 2. songs.yml 加三行
  wow:
    name: Wow
    length: 5
```

`sounds.json` 不用碰——`./gradlew build` 會掃那個資料夾自動產生（檔名不合法會直接讓建置失敗，
而不是在遊戲裡默默沒有聲音）。伺服器端 `/duel reload` 就會看到新歌，不用重開。

## 伺服器資源包（音效）

音檔在模組的 `assets/` 裡——**裝了模組的客戶端**本來就聽得到。要讓**沒裝模組的原版客戶端**也聽到，
就掛一個伺服器資源包：玩家一進來原版會問「是否下載伺服器資源包」，同意之後自動套用，
不用手動裝任何東西。

```bash
./gradlew resourcePack   # 產出 build/resourcepack/*.zip，並印出 sha1
```

把 zip 放到任何公開網址（現成的一份在 [Release `resources-v2`](https://github.com/XinBow99/MinecraftFarbicFortress/releases/tag/resources-v2)），然後填進 `server.properties`：

```properties
resource-pack=https://github.com/XinBow99/MinecraftFarbicFortress/releases/download/resources-v2/fortress-duel-resources-1.0.0.zip
resource-pack-sha1=e274bbee96303ea92e11bfcc23caee18f325c578
resource-pack-required=false
```

`sha1` 不是可選的裝飾：填了客戶端才會快取，不然每次進來都重下載一次。改了 zip 就要一起換掉這兩行——
網址不變而內容變了的話，客戶端會拿舊的快取。

`resource-pack-required=true` 則是不裝就踢出去；點歌只是好玩的東西，預設不強制。

## 發佈

打上 `v` 開頭的 tag 就會自動建置並開一個 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 授權

CC0-1.0
