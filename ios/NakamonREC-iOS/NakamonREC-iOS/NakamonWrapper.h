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
 */
+ (void)cacheMonsterTemplates:(NSArray<UIImage *> *)templates;

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

@end

NS_ASSUME_NONNULL_END
