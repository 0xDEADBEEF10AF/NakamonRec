# R8 (release の isMinifyEnabled = true) 用の keep ルール。
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 行番号を残す (Play Console のクラッシュ stack を mapping.txt で元に戻したとき行まで読めるように)。
# Signature は Gson が List<BattleRecord> などの型引数を読むのに必要。
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*

# Gson で保存/読込するデータモデル。フィールド名 = JSON のキーなので名前を一切変えない。
# 変わると既存ユーザーの戦績・GP 記録・校正データが読めなくなり、上書き保存でデータが壊れる。
# Gson で扱う型を追加したら、ここにも必ず追加すること。
-keep class com.dqw.nakamonrec.MonsterData { *; }
-keep class com.dqw.nakamonrec.BattleRecord { *; }
-keep class com.dqw.nakamonrec.GrandPrixRecord { *; }
-keep class com.dqw.nakamonrec.BattleHistory { *; }
-keep class com.dqw.nakamonrec.BoxConfig { *; }
-keep class com.dqw.nakamonrec.CalibrationData { *; }

# OpenCV: ネイティブ (JNI) 側がクラス名・フィールド名で Java 側を参照するため丸ごと残す
-keep class org.opencv.** { *; }
