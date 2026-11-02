# 通知典藏 · NotiVault

Android 10+ 原生通知典藏 App。繁體中文、預設深色科技風，支援淺色及跟隨系統模式。資料保存在手機與自行設定的 WebDAV，沒有第三方分析或廣告服務。

## 安裝與開始使用

1. 安裝交付的 `NotiVault-1.0.0.apk`。這是個人測試簽章 APK；首次安裝時依手機提示允許此來源安裝 App。
2. 開啟 App，在首次引導中開啟系統「通知存取」權限，返回後首頁應顯示服務已連線。
3. 收到新通知後，可在「歷史」月曆選日期查看。相同通知內容變更會保存新版本，完全相同的回呼會略過。
4. 在「WebDAV」填入最終 WebDAV 網址、備份目錄、帳號與密碼／應用程式密碼。先測試連線，再儲存設定。
5. 預設每小時、只用 Wi-Fi 備份；「設定」可允許行動網路、調整頻率及保留天數，也能立即手動備份。

手機可能對側載 App 限制敏感設定；若通知存取無法啟用，請先在系統的 App 資訊頁檢查「允許受限制的設定」。部分廠牌另有自啟動與省電限制。強制停止 App 或關閉通知存取期間，無法持續收集通知。

## 功能與資料定義

- 總覽：目前本機通知版本總數、今日筆數、7／30／90 天或全部的每日折線圖、最高日期（含並列）及典藏資料用量。
- 歷史：月曆、日期筆數、每頁 50 筆、App 篩選、標題／內文搜尋、通知詳情、圖片預覽及完整 JSON 匯出。
- CSV：在歷史日期右側按下載圖示，用 Android 檔案選擇器匯出當天 CSV；不需要整個儲存空間的存取權限。
- WebDAV：遠端依安裝 ID／年／月分目錄，可在歷史頁切到「遠端 WebDAV」，選裝置與日期查閱。遠端暫存不計入本機統計。
- 保留天數：預設今天及前六個日曆日。過期資料只有在本次遠端校驗成功、目的地及本機版本未改變時才清除。沒有設定備份、網路不符或驗證失敗時，可能在手機保留超過七天。
- 統計只根據目前手機仍保留的通知版本；清理後總數、趨勢與最高日期會更新，不保留累計筆數摘要。聊天通知中的多個訊息不另外拆算，常駐通知、群組摘要及進度更新也會計數。

## CSV v1 格式

檔名為 `YYYY-MM-DD.csv`，依 `StatusBarNotification.postTime` 與固定 `Asia/Taipei` 時區分日。檔案是 UTF-8 BOM、CRLF、RFC 4180 引號轉義。**第一欄固定為 `postTime`，值為 API 原始 Unix 毫秒數。**

```text
postTime,postTimeIso,capturedAt,eventId,instanceId,notificationKey,version,archiveDate,archiveZoneId,packageName,appLabel,title,text,sbnJson,notificationJson,rankingJson,serializationErrorsJson,schemaVersion
```

常用欄位獨立成欄，其餘放在完整 JSON 欄位。`notificationJson` 包含公開通知欄位、extras、訊息陣列、動作、圖示及可讀取的圖片；圖片保存為 PNG Base64。每份 CSV 可獨立讀回，不依賴外部附件。完整 API 37 公開欄位清單定義於 `NotificationSerializer.kt`，舊系統會自動略過尚未提供的 API。

公開 API 沒有提供可永久匯出的 PendingIntent／Binder 權杖或 RemoteViews 內部指令；這些保存公開描述與限制原因。無法存取的 URI、未知 Parcelable、讀取失敗及文字樣式限制會寫入 `serializationErrorsJson`，不會默默丟棄。不能補回 App 啟用前已消失的通知，也無法還原 Android 15+ 遮蔽的驗證碼等敏感內容。

可讀取的本機圖片 URI 也會保存原始影像 Base64；不連線抓取通知內的網路網址。單筆圖片 URI 讀取上限為 16 MB，超過或權限不足時保留 URI 並記錄明確原因。大型圖片可能使單一 CSV 儲存格超過 Excel 的儲存格長度限制；CSV 本身與 App 的讀回支援長儲存格。要檢視完整結構可用通知詳情的 JSON 匯出。原始文字保留原樣，匯入試算表時請以文字欄位匯入通知內文。

## WebDAV 相容與安全清理

使用標準 Basic 帳密驗證，預設要求 HTTPS；HTTP 必須明確開啟。使用系統信任的 TLS 憑證，未加入略過憑證檢查的選項。網址須直接指向最終 WebDAV 端點；不自動跟隨重新導向傳送帳密。Nextcloud 例：`https://host/remote.php/dav/files/使用者/`；NAS 請使用該服務提供的 WebDAV 端點。

伺服器需支援 PROPFIND、MKCOL、GET、PUT、MOVE、暫存檔 DELETE，以及既有檔案的強 ETag 和 DAV `If` 條件式覆蓋。DELETE 僅用於本次建立的暫存／連線測試檔；到期清理不刪除遠端日誌。

```text
備份目錄/<installation UUID>/YYYY/MM/YYYY-MM-DD.csv
```

流程：固定本機版本 → 讀取既有遠端內容 → 按事件 ID 合併 → 上傳唯一暫存檔 → 條件式 MOVE 到正式檔 → 重新 GET → 比對 SHA-256 → 更新驗證紀錄 → 檢查保留天數及本機版本 → 清除本機內容／CSV／索引並回收空間。

已清理日期若又收到補抓資料，會合併既有遠端檔案。遠端非預期變更、同事件 ID 內容不符、格式不支援、上傳中斷或雜湊不符，都會保留本機資料並顯示錯誤。更換伺服器／帳密／目錄後重新驗證；不會自動搬移已經只存在舊伺服器的歷史。當天 CSV 是持續更新的快照。

密碼以 Android Keystore AES-GCM 加密後存入 DataStore；通知保存在 App 私有儲存空間。系統雲端備份與裝置搬移已排除資料，避免與自行指定的 WebDAV 混用。解除安裝會刪除手機資料與金鑰；遠端 CSV 保留，新安裝可重新設定 WebDAV 並選舊裝置資料夾閱讀。

遠端查閱使用獨立暫存索引。未開啟的 CSV 採 50 MB LRU 限制；目前正在開啟的一日檔案及解析暫存可暫時超過限制，關閉查閱後可淘汰；設定頁可清除所有遠端查閱暫存。

## 開發與建置

- JDK 17+、Android SDK platform `37.0`、Build Tools `36.0.0`。
- AGP 9.5.0、Gradle Wrapper 9.6.0、Kotlin 2.4.21、Compose BOM 2026.09.00、Room 2.8.5、WorkManager 2.12.0。
- 開啟 Android Studio 專案根目錄，安裝缺少的 SDK，設定 `local.properties` 的 `sdk.dir` 後同步。

Windows PowerShell 7：

```powershell
./tools/build.ps1
```

此腳本可使用 `%USERPROFILE%/AndroidToolchain` 下的已安裝工具；必要時為中文專案路徑建立暫存 ASCII junction，處理部分 Windows Java／Gradle 測試工作程序的字碼頁問題。junction 指向原專案，不複製或搬移檔案。

一般命令：

```text
gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
gradlew :app:assembleDebug
```

Release 開啟 R8 與資源縮減，並使用測試簽章以便直接安裝；**尚未設定 Google Play 正式發布簽章**。正式發布前需自行保管簽章金鑰，並處理套件可見性權限與商店隱私聲明。

## 驗證

單元測試包含 Unicode／換行／引號／長 Base64 CSV 讀回、格式拒絕、日期時區、七日邊界、原子寫入中斷，以及 MockWebServer 模擬的上傳、合併、校驗、權限錯誤、ETag 衝突、目的地變更、版本競爭與重試復原。另以 Robolectric Android 10 驗證通知序列化、持久化去重與清理復原，以及 Compose 引導、四頁切換、月曆與主題操作。

仍須用你的實體手機與 WebDAV 伺服器完成連線測試，確認廠牌省電限制、通知存取授權及伺服器條件式 MOVE 相容性。測試不使用你的真實伺服器或帳密。
