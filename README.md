# summerplugin
マイクラサーバーを軽量化するプラグイン(保証なし)
        一応検証済み　mythicmobs　8000～9000体TPS20　このプラグインがない場合 TPS1５以下
        他のプラグインがないと意味がないプラグイン
1.20.1しか対応してないwしかもAIほぼ任せｗｗブラックボックス
仕組み
設定の読み込みと初期化:

loadConfig() メソッドで、設定ファイルから非同期化対象や除外プラグイン、タイムアウト時間などを読み込みます。また、ログレベルの設定やスレッドプールの初期化も行います。
プラグインのラッピング:

wrapPlugins() メソッドで、非同期化対象のプラグインを選別し、それぞれのプラグインについてイベントハンドラを非同期で実行するように設定します。これにより、対象プラグインのイベント処理が非同期で実行されるようになります。
非同期リスナーの実装:

AsyncListener クラスが、各プラグインのイベントリスナーとして機能します。イベントが発生すると、それをスレッドプールにタスクとして提出します。また、タイムアウトの監視やエラーハンドリングもこの部分で行います。
マルチスレッドプールの管理:

MultiThreadPool クラスが、優先度ごとに異なるスレッドプールを管理し、各タスクを適切な優先度で実行します。これにより、高優先度のタスクや低負荷のタスクを効率的に処理できます。
コマンド処理とタブ補完:

onCommand() メソッドと onTabComplete() メソッドが、管理者がプラグインの設定を操作するためのインターフェースを提供します。これにより、実行時に設定の調整や管理が容易になります。
