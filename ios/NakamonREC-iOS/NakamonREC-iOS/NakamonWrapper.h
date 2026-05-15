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

@end

NS_ASSUME_NONNULL_END
