# Eye Android アプリ - UI 担当 引継ぎ書

別のLLMにこのファイルだけ渡してUI実装を依頼するための切り出し版。

## 0. ゴール

Android アプリ (Kotlin + Jetpack Compose, Material 3) のUI部分を実装する。
画面3つ + 共通コンポーネント数個。バックエンドのロジック層は別で実装されるので、UIはダミーデータでも動くように作る。

ロジック層との接続は ViewModel (Hilt or 手書きDI) を通じて受け渡す前提。各画面は ViewModel を依存性注入で受け取り、StateFlow/State を購読する形にする。

## 1. 全体方針

- **テーマ**: Material 3 (Material You)
- **配色**: ダークテーマ既定 (撮影中まぶしくない)、ライトテーマも対応
- **アクセント色**: 単色1つ (デフォルトは深い青 #2962FF)、設定不要
- **タイポ**: システムフォント、本文 14sp、返答文 16sp
- **言語**: 全文日本語 (英語化は不要)
- **対応サイズ**: スマホ縦持ち専用、タブレット・横持ちは考慮外
- **アニメーション**: スプラッシュ無し、画面遷移は標準のslide

## 2. 画面一覧

### 2.1 MainScreen (起動時)

**役割**: カメラプレビュー + シャッター + 履歴

**レイアウト** (上から):

```
┌────────────────────────────┐
│ Eye                     ⚙ │  ← TopAppBar (タイトル + 設定アイコン)
├────────────────────────────┤
│                            │
│                            │
│   [カメラプレビュー]          │ ← 画面の60%、CameraX PreviewView
│                            │  (PreviewViewはラッパで提供される、ダミー時は黒い四角)
│                            │
│                            │
├────────────────────────────┤
│        ┌──────┐            │
│        │  ◯   │            │ ← シャッターボタン (中央、72dp円)
│        └──────┘            │
├────────────────────────────┤
│ ▼ 履歴 (引き上げ可能シート)    │ ← BottomSheet (折り畳み式)
│ ┌──┐ "猫が窓辺で寝ている..."│
│ │画│  10:23                │
│ └──┘                       │
│ ┌──┐ "看板に「禁煙」と..."  │
│ │画│  10:15                │
│ └──┘                       │
└────────────────────────────┘
```

#### 必須UI要素 (ボタン・コンポーネント)

| ID | 要素 | 仕様 |
|---|---|---|
| **U-01** | TopAppBar | タイトル "Eye" + 右上に⚙(Settings)アイコン |
| **U-02** | カメラプレビューエリア | `AndroidView { PreviewView }` のスロット。プレビュー未取得時は黒背景 + 中央に「カメラ起動中」テキスト |
| **U-03** | シャッターボタン | 中央下、直径72dp、白円+黒枠2dp。タップで撮影トリガ。長押しで連射(V2)。送信中はローディングサークル化 |
| **U-04** | 履歴シート (BottomSheet) | 下端から引き上げる Material3 ModalBottomSheet。閉じてる時は履歴件数バッジ表示 (例: 履歴 (15件)) |
| **U-05** | 履歴アイテム | 横並び: 64dp サムネ + 返答テキスト2行 + 時刻 (右下12sp淡色)。タップでHistoryDetailScreenへ |
| **U-06** | プロンプトチップ群 (V2) | カメラプレビューとシャッターの間に水平スクロール: [説明] [OCR] [翻訳] [日本語化]。選択中はFilledTonal、未選択はOutlined |
| **U-07** | 撮影中オーバーレイ | シャッターボタンタップ時、画面に半透明白フラッシュ → 撮影音 (ハプティクス含む) → ローディング表示 |
| **U-08** | エラー Snackbar | 画面下から、「ネットワーク不通」等のメッセージ + [再試行] アクション |

#### 状態 (ViewModelから受け取る)

```kotlin
data class MainScreenState(
    val isShuttering: Boolean,           // 撮影中の連打防止フラグ
    val isSending: Boolean,              // サーバ送信中
    val captures: List<CaptureUi>,       // 履歴 (新しい順)
    val cameraPermissionGranted: Boolean,// CAMERA権限あるか
    val errorMessage: String?,           // 出すべきエラー (nullなら出さない)
    val currentPrompt: String,           // 今選んでるプロンプト
    val availablePrompts: List<String>   // チップに出す候補
)

data class CaptureUi(
    val id: String,
    val thumbnailPath: String,           // ファイルパス (Coilで読み込む)
    val responseText: String?,           // null=処理中
    val timestampLabel: String,          // "10:23"
    val isLoading: Boolean,              // trueなら...表示
    val isError: Boolean                 // trueならエラーアイコン
)
```

#### イベント (UI→ViewModel)

```kotlin
sealed class MainScreenEvent {
    object ShutterPressed : MainScreenEvent()
    object SettingsPressed : MainScreenEvent()
    data class HistoryItemTapped(val captureId: String) : MainScreenEvent()
    data class PromptSelected(val prompt: String) : MainScreenEvent()
    object ErrorDismissed : MainScreenEvent()
    object RetryLastSend : MainScreenEvent()
    object RequestCameraPermission : MainScreenEvent()
}
```

### 2.2 SettingsScreen

**役割**: サーバーURL・プロンプト等の設定

#### 必須UI要素

| ID | 要素 | 入力タイプ | 説明 |
|---|---|---|---|
| **S-01** | TopAppBar | - | "設定" + 戻る矢印 |
| **S-02** | Server URL | OutlinedTextField | プレースホルダ `http://mini-pc.tailnet-name.ts.net:8080` |
| **S-03** | API Token | OutlinedTextField (パスワード表示) | 任意、空でもOK |
| **S-04** | デフォルトプロンプト | OutlinedTextField (3行) | 既定値: `この画像に何が写っていますか？簡潔に日本語で説明してください。` |
| **S-05** | 画像最大サイズ | Slider (800-2048px) | 値ラベル表示 (例: 1920px) |
| **S-06** | JPEG品質 | Slider (50-95) | 値ラベル表示 (例: 85) |
| **S-07** | 履歴保持件数 | OutlinedTextField (数値) | デフォルト 100 |
| **S-08** | 音量ボタンで撮影 | Switch | デフォルトON |
| **S-09** | 結果を読み上げる (V2) | Switch | デフォルトOFF |
| **S-10** | 接続テストボタン | Button (FilledTonal) | タップで `/health` を叩いて結果トースト |
| **S-11** | アプリバージョン | テキスト | 末尾に小さく "v0.1.0 / build 1" |

#### 状態

```kotlin
data class SettingsState(
    val serverUrl: String,
    val apiToken: String,
    val defaultPrompt: String,
    val imageMaxSize: Int,
    val jpegQuality: Int,
    val historyRetention: Int,
    val volumeButtonCapture: Boolean,
    val ttsAutoRead: Boolean,
    val isTesting: Boolean,
    val testResult: String?,    // "OK 200" or "Failed: ..."
    val appVersion: String
)
```

#### イベント

```kotlin
sealed class SettingsEvent {
    data class UpdateServerUrl(val value: String) : SettingsEvent()
    data class UpdateToken(val value: String) : SettingsEvent()
    data class UpdatePrompt(val value: String) : SettingsEvent()
    data class UpdateImageMaxSize(val value: Int) : SettingsEvent()
    data class UpdateJpegQuality(val value: Int) : SettingsEvent()
    data class UpdateHistoryRetention(val value: Int) : SettingsEvent()
    data class UpdateVolumeButton(val value: Boolean) : SettingsEvent()
    data class UpdateTtsRead(val value: Boolean) : SettingsEvent()
    object TestConnection : SettingsEvent()
    object Back : SettingsEvent()
}
```

### 2.3 HistoryDetailScreen

**役割**: 過去の撮影 1件を詳細表示

#### 必須UI要素

| ID | 要素 | 説明 |
|---|---|---|
| **D-01** | TopAppBar | "詳細" + 戻る矢印 + 右上に削除アイコン |
| **D-02** | 画像表示 | 上半分、ピンチイン/アウトでズーム可、タップでフルスクリーン |
| **D-03** | 返答全文 | 下半分、SelectionContainerで選択コピー可能 |
| **D-04** | メタ情報 | 撮影時刻 / 使用プロンプト / 応答時間 (durationMs) / トークン使用量 |
| **D-05** | アクションボタン群 | [コピー] [共有] [読み上げ] [再送信] |

#### 状態

```kotlin
data class HistoryDetailState(
    val capture: CaptureUi?,
    val isReading: Boolean,        // TTS再生中
    val isReSending: Boolean
)
```

## 3. 共通コンポーネント

| コンポーネント | パス | 用途 |
|---|---|---|
| `ShutterButton` | `ui/components/ShutterButton.kt` | 円形シャッター、状態(idle/loading/disabled)で見た目変更 |
| `HistoryItem` | `ui/components/HistoryItem.kt` | 履歴1件のRow表示 |
| `ChatBubble` | `ui/components/ChatBubble.kt` | 返答文の吹き出し風表示 |
| `LoadingDots` | `ui/components/LoadingDots.kt` | 「・・・」のアニメーション |
| `EmptyHistory` | `ui/components/EmptyHistory.kt` | 履歴ゼロ件時の表示 (アイコン + 「まだ撮影がありません」) |

## 4. テーマ定義 (Color.kt / Theme.kt)

```kotlin
// Color.kt
val PrimaryDark = Color(0xFF2962FF)    // アクセント青
val PrimaryLight = Color(0xFF82B1FF)
val Surface = Color(0xFF121212)        // ダーク背景
val OnSurface = Color(0xFFE0E0E0)
val Error = Color(0xFFCF6679)
val Success = Color(0xFF4CAF50)
```

DynamicColor (Material You) は API 31+で有効化、それ未満は固定パレット。

## 5. 文字列 (strings.xml)

主要なものを列挙、自由に追加してOK:

```xml
<string name="app_name">Eye</string>
<string name="settings_title">設定</string>
<string name="settings_server_url">サーバーURL</string>
<string name="settings_api_token">APIトークン (任意)</string>
<string name="settings_default_prompt">デフォルトプロンプト</string>
<string name="settings_image_max_size">画像最大サイズ</string>
<string name="settings_jpeg_quality">JPEG品質</string>
<string name="settings_history_retention">履歴保持件数</string>
<string name="settings_volume_button">音量ボタンで撮影</string>
<string name="settings_tts_read">結果を読み上げる</string>
<string name="settings_test_connection">接続テスト</string>
<string name="default_prompt">この画像に何が写っていますか？簡潔に日本語で説明してください。</string>
<string name="prompt_describe">説明</string>
<string name="prompt_ocr">OCR</string>
<string name="prompt_translate">翻訳</string>
<string name="error_network">ネットワークに接続できません</string>
<string name="error_server">サーバーエラー (HTTP %1$d)</string>
<string name="error_timeout">サーバー応答なし (60秒タイムアウト)</string>
<string name="error_auth">APIトークンが無効です</string>
<string name="error_camera">カメラエラー: %1$s</string>
<string name="action_retry">再試行</string>
<string name="action_settings">設定を開く</string>
<string name="action_copy">コピー</string>
<string name="action_share">共有</string>
<string name="action_read_aloud">読み上げ</string>
<string name="action_resend">再送信</string>
<string name="action_delete">削除</string>
<string name="history_count">履歴 (%1$d件)</string>
<string name="history_empty">まだ撮影がありません</string>
<string name="camera_permission_required">カメラ権限が必要です</string>
<string name="camera_permission_grant">許可する</string>
<string name="capture_loading">処理中…</string>
```

## 6. 動作モック (ダミー実装の例)

ロジック層が無い段階でも UI が動くように、ダミーStateを返す Preview/モックを用意:

```kotlin
val mockState = MainScreenState(
    isShuttering = false,
    isSending = false,
    cameraPermissionGranted = true,
    errorMessage = null,
    currentPrompt = "この画像に何が写っていますか？...",
    availablePrompts = listOf("説明", "OCR", "翻訳"),
    captures = listOf(
        CaptureUi("1", "/dummy.jpg", "猫が窓辺で寝ている。日差しが暖かそう。", "10:23", false, false),
        CaptureUi("2", "/dummy.jpg", "看板に「禁煙」と書かれている。", "10:15", false, false),
        CaptureUi("3", "/dummy.jpg", null, "10:14", true, false),  // ローディング中
        CaptureUi("4", "/dummy.jpg", null, "10:10", false, true),  // エラー
    )
)
```

各画面の `@Preview` でこのmockを使って Compose Preview が見られるようにする。

## 7. 実装ファイル (UI担当が触る範囲)

```
app/src/main/java/com/cudgk/eye/ui/
├── MainScreen.kt
├── SettingsScreen.kt
├── HistoryDetailScreen.kt
├── components/
│   ├── ShutterButton.kt
│   ├── HistoryItem.kt
│   ├── ChatBubble.kt
│   ├── LoadingDots.kt
│   └── EmptyHistory.kt
└── theme/
    ├── Color.kt
    ├── Type.kt
    └── Theme.kt

app/src/main/res/values/
├── strings.xml
└── colors.xml
```

NavHost / NavController も UI担当が書く (3画面の遷移定義)。
ViewModel は **抽象クラス + 引数で受け取る型** までは決め打ちでOK、実装は別担当。

## 8. やらなくていいこと

- ネットワーク通信 (Retrofit/OkHttp) ← 別担当
- カメラ実装 (CameraX) ← 別担当 (ただし PreviewView を貼るスロットは用意してね)
- DB (Room) ← 別担当
- 設定永続化 (DataStore) ← 別担当
- 権限要求の実装 ← 別担当 (UIは「権限ない」状態の表示だけ)
- 起動時の初期化処理 ← 別担当
- TTS実装 ← V2、UIにボタンだけ用意

## 9. 確認事項 (UI担当からSPEC作者への質問可)

不明点があれば聞いてくれ。特に:
- カメラプレビューのアスペクト比をどう扱うか (4:3 or 16:9)
- ダークテーマのみにするかライト併用か (既定はダーク優先)
- ボタンの押下ハプティクスの強度
