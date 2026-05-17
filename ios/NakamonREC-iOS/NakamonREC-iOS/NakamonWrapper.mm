#ifdef __cplusplus
#import <opencv2/opencv.hpp>
#endif

#import <opencv2/imgcodecs/ios.h>
#import "NakamonWrapper.h"
#import "../../../shared_cpp/NakamonAnalyzerCore.h"

@implementation NakamonMatchResult
- (instancetype)initWithScore:(double)score index:(NSInteger)index {
    self = [super init];
    if (self) {
        _score = score;
        _index = index;
    }
    return self;
}
@end

@implementation NakamonWrapper

// プロセス内モンスターテンプレキャッシュ。
// Extension 起動から終了までの間、calibrate 後に 1 回 populate される。
static std::vector<cv::Mat> gCachedMonsterTemplates;

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

+ (void)cacheMonsterTemplates:(NSArray<UIImage *> *)templates {
    gCachedMonsterTemplates.clear();
    gCachedMonsterTemplates.reserve(templates.count);
    for (UIImage *img in templates) {
        gCachedMonsterTemplates.push_back([self cvMatFromUIImage:img]);
    }
}

+ (double)findBestMonsterMatchUsingCache:(UIImage *)roi {
    if (gCachedMonsterTemplates.empty()) {
        return 0.0;
    }
    cv::Mat roiMat = [self cvMatFromUIImage:roi];
    Nakamon::NakamonAnalyzerCore::normalizeImage(roiMat);
    Nakamon::MatchResult res = Nakamon::NakamonAnalyzerCore::findBestMatchWithScales(roiMat, gCachedMonsterTemplates);
    return res.score;
}

+ (double)findBestMonsterMatchInRegion:(UIImage *)scene
                               centerX:(int)centerX
                               centerY:(int)centerY
                                 width:(int)width
                                height:(int)height {
    NakamonMatchResult *result = [self bestMonsterInRegion:scene
                                                   centerX:centerX
                                                   centerY:centerY
                                                     width:width
                                                    height:height];
    return result.score;
}

+ (NakamonMatchResult *)bestMonsterInRegion:(UIImage *)scene
                                    centerX:(int)centerX
                                    centerY:(int)centerY
                                      width:(int)width
                                     height:(int)height {
    return [self bestMonsterAndSaveInRegion:scene
                                    centerX:centerX
                                    centerY:centerY
                                      width:width
                                     height:height
                                   savePath:nil];
}

/**
 * cv::Mat (RGB) を PNG として savePath に書き出すヘルパー。
 * savePath が nil の場合は何もしない。失敗してもサイレント (詳細画面は無いだけ)。
 *
 * 注: OpenCV iOS の MatToUIImage は CGColorSpaceCreateDeviceRGB を使い R,G,B 順の
 *     バイト列をそのまま CGImage 化する。cvMatFromUIImage 側で既に RGB に変換済みなので
 *     ここでは追加の色変換は不要。BGR に変換すると R↔B が入れ替わり、見た目が
 *     "ネガポジ反転" のような色になる。
 */
+ (void)saveMat:(const cv::Mat &)mat toPath:(NSString *)savePath {
    if (!savePath || mat.empty()) return;
    UIImage *img = MatToUIImage(mat);
    if (!img) return;
    NSData *png = UIImagePNGRepresentation(img);
    if (!png) return;
    [png writeToFile:savePath atomically:YES];
}

+ (double)performMatchAndSaveWithScene:(UIImage *)scene
                            templateImg:(UIImage *)templateImg
                                centerX:(int)centerX
                                centerY:(int)centerY
                         verticalMargin:(int)vMargin
                       horizontalMargin:(int)hMargin
                               savePath:(NSString *)savePath {

    cv::Mat sceneMat = [self cvMatFromUIImage:scene];
    cv::Mat tplMat = [self cvMatFromUIImage:templateImg];

    // C++ 側と同じロジックで ROI を切り出して保存する
    if (savePath && !sceneMat.empty() && !tplMat.empty()) {
        int imgW = sceneMat.cols;
        int imgH = sceneMat.rows;
        if (tplMat.cols <= imgW && tplMat.rows <= imgH) {
            int roiW = std::min(tplMat.cols + hMargin * 2, imgW);
            int roiH = std::min(tplMat.rows + vMargin * 2, imgH);
            int left = std::max(0, std::min(centerX - roiW / 2, imgW - roiW));
            int top  = std::max(0, std::min(centerY - roiH / 2, imgH - roiH));
            cv::Rect roiRect(left, top, roiW, roiH);
            if ((roiRect.x + roiRect.width) <= imgW &&
                (roiRect.y + roiRect.height) <= imgH) {
                cv::Mat roi;
                sceneMat(roiRect).copyTo(roi);
                [self saveMat:roi toPath:savePath];
            }
        }
    }

    double score = Nakamon::NakamonAnalyzerCore::performColorMatch(
        sceneMat, tplMat, centerX, centerY, vMargin, hMargin
    );
    return score;
}

+ (NakamonMatchResult *)bestMonsterAndSaveInRegion:(UIImage *)scene
                                            centerX:(int)centerX
                                            centerY:(int)centerY
                                              width:(int)width
                                             height:(int)height
                                           savePath:(NSString *)savePath {
    if (gCachedMonsterTemplates.empty()) {
        return [[NakamonMatchResult alloc] initWithScore:0.0 index:-1];
    }

    cv::Mat sceneMat = [self cvMatFromUIImage:scene];
    int imgW = sceneMat.cols;
    int imgH = sceneMat.rows;
    int w = std::min(width, imgW);
    int h = std::min(height, imgH);
    int left = std::max(0, std::min(centerX - w / 2, imgW - w));
    int top  = std::max(0, std::min(centerY - h / 2, imgH - h));
    cv::Rect roiRect(left, top, w, h);
    cv::Mat workRoi;
    sceneMat(roiRect).copyTo(workRoi);

    // 正規化 *前* のオリジナル ROI を保存 (人が見たときに自然な色味)
    if (savePath) {
        [self saveMat:workRoi toPath:savePath];
    }

    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);
    Nakamon::MatchResult res = Nakamon::NakamonAnalyzerCore::findBestMatchWithScales(workRoi, gCachedMonsterTemplates);
    return [[NakamonMatchResult alloc] initWithScore:res.score index:res.bestScaleIndex];
}

@end
