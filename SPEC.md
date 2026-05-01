# Eye - Android アプリ仕様書

## 1. 目的

不要になったAndroidスマホをLLM (Qwen3-VL-30B-A3B-Instruct) の「目」として再利用する。
カメラで撮影した画像を自宅PC上のVLMサーバーに送り、画像の説明文を返してもらう。
歩きながら見たもの・気になったものを尋ねるためのウェアラブル/ハンドヘルド端末。

## 2. 想定ユーザー / 利用シーン

### 想定端末 (実機確認済み)

- **機種**: Xiaomi 13T (XIG04, au/KDDI版)
- **OS**: Android 15 (API 35)
- **RAM**: 7.6GB
- **CPU**: ARM64-v8a
- **カメラ**: Camera2 Hardware Level FULL (manual sensor / RAW / フラッシュ対応)
- **接続**: USBケーブル経由のADBが既に通じている (`adb devices` 検出可)

### ユーザー

- **ユーザー**: 自分一人 (個人利用、認証はトークン方式で十分)
- **利用シーン**:
  - 外出先で見つけた物・看板・植物・料理を「これ何?」と聞く
  - 部屋を見回して何があるか説明させる
  - OCR的に標識・メニューを読ませる
  - 視覚障害者支援的な使い方も想定 (TTS読み上げあり)

## 3. システム全体構成

```
┌─────────────────────────┐         ┌────────────────────────┐
│   Android スマホ          │         │ 自宅ミニPC                │
│  (このアプリ)              │ HTTPS   │ (192.168.1.13)         │
│                          │ POST    │                        │
│   ┌──────────────┐       │ ───────►│ ┌──────────────────┐   │
│   │ シャッターUI    │       │         │ │ llama-server     │   │
│   └──────────────┘       │         │ │ Qwen3-VL-30B-A3B │   │
│   ┌──────────────┐       │         │ │ port 8080        │   │
│   │ CameraX 撮影  │       │ ◄──────│ └──────────────────┘   │
│   └──────────────┘       │ JSON    │   31 tok/s             │
│   ┌──────────────┐       │ resp    │                        │
│   │ チャット履歴UI  │       │         │ Cloudflare Tunnel経由   │
│   └──────────────┘       │         │ で公開                   │
└─────────────────────────┘         └────────────────────────┘
```

### 通信経路

**`Android → Tailscale → 自宅PC llama-server`** を採用。

- すでにメインPC・ミニPCに Tailscale が入っている (`100.83.48.127` 等)
- AndroidにTailscale公式アプリを入れて MagicDNS で `mini-pc.tailnet-name.ts.net:8080` を叩く
- 認証は Tailscale ACL に任せる (Bearer Tokenはオプション、MVPでは不要)
- WiFi/4G/iPhoneテザリングどれでも、Tailscaleが自動経路選択
- 公開インターネットに穴を開けないので安全

## 4. 機能要件

### 4.1 必須機能 (MVP)

| ID | 機能 | 説明 |
|---|---|---|
| F-01 | カメラプレビュー | 起動時に背面カメラのリアルタイムプレビューを全画面表示 |
| F-02 | シャッター撮影 | 大きなシャッターボタン1個。タップでJPEG撮影 (1920×1080程度に縮小) |
| F-03 | サーバー送信 | 撮影画像をBase64でJSON化、`POST /v1/chat/completions` へ送信 |
| F-04 | 結果表示 | LLMの返答テキストをチャット形式で画面下部に表示 (画像サムネ + 返答文) |
| F-05 | 履歴保持 | 送信した画像と返答をローカルDBに保存、起動時に復元 |
| F-06 | 設定画面 | サーバーURL・APIトークン・プロンプト・画像サイズ・履歴保持件数を設定可能 |
| F-07 | エラー表示 | ネットワーク・サーバーエラー時は具体的な文言で通知 (再試行ボタン付き) |
| F-08 | 物理音量ボタン撮影 | 音量上下ボタンでもシャッター可能 (片手操作用) |

### 4.2 推奨機能 (V2)

| ID | 機能 | 説明 |
|---|---|---|
| F-10 | TTS読み上げ | 返答文を音声で読み上げ (端末標準TTS、日本語音声) |
| F-11 | プロンプト切替 | 「説明して」「OCRして」「翻訳して」等のプリセットを下部にチップ表示 |
| F-12 | 連射防止 | 送信中は再撮影ロック、Spinner表示 |
| F-13 | フラッシュ切替 | 暗所撮影用にフラッシュON/OFF/AUTO |
| F-14 | カメラ切替 | 前面/背面カメラ切替 |
| F-15 | オフラインキュー | ネット圏外時は撮影画像をキューに保存、復活時に自動送信 |

### 4.3 将来検討 (V3+)

- 音声入力 (「これ何?」と話してから撮影)
- ウェイクワード対応 ("Hey Eye")
- ホーム画面ウィジェットでクイック撮影
- BLE Bluetoothシャッターボタン対応
- 連続キャプチャモード (動画 → フレーム抽出)

## 5. UI 仕様 (UI担当LLMへの引継ぎ用に詳細記述)

### 5.1 画面一覧

| 画面 | 役割 | 遷移 |
|---|---|---|
| MainScreen | カメラプレビュー + シャッター + チャット履歴 | 起動時 |
| SettingsScreen | サーバーURL等の設定 | MainScreen右上の歯車アイコンから |
| HistoryDetailScreen | 過去の撮影詳細 (画像拡大 + 返答全文 + コピー) | MainScreen下部の履歴アイテムタップ |

### 5.2 MainScreen レイアウト (要点)

```
┌─────────────────────────────┐
│ ⚙ Eye                       │ ← 上部TopBar (タイトル + 設定アイコン)
├─────────────────────────────┤
│                             │
│                             │
│       [カメラプレビュー]      │ ← 全画面の60%、CameraX PreviewView
│                             │
│                             │
│       ┌────────┐            │
│       │   ◯    │ ← シャッター │ ← 中央下、直径72dp、押しやすく
│       └────────┘            │
├─────────────────────────────┤
│ プリセット: [説明] [OCR] [翻訳] │ ← V2: チップ群
├─────────────────────────────┤
│ 履歴 (下からせり上がる、スクロール) │
│ ┌──┐ "猫が窓辺で寝ている..."  │
│ │画│  10:23 ↳ コピー          │
│ └──┘                        │
│ ┌──┐ "看板に「禁煙」と..."     │
│ │画│  10:15                  │
│ └──┘                        │
└─────────────────────────────┘
```

### 5.3 デザイン指針

- **テーマ**: Material 3 (Material You)、ダークテーマ優先 (撮影時に眩しくない)
- **カラー**: 黒基調 + 単色アクセント (好みで青/緑) 、シャッターボタンは白円
- **タイポ**: システムフォント、本文 14sp、返答文 16sp で読みやすく
- **アニメーション**: 撮影時のシャッター閉じるエフェクト、結果到着時にチャットがスライドイン
- **タップ領域**: シャッターボタンは最低 56dp、誤タップ防止のため画面端から24dp離す
- **ハプティクス**: シャッターボタンタップ時に軽く振動

### 5.4 SettingsScreen 項目

| 項目 | 型 | デフォルト | 備考 |
|---|---|---|---|
| Server URL | TextField (URL) | `http://mini-pc.tailnet-name.ts.net:8080` | TailscaleのMagicDNS名 |
| API Token | TextField (パスワード表示) | (空) | Bearer Token (任意、空でもOK) |
| Default Prompt | TextField (複数行) | "この画像に何が写っていますか？簡潔に日本語で説明してください。" | |
| Image Max Size | Slider | 1920px | 800-2048px、長辺の最大値 |
| JPEG Quality | Slider | 85 | 50-95 |
| History Retention | NumberField | 100 | 履歴保持件数 |
| Volume Button Capture | Switch | ON | 音量ボタンでシャッター |
| TTS Auto-read | Switch | OFF | 結果到着時に読み上げ (V2) |
| Test Connection | Button | - | タップで `/health` を叩いて結果表示 |

### 5.5 エラーUI

| エラー種別 | 表示 |
|---|---|
| ネットワーク不通 | Snackbar 「ネットワークに接続できません」 + 再試行ボタン |
| サーバー応答なし | ダイアログ 「サーバー応答なし (Timeout 60s)」 + 再試行 |
| 認証失敗 (401) | ダイアログ 「APIトークンが無効です」 + 設定を開くボタン |
| サーバーエラー (5xx) | Snackbar 「サーバーエラー (HTTP {code})」 |
| 画像撮影失敗 | Snackbar 「カメラエラー: {message}」 |

## 6. API 仕様

### 6.1 リクエスト (アプリ → サーバー)

**Endpoint**: `POST http://{tailscale_host}:8080/v1/chat/completions`
例: `http://mini-pc.tailnet-name.ts.net:8080/v1/chat/completions`

**Headers**:
```
Content-Type: application/json
```
(Bearer Token は MVP では未使用、Tailscale網内のみアクセス可なので省略)

**Body** (OpenAI互換):
```json
{
  "model": "qwen3-vl",
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "{user_prompt}" },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,{base64}" } }
      ]
    }
  ],
  "max_tokens": 512,
  "temperature": 0.2
}
```

**Timeout**: 60秒 (画像処理 + 生成で15-30秒想定、余裕を持たせる)

### 6.2 レスポンス (サーバー → アプリ)

```json
{
  "choices": [
    {
      "message": { "role": "assistant", "content": "<説明文>" },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 1053,
    "completion_tokens": 288,
    "total_tokens": 1341
  },
  "timings": {
    "predicted_per_second": 31.3,
    "prompt_per_second": 97.5
  }
}
```

アプリは `choices[0].message.content` を取り出して表示。`timings` は設定画面のデバッグセクションに出してもよい。

### 6.3 ヘルスチェック

`GET {base_url}/health` → 200 OK を返せば疎通OK。設定画面の「Test Connection」で叩く。

## 7. データモデル

### 7.1 Capture (撮影記録)

```kotlin
@Entity
data class Capture(
    @PrimaryKey val id: String,           // UUID
    val timestamp: Long,                  // 撮影時刻 epoch ms
    val imagePath: String,                // ローカルJPEGの絶対パス (内部ストレージ)
    val thumbnailPath: String,            // 256x256サムネ
    val prompt: String,                   // 送ったプロンプト
    val response: String?,                // LLM応答 (null=処理中/失敗)
    val status: CaptureStatus,            // SENDING, DONE, FAILED
    val errorMessage: String?,            // 失敗理由
    val durationMs: Long?,                // リクエスト所要時間
    val tokenUsage: TokenUsage?           // prompt_tokens等
)

enum class CaptureStatus { SENDING, DONE, FAILED }

data class TokenUsage(val promptTokens: Int, val completionTokens: Int)
```

### 7.2 Settings (設定値)

DataStore (Preferences) で保存:
- `server_url: String`
- `api_token: String`
- `default_prompt: String`
- `image_max_size: Int`
- `jpeg_quality: Int`
- `history_retention: Int`
- `volume_button_capture: Boolean`
- `tts_auto_read: Boolean`

## 8. Android 権限

`AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

実行時パーミッション要求:
- `CAMERA` (起動時、未許可ならRationale表示)

## 9. 非機能要件

| 項目 | 要件 |
|---|---|
| 対応OS | **Android 8.0 (API 26) 以上** (実機検証は Android 15 / API 35) |
| 撮影→結果表示 | 全体25秒以内 (うちLLM処理20秒、ネットワーク2-3秒) |
| 起動時間 | 2秒以内 |
| メモリ消費 | 200MB以下 |
| ローカルストレージ | 上限300MB (履歴100件 × ~3MB) |
| バッテリ | 1時間で~10%消費目安 (常時カメラON、画面ON前提) |
| オフライン耐性 | 撮影自体はオフラインでも可、送信は次回オンライン時 |

## 10. 技術スタック

| 領域 | 採用技術 | 理由 |
|---|---|---|
| 言語 | **Kotlin** | Android標準 |
| UI | **Jetpack Compose** | 宣言的・コード量少 |
| 最低SDK | API 26 (Android 8.0) | 古いスマホもカバー |
| ターゲットSDK | API 34 (Android 14) | Play Storeの最新要件相当 |
| カメラ | **CameraX** | Camera2より圧倒的に楽 |
| HTTP | **Retrofit + OkHttp + Moshi** | 標準的なAndroid HTTPクライアント |
| 非同期 | **Kotlin Coroutines + Flow** | suspend関数で書きやすい |
| 永続化 | **Room** (履歴) + **DataStore** (設定) | Jetpack標準 |
| DI | **Hilt** (任意) | テスト可能性 |
| 画像処理 | Bitmap + Base64標準API | サードパーティ不要 |
| ビルド | Gradle (Kotlin DSL) + Android Studio | 標準 |

## 11. ビルド・配布

### 開発時

```
1. Android Studio で開く
2. 端末をUSBで接続、開発者モードON、USBデバッグON
3. Run (▶) → APKがインストールされて起動
```

### 配布

開発者本人のみ使用する想定なので **Play Store には出さない**。

- **APKを直接インストール**: `app-release.apk` を端末にコピー → 「不明なアプリのインストールを許可」してインストール
- 自分用なので署名は debug keystore で十分 (本格運用時のみ release署名)

### CI

任意。GitHub Actions で `gradlew assembleRelease` を回して APK を成果物としてダウンロード可能にする程度で十分。

## 12. ファイル構成 (推奨)

```
eye-android/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/cudgk/eye/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ui/
│   │   │   │   ├── MainScreen.kt          ← UI担当LLMの主戦場
│   │   │   │   ├── SettingsScreen.kt
│   │   │   │   ├── HistoryDetailScreen.kt
│   │   │   │   ├── components/
│   │   │   │   │   ├── ShutterButton.kt
│   │   │   │   │   ├── HistoryItem.kt
│   │   │   │   │   └── ChatBubble.kt
│   │   │   │   └── theme/
│   │   │   │       ├── Color.kt
│   │   │   │       ├── Type.kt
│   │   │   │       └── Theme.kt
│   │   │   ├── camera/
│   │   │   │   └── CaptureUseCase.kt      ← CameraX wrapping
│   │   │   ├── network/
│   │   │   │   ├── LlamaApi.kt            ← Retrofit interface
│   │   │   │   ├── ApiClient.kt
│   │   │   │   └── dto/
│   │   │   ├── data/
│   │   │   │   ├── CaptureDao.kt
│   │   │   │   ├── CaptureDb.kt
│   │   │   │   ├── CaptureEntity.kt
│   │   │   │   └── SettingsRepository.kt
│   │   │   └── domain/
│   │   │       ├── CaptureRepository.kt
│   │   │       └── SendCaptureUseCase.kt
│   │   └── res/
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   └── colors.xml
│   │       └── drawable/
│   │           └── ic_launcher.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

### UI担当LLMへの分担

UI担当LLMには以下のファイルを書かせる:
- `ui/MainScreen.kt`
- `ui/SettingsScreen.kt`
- `ui/HistoryDetailScreen.kt`
- `ui/components/*.kt` 全部
- `ui/theme/*.kt`
- `res/values/strings.xml`, `res/values/colors.xml`

これらは「§5 UI仕様」と「§7 データモデル」だけあれば書ける。ロジック層は別途指示する。

ロジック側 (network, camera, data, domain) は俺が書く方針。

## 13. サーバー側準備 (アプリ書く前にやる事)

1. **Tailscale Android アプリインストール**:
   - Google Play から Tailscale 公式アプリを Xiaomi 13T に入れる
   - 既存アカウント (メインPC・ミニPCと同じ) でログイン
   - 接続するとTailscale網内に入る (CGNAT帯の100.x.x.xのIP取得)
2. **MagicDNS 名を確認**: Tailscale管理画面でミニPCのDNS名を確認 (例: `mini-pc.your-tailnet.ts.net`)
3. **llama-serverの起動引数確認**: `--host 0.0.0.0` で起動済み (確認済)
4. **疎通確認**: Androidアプリに `http://mini-pc.your-tailnet.ts.net:8080/health` と入れて 200 が返れば OK
5. **(任意) Bearer Token化**: 心配ならNGINX等でリバプロして認証層を追加

## 14. リスク・既知の制約

- **古いAndroid端末ではCameraXのバージョン互換問題**が出る可能性 → API 26+で十分検証する
- **長時間カメラON で発熱**: 30分以上連続使用すると熱で性能低下する場合あり、対策は使用パターン次第
- **モバイル回線で20秒以上の応答待ち**: Cloudflare Tunnel やWAFで途中切断される可能性 → アプリ側で60秒タイムアウトに設定済み
- **大きな画像(>2MB)送信時に Cloudflare の制限**: 100MB以下なので問題ないはずだが、念のため画像は1920px縮小+JPEG quality 85 で1-2MBに抑える

## 15. 開発フェーズ

- **Phase 1 (MVP)**: F-01〜F-08 のみ実装、リリース可能な最低限
- **Phase 2 (使い勝手向上)**: F-10〜F-15 を追加
- **Phase 3 (拡張)**: 音声入力、ウィジェット、Bluetoothシャッター等

## 16. 完了の定義 (DoD)

Phase 1 として:
- [ ] 撮影 → サーバー送信 → 結果表示 が一連で動く
- [ ] 設定画面でサーバーURL・トークン・プロンプトを変更できる
- [ ] 履歴が永続化され、再起動後も復元される
- [ ] エラー時に分かりやすい文言が出る
- [ ] APK署名済みファイルが生成され、開発者本人の端末で動作する
- [ ] ネットワーク不通時にクラッシュしない

---

**この仕様書だけ読めば、UI担当LLMはMainScreen/SettingsScreen/HistoryDetailScreenの完全実装ができる**。
不足してたら「§5に追記してくれ」と言ってもらう想定。
