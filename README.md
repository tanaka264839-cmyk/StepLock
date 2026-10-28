# StepLock 🚶‍♂️🔒

> 歩数をスマホ時間に変える Android アプリ

「また止められなかった」——スマホをだらだら見てしまうのは、意志力の問題ではありませんでした。  
StepLock は、**歩いた分だけスマホが使える**という仕組みで、スマホ依存を行動で解決します。

## 特徴

- 📱 **スマホ制限** — 選んだアプリを、歩いてためた時間だけ使える（100歩で1分）
- 🚶 **歩数計測** — 端末の歩数センサーを、常駐のサービスがリアルタイムに数える
- 🔒 **その場で止める** — 時間が0になったら、開いているアプリにも全画面の覆いを出す
- 🔓 **自動で解放** — 歩いて時間がたまれば、覆いは自動で閉じる

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose（Material 3） |
| 主な仕組み | AccessibilityService（前面のアプリの検知と覆い）／フォアグラウンドサービス（health）／歩数センサー（TYPE_STEP_COUNTER） |
| 対応 | Android 8.0（API 26）以上・targetSdk 36 |
| 通信 | なし（データは端末の中だけに保存） |
| ビルドツール | Gradle (Kotlin DSL) |

## セットアップ

```bash
git clone https://github.com/tanaka264839-cmyk/StepLock.git
```

Android Studio でプロジェクトを開き、エミュレータまたは実機でビルド。

## デモ動画

▶️ [YouTube Shorts で見る](https://youtube.com/shorts/loQ52K4KPnk)

## 現在の状況

Google Play で一般公開中（2026年8月〜）：[StepLock - 歩数をスマホ時間に](https://play.google.com/store/apps/details?id=com.shosoku.steplock)  
約30人のクローズドテストの後、ユーザー補助（AccessibilityService）の審査を通して公開しました。

このリポジトリのコードは v1.12（2026-08-03）時点のものです。Google Play の公開版（難易度の設定・ランダムモードつき）は、その後の版です。

## 作者

初速制作室田中 | [X (Twitter)](https://x.com/EJ6aNsgkA127548) | [note](https://note.com/tanaka_hagatatsu)

企画・設計・開発・テスト・公開まで一人で担当しました（開発は AI〈Claude〉と一緒に進めています）。
