#ifdef __cplusplus
#import <opencv2/opencv.hpp>
#endif

#import <opencv2/imgcodecs/ios.h>
#import "NakamonWrapper.h"
#import "../../../shared_cpp/NakamonAnalyzerCore.h"

@implementation NakamonWrapper

/**
 * UIImage を cv::Mat (RGB) に変換するヘルパー
 */
+ (cv::Mat)cvMatFromUIImage:(UIImage *)image {
    CGColorSpaceRef colorSpace = CGImageGetColorSpace(image.CGImage);
    CGFloat cols = image.size.width;
    CGFloat rows = image.size.height;

    cv::Mat mat(rows, cols, CV_8UC4); // 常に RGBA で作成

    CGContextRef contextRef = CGBitmapContextCreate(mat.data,
                                                    cols,
                                                    rows,
                                                    8,
                                                    mat.step[0],
                                                    colorSpace,
                                                    kCGImageAlphaNoneSkipLast |
                                                    kCGBitmapByteOrderDefault);

    CGContextDrawImage(contextRef, CGRectMake(0, 0, cols, rows), image.CGImage);
    CGContextRelease(contextRef);

    // RGB に変換
    cv::Mat rgbMat;
    cv::cvtColor(mat, rgbMat, cv::COLOR_RGBA2RGB);
    return rgbMat;
}

+ (double)performMatchWithScene:(UIImage *)scene
                    templateImg:(UIImage *)templateImg
                        centerX:(int)centerX
                        centerY:(int)centerY
                 verticalMargin:(int)vMargin
               horizontalMargin:(int)hMargin {

    cv::Mat sceneMat = [self cvMatFromUIImage:scene];
    cv::Mat tplMat = [self cvMatFromUIImage:templateImg];

    double score = Nakamon::NakamonAnalyzerCore::performColorMatch(
        sceneMat, tplMat, centerX, centerY, vMargin, hMargin
    );

    return score;
}

+ (double)findBestMonsterMatch:(UIImage *)roi
                     templates:(NSArray<UIImage *> *)templates {

    cv::Mat roiMat = [self cvMatFromUIImage:roi];
    // GALAXY/iOS共通のコントラスト補正を適用
    Nakamon::NakamonAnalyzerCore::normalizeImage(roiMat);

    std::vector<cv::Mat> tplMats;
    for (UIImage *img in templates) {
        tplMats.push_back([self cvMatFromUIImage:img]);
    }

    Nakamon::MatchResult res = Nakamon::NakamonAnalyzerCore::findBestMatchWithScales(roiMat, tplMats);

    return res.score;
}

@end
