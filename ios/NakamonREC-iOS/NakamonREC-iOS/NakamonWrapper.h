#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// per-slot 識別結果 (スコアとマッチしたモンスターのインデックス)
@interface NakamonMatchResult : NSObject
@property (nonatomic, readonly) double score;
@property (nonatomic, readonly) NSInteger index;
- (instancetype)initWithScore:(double)score index:(NSInteger)index NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/// 自動校正用: scene 内で best match した中心位置とスコア
@interface NakamonMatchLocation : NSObject
@property (nonatomic, readonly) CGFloat centerX;   // scene のピクセル座標
@property (nonatomic, readonly) CGFloat centerY;
@property (nonatomic, readonly) double score;
- (instancetype)initWithCenterX:(CGFloat)cx centerY:(CGFloat)cy score:(double)score NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/// per-slot 自動校正用: スロット ROI 内で best match したモンスター index / 位置 / スコア
@interface NakamonSlotMatch : NSObject
@property (nonatomic, readonly) CGFloat centerX;   // scene のピクセル座標
@property (nonatomic, readonly) CGFloat centerY;
@property (nonatomic, readonly) double score;
@property (nonatomic, readonly) NSInteger index;   // cacheMonsterTemplates の index、未マッチは -1
- (instancetype)initWithCenterX:(CGFloat)cx centerY:(CGFloat)cy
                          score:(double)score index:(NSInteger)index NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

@interface NakamonWrapper : NSObject

/**
 * UIImage 同士をマッチングし、最高スコアを返します。
 * Android 版の performColorMatchCached に相当。
 */
+ (double)performMatchWithScene:(UIImage *)scene
                    templateImg:(UIImage *)templateImg
                        centerX:(int)centerX
                        centerY:(int)centerY
                 verticalMargin:(int)vMargin
               horizontalMargin:(int)hMargin;

/**
 * 1つのモンスター画像を複数のスケール（0.9〜1.1）でマッチングし、最高スコアを返します。
 * templates には 0.90, 0.95, 1.0, 1.05, 1.10 倍の順で UIImage が入っていることを想定。
 */
+ (double)findBestMonsterMatch:(UIImage *)roi
                     templates:(NSArray<UIImage *> *)templates;

/**
 * モンスターテンプレートを cv::Mat に変換してプロセス内キャッシュに保持する。
 * 初期化 (calibrate) 直後に 1 回だけ呼ぶことで、以降のマッチ呼び出しで再変換を不要にする。
 * 1 monster あたり 1 バリアント (単一スケール) として格納される。
 */
+ (void)cacheMonsterTemplates:(NSArray<UIImage *> *)templates;

/**
 * モンスターテンプレートを「monster ごとの複数バリアント (マルチスケール) 」としてキャッシュする。
 * - 外側配列: monster (1 entry per monster)
 * - 内側配列: その monster のスケールバリアント (例: 0.85x / 0.95x / 1.0x / 1.10x / 1.20x)
 * ランタイムマッチングで monster ごとに全バリアントを試し、最高スコアを採用する。
 * iPhone SE3 等、UI 描画サイズが他機種と乖離する端末でも適切な倍率を捕捉できるようにするのが目的。
 */
+ (void)cacheMonsterTemplatesGrouped:(NSArray<NSArray<UIImage *> *> *)templateGroups;

/**
 * cacheMonsterTemplates: で保持されたキャッシュを使ってマッチングを行う。
 * UIImage → cv::Mat 変換コストが 1 フレームぶん (ROI のみ) に圧縮される。
 */
+ (double)findBestMonsterMatchUsingCache:(UIImage *)roi;

/**
 * 指定領域 (center + width + height) だけを切り出してキャッシュ済みテンプレでマッチング。
 * 全画面検索ではなく敵モンスター行などの小領域に絞ることで matchTemplate のコストを大幅削減する。
 */
+ (double)findBestMonsterMatchInRegion:(UIImage *)scene
                               centerX:(int)centerX
                               centerY:(int)centerY
                                 width:(int)width
                                height:(int)height;

/**
 * 指定領域でキャッシュ済テンプレすべてを照合し、最高スコアとそのインデックスを返す。
 * per-slot 識別 (8 スロット × 30 テンプレ) で使う。
 */
+ (NakamonMatchResult *)bestMonsterInRegion:(UIImage *)scene
                                    centerX:(int)centerX
                                    centerY:(int)centerY
                                      width:(int)width
                                     height:(int)height;

/**
 * performMatchWithScene と同じ。加えて検索 ROI を PNG として savePath に書き出す。
 * マッチングスコア詳細画面用のサムネ生成に使う。
 */
+ (double)performMatchAndSaveWithScene:(UIImage *)scene
                            templateImg:(UIImage *)templateImg
                                centerX:(int)centerX
                                centerY:(int)centerY
                         verticalMargin:(int)vMargin
                       horizontalMargin:(int)hMargin
                               savePath:(nullable NSString *)savePath;

/**
 * bestMonsterInRegion と同じ。加えて検索 ROI を PNG として savePath に書き出す。
 */
+ (NakamonMatchResult *)bestMonsterAndSaveInRegion:(UIImage *)scene
                                            centerX:(int)centerX
                                            centerY:(int)centerY
                                              width:(int)width
                                             height:(int)height
                                           savePath:(nullable NSString *)savePath;

/**
 * 自動校正用: scene 全体 (もしくは大きい探索範囲) でテンプレートを探し、最高スコア位置を返す。
 * - 戻り値の center は scene のピクセル座標
 * - 自動校正のために 1 回だけ呼ぶ想定 (本番のフレーム毎マッチング用ではない)
 */
+ (NakamonMatchLocation *)findBestMatchLocationInScene:(UIImage *)scene
                                            templateImg:(UIImage *)templateImg;

/**
 * 自動校正用: NMS (Non-Maximum Suppression) で上位 k 件の match を返す。
 * - 各 match 検出後、その周辺の result matrix を抑制して次の match を探す
 * - パーティ選択 3 枠のように、同じテンプレが複数位置に出現するケースに使う
 * - 戻り値はスコア降順 (= 信頼度の高い順)
 */
+ (NSArray<NakamonMatchLocation *> *)findTopKMatchesInScene:(UIImage *)scene
                                                templateImg:(UIImage *)templateImg
                                                          k:(int)k
                                       suppressHalfWidth:(int)halfW
                                      suppressHalfHeight:(int)halfH;

/**
 * per-slot 自動校正用: 指定 ROI 内でキャッシュ済テンプレすべてを試し、最高スコアの
 * テンプレ index と中心位置 (scene のピクセル座標) を返す。
 * bestMonsterInRegion と同じデータを返すが、加えて scene 内の位置情報を伴う。
 */
+ (NakamonSlotMatch *)bestMonsterLocationInRegion:(UIImage *)scene
                                          centerX:(int)centerX
                                          centerY:(int)centerY
                                            width:(int)width
                                           height:(int)height;

/**
 * 詳細校正用: 指定された 1 つのモンスターテンプレを使い、ROI 内の最良位置を返す。
 * 全 127 体走査ではなく単一テンプレ走査なので、磁石テンプレ (低スコアでもピークが出やすい
 * テンプレ群) の false positive を回避できる。事前にユーザーが「このスロットにはこのモンスター」
 * を指定する詳細校正フロー専用。返り値の center は scene のピクセル座標。
 */
+ (NakamonMatchLocation *)findSpecificMonsterLocationInRegion:(UIImage *)scene
                                                  templateImg:(UIImage *)templateImg
                                                      centerX:(int)centerX
                                                      centerY:(int)centerY
                                                        width:(int)width
                                                       height:(int)height
NS_SWIFT_NAME(findSpecificMonsterLocation(inRegion:templateImg:centerX:centerY:width:height:));

/**
 * 1 フレームを cv::Mat に 1 回だけ変換してプロセス内にキャッシュする。
 * 連続して同一 scene に対して複数 ROI を評価する performDeepAnalysis のホットパスで
 * UIImage→cv::Mat 変換コストを削減するために使う。
 * 次の prepareSceneMat: 呼び出しか clearPreparedSceneMat で上書き/解放される。
 */
+ (void)prepareSceneMat:(UIImage *)scene;

/** prepareSceneMat: で保持された cv::Mat を解放する。フレーム解析完了時に呼ぶ。 */
+ (void)clearPreparedSceneMat;

/**
 * prepareSceneMat: で準備済の scene Mat を使い、bestMonsterInRegion と同等の処理を行う。
 * UIImage→Mat 変換が省ける分だけ高速。事前に必ず prepareSceneMat: を呼ぶこと。
 */
+ (NakamonMatchResult *)bestMonsterInPreparedSceneCenterX:(int)centerX
                                                   centerY:(int)centerY
                                                     width:(int)width
                                                    height:(int)height
NS_SWIFT_NAME(bestMonsterInPreparedScene(centerX:centerY:width:height:));

/** prepareSceneMat: 済の scene を使う bestMonsterInRegion:templateIndices: 相当。 */
+ (NakamonMatchResult *)bestMonsterInPreparedSceneCenterX:(int)centerX
                                                   centerY:(int)centerY
                                                     width:(int)width
                                                    height:(int)height
                                           templateIndices:(NSArray<NSNumber *> *)indices
NS_SWIFT_NAME(bestMonsterInPreparedScene(centerX:centerY:width:height:templateIndices:));

/** prepareSceneMat: 済の scene を使う topKMonstersInRegion 相当。 */
+ (NSArray<NakamonMatchResult *> *)topKMonstersInPreparedSceneCenterX:(int)centerX
                                                               centerY:(int)centerY
                                                                 width:(int)width
                                                                height:(int)height
                                                                  topK:(int)topK
NS_SWIFT_NAME(topKMonstersInPreparedScene(centerX:centerY:width:height:topK:));

/**
 * 指定領域でキャッシュ済テンプレすべてを照合し、スコア降順で上位 topK 件を返す。
 * バースト解析の Frame 1 で全テンプレ走査 → 以降のフレームで上位だけを再評価するために使う。
 */
+ (NSArray<NakamonMatchResult *> *)topKMonstersInRegion:(UIImage *)scene
                                                centerX:(int)centerX
                                                centerY:(int)centerY
                                                  width:(int)width
                                                 height:(int)height
                                                   topK:(int)topK;

/**
 * 指定領域で、キャッシュ済テンプレのうち指定 index のものだけを照合して最高スコアを返す。
 * バースト解析の Frame 2-5 で Top-K サブセットのみ評価するために使う。
 */
+ (NakamonMatchResult *)bestMonsterInRegion:(UIImage *)scene
                                    centerX:(int)centerX
                                    centerY:(int)centerY
                                      width:(int)width
                                     height:(int)height
                            templateIndices:(NSArray<NSNumber *> *)indices;

@end

NS_ASSUME_NONNULL_END
