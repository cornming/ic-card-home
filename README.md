# IC 卡控制（ic-card-home）

用手機 NFC 讀 IC 卡，依對照表控制 Google Home 裡的開關類裝置。Android，Kotlin + Compose。

## 安全設計

- 預設「試運行」：刷卡只寫記錄，不會控制裝置。
- 名稱或類型含「門、鎖、door、gate」的裝置預設一律擋下，需在 App 內另外允許。
- 程式裡沒有任何送出鎖定或解鎖的路徑，只使用 OnOff（開關）功能。
- 門禁類裝置（如「一樓大門」：Sonoff 繼電器，經 eWeLink 連 Google Home，已設成點動）只送「開」，
  脈衝長度與自動恢復由 eWeLink 裝置設定決定，App 無法控制。
- 門禁類裝置預設刷卡後先跳出確認視窗（10 秒內沒確認就取消），可在設定改成直接執行。
- 同一張卡 2 秒內重複感應只算一次。
- 讀卡器是自己的手機且 App 要在前景，卡片 ID 被複製的風險低；若之後把手機固定在門口當讀卡器，
  就要改用有加密驗證的卡（例如 DESFire、FeliCa），不能只靠 ID。
- 悠遊卡類通常是固定 ID。銀行卡、手機錢包常是每次都變的隨機 ID，App 會對開頭 08 的 ID 提出警告。
- App 關閉時刷卡由系統叫起 App（0.3.0 起），走的是同一條流程，確認視窗照常出現。
  只採用系統送來的 Tag 物件，不信任 intent 裡自帶的 ID 位元組（任何 App 都能偽造那種 intent）。

## 開發環境

- Android Studio 2024.2.1 以上，JDK 17 以上，實體 Android 10 以上手機（模擬器不行）。
- 手機上要有 Google Home App，裝置要先能在 Home App 裡控制。

### Home API SDK（不在這個 repo 裡）

SDK 的授權禁止再散布，**不可 commit**，也不可放進公開 repo。每個開發者自己取得：

1. 到 Google Home Developers 登入並同意條款，下載 Home API Android SDK（zip）。
2. 解壓縮後把 `com` 目錄合併進 `C:\Users\<你>\.m2\repository`。
3. 本專案用 `play-services-home` 與 `play-services-home-types` 17.1.0，由 `mavenLocal()` 讀取。

### Google Cloud / OAuth

- 專案：`home-api-test`（ID `home-api-test-513708`），OAuth 同意畫面為外部、測試中。
- Android 用戶端：套件 `com.cornming.iccard` + debug 憑證的 SHA-1。
- 只有加入「測試使用者」的 Google 帳號能授權，上限 100 位。
- 換電腦或換 keystore，SHA-1 會變，要回 Google Cloud 更新用戶端。
- Scope 使用 `home.platform.selected.devices`（`HOME_PLATFORM_SCOPE_VERSION_1`）。

## 建置

```
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat :app:installDebug
```

## 簽章與更新

- App 啟動時會查這個 repo 的最新 GitHub Release（最多每 6 小時一次，也可在 App 內手動檢查）。
  有新版時提示，下載 APK、驗證套件名稱／版本／簽章後交給系統安裝，最後仍由使用者在系統畫面確認。
- 第一次更新時，Android 會要求允許這個 App「安裝未知來源 App」。
- 更新必須用同一把金鑰簽署，否則手機會拒絕。金鑰檔 **不在 repo 裡**，請另外備份；
  路徑與密碼放在 `~/.gradle/gradle.properties`：
  `ICCARD_KEYSTORE_PATH`、`ICCARD_KEY_ALIAS`、`ICCARD_KEYSTORE_PASSWORD`、`ICCARD_KEY_PASSWORD`。
  debug 與 release 都用這把金鑰簽，所以 `installDebug` 也能覆蓋已安裝的版本。
- 發布新版：同時調高 `app/build.gradle.kts` 的 `versionCode` 與 `versionName`，再執行
  `.\scripts\release.ps1 -Notes "更新內容"`（需要 `gh auth login`）。

## 使用

1. 開啟 App，點「授權 Google Home」。
2. 「新增卡片」後刷卡，選裝置與動作（開 / 關 / 切換）。
3. 先在試運行下確認記錄正確，再關閉試運行。
4. 0.3.0 起不用先開 App：手機亮屏並解鎖後，把手機靠近已登記的卡，系統會自動開啟 App 並跳出確認視窗
   （這項待現場實機驗證）。App 已在前景時仍用原本的讀卡模式。

## 已實測

- 2026-11-04 在現場完成實測：解鎖手機、打開 App、刷已登記的卡、按確認視窗，一樓大門（Sonoff 繼電器，
  經 eWeLink 連到 Google Home，已設成點動）確實點動開門。
- App 內更新（0.2.0 → 0.2.1）已在實機驗證。

## 已知限制

- 手機要亮屏並解鎖才會讀卡（App 在前景，或由系統因刷卡叫起）；螢幕關閉或鎖定時手機不會讀卡，這是手機的行為，不是 App 能改的。
  卡片貼在金屬後面或金屬上會讀不到，要用防金屬（鐵氧體）貼紙，且感應距離只有 1～3 公分。
  要做到不解鎖就刷卡，需要固定在門口、不鎖屏的備用手機，或專用的讀卡硬體，且卡片 ID 會成為唯一鑰匙
  （固定 ID 的卡可被複製），目前沒有做。
- 只靠卡片 ID 辨識，不做加密驗證。
