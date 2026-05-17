#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

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

@end

NS_ASSUME_NONNULL_END
