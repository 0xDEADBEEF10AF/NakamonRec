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

@implementation NakamonMatchLocation
- (instancetype)initWithCenterX:(CGFloat)cx centerY:(CGFloat)cy score:(double)score {
    self = [super init];
    if (self) {
        _centerX = cx;
        _centerY = cy;
        _score = score;
    }
    return self;
}
@end

@implementation NakamonSlotMatch
- (instancetype)initWithCenterX:(CGFloat)cx centerY:(CGFloat)cy
                          score:(double)score index:(NSInteger)index {
    self = [super init];
    if (self) {
        _centerX = cx;
        _centerY = cy;
        _score = score;
        _index = index;
    }
    return self;
}
@end

@implementation NakamonWrapper

// プロセス内モンスターテンプレキャッシュ (グループ構造)。
// 各 monster ごとに複数スケール (micro-scale) のバリアントを持つ:
//   gCachedMonsterTemplateGroups[m] = monster m のスケールバリアント配列
// 単一スケールのレガシー cacheMonsterTemplates: は 1 要素のグループとして格納する。
// runtime matching では monster 単位で全バリアントを試して最高スコアを採用する。
static std::vector<std::vector<cv::Mat>> gCachedMonsterTemplateGroups;

// 1 フレームを cv::Mat 化した結果のプロセス内キャッシュ。
// performDeepAnalysis で 1 フレームに対し 8 スロット分の matching を回すホットパスで
// 同じ UIImage を毎呼び出し変換していたコスト (~3MB ピクセルコピー × 8 = 24MB/フレーム)
// を 3MB/フレームに削減する。prepareSceneMat: で更新、clearPreparedSceneMat で解放。
static cv::Mat gPreparedSceneMat;

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
    // レガシー: 1 monster あたり 1 バリアントとしてグループ化して格納する
    gCachedMonsterTemplateGroups.clear();
    gCachedMonsterTemplateGroups.reserve(templates.count);
    for (UIImage *img in templates) {
        std::vector<cv::Mat> group;
        group.push_back([self cvMatFromUIImage:img]);
        gCachedMonsterTemplateGroups.push_back(std::move(group));
    }
}

+ (void)cacheMonsterTemplatesGrouped:(NSArray<NSArray<UIImage *> *> *)templateGroups {
    // monster ごとに複数スケールバリアントをキャッシュ。
    // ランタイムの runtime monster matching が SE3 等の機種で適切な倍率を捕捉できるようにする。
    gCachedMonsterTemplateGroups.clear();
    gCachedMonsterTemplateGroups.reserve(templateGroups.count);
    for (NSArray<UIImage *> *group in templateGroups) {
        std::vector<cv::Mat> mats;
        mats.reserve(group.count);
        for (UIImage *img in group) {
            mats.push_back([self cvMatFromUIImage:img]);
        }
        gCachedMonsterTemplateGroups.push_back(std::move(mats));
    }
}

+ (double)findBestMonsterMatchUsingCache:(UIImage *)roi {
    if (gCachedMonsterTemplateGroups.empty()) {
        return 0.0;
    }
    cv::Mat roiMat = [self cvMatFromUIImage:roi];
    Nakamon::NakamonAnalyzerCore::normalizeImage(roiMat);
    // 全 monster × 全バリアントで最高スコアを取る
    double bestScore = 0.0;
    for (const auto& group : gCachedMonsterTemplateGroups) {
        for (const auto& tpl : group) {
            if (tpl.cols > roiMat.cols || tpl.rows > roiMat.rows) continue;
            cv::Mat result;
            cv::matchTemplate(roiMat, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > bestScore) bestScore = maxVal;
        }
    }
    return bestScore;
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

+ (NakamonMatchLocation *)findBestMatchLocationInScene:(UIImage *)scene
                                            templateImg:(UIImage *)templateImg {
    cv::Mat sceneMat = [self cvMatFromUIImage:scene];
    cv::Mat tplMat = [self cvMatFromUIImage:templateImg];
    if (sceneMat.empty() || tplMat.empty() ||
        tplMat.cols > sceneMat.cols || tplMat.rows > sceneMat.rows) {
        return [[NakamonMatchLocation alloc] initWithCenterX:0 centerY:0 score:0];
    }
    cv::Mat result;
    cv::matchTemplate(sceneMat, tplMat, result, cv::TM_CCOEFF_NORMED);
    double maxVal = 0;
    cv::Point maxLoc;
    cv::minMaxLoc(result, nullptr, &maxVal, nullptr, &maxLoc);
    // maxLoc は scene 内のテンプレート左上座標。中心に変換して返す
    CGFloat cx = maxLoc.x + tplMat.cols * 0.5;
    CGFloat cy = maxLoc.y + tplMat.rows * 0.5;
    return [[NakamonMatchLocation alloc] initWithCenterX:cx centerY:cy score:maxVal];
}

+ (NSArray<NakamonMatchLocation *> *)findTopKMatchesInScene:(UIImage *)scene
                                                templateImg:(UIImage *)templateImg
                                                          k:(int)k
                                       suppressHalfWidth:(int)halfW
                                      suppressHalfHeight:(int)halfH {
    NSMutableArray<NakamonMatchLocation *> *results = [NSMutableArray array];
    cv::Mat sceneMat = [self cvMatFromUIImage:scene];
    cv::Mat tplMat = [self cvMatFromUIImage:templateImg];
    if (sceneMat.empty() || tplMat.empty() ||
        tplMat.cols > sceneMat.cols || tplMat.rows > sceneMat.rows ||
        k <= 0) {
        return results;
    }
    cv::Mat result;
    cv::matchTemplate(sceneMat, tplMat, result, cv::TM_CCOEFF_NORMED);

    for (int i = 0; i < k; i++) {
        double maxVal = 0;
        cv::Point maxLoc;
        cv::minMaxLoc(result, nullptr, &maxVal, nullptr, &maxLoc);
        CGFloat cx = maxLoc.x + tplMat.cols * 0.5;
        CGFloat cy = maxLoc.y + tplMat.rows * 0.5;
        [results addObject:[[NakamonMatchLocation alloc] initWithCenterX:cx centerY:cy score:maxVal]];

        // 周辺を抑制 (次の minMaxLoc が同じ位置を返さないように)
        int x0 = std::max(0, maxLoc.x - halfW);
        int y0 = std::max(0, maxLoc.y - halfH);
        int x1 = std::min(result.cols, maxLoc.x + halfW);
        int y1 = std::min(result.rows, maxLoc.y + halfH);
        if (x1 > x0 && y1 > y0) {
            result(cv::Rect(x0, y0, x1 - x0, y1 - y0)).setTo(cv::Scalar(-1.0));
        }
    }
    return results;
}

+ (NakamonSlotMatch *)bestMonsterLocationInRegion:(UIImage *)scene
                                          centerX:(int)centerX
                                          centerY:(int)centerY
                                            width:(int)width
                                           height:(int)height {
    if (gCachedMonsterTemplateGroups.empty()) {
        return [[NakamonSlotMatch alloc] initWithCenterX:0 centerY:0 score:0 index:-1];
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
    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);

    double bestScore = -1.0;
    int bestIdx = -1;
    cv::Point bestLoc(0, 0);
    int bestTplW = 0, bestTplH = 0;
    for (size_t m = 0; m < gCachedMonsterTemplateGroups.size(); ++m) {
        const auto& group = gCachedMonsterTemplateGroups[m];
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal = 0;
            cv::Point maxLoc;
            cv::minMaxLoc(result, nullptr, &maxVal, nullptr, &maxLoc);
            if (maxVal > bestScore) {
                bestScore = maxVal;
                bestIdx = (int)m;  // monster index (not variant index)
                bestLoc = maxLoc;
                bestTplW = tpl.cols;
                bestTplH = tpl.rows;
            }
        }
    }
    // ROI 内 left-top → scene 内 center に変換
    CGFloat sceneX = roiRect.x + bestLoc.x + bestTplW * 0.5;
    CGFloat sceneY = roiRect.y + bestLoc.y + bestTplH * 0.5;
    return [[NakamonSlotMatch alloc] initWithCenterX:sceneX
                                              centerY:sceneY
                                                score:bestScore
                                                index:bestIdx];
}

+ (NSArray<NakamonMatchResult *> *)topKMonstersInRegion:(UIImage *)scene
                                                centerX:(int)centerX
                                                centerY:(int)centerY
                                                  width:(int)width
                                                 height:(int)height
                                                   topK:(int)topK {
    if (gCachedMonsterTemplateGroups.empty() || topK <= 0) {
        return @[];
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
    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);

    // monster ごとに全バリアントを試し、その monster のベストスコアを記録
    std::vector<std::pair<double, int>> scored;
    scored.reserve(gCachedMonsterTemplateGroups.size());
    for (size_t m = 0; m < gCachedMonsterTemplateGroups.size(); ++m) {
        const auto& group = gCachedMonsterTemplateGroups[m];
        double monsterBest = -1.0;
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > monsterBest) monsterBest = maxVal;
        }
        if (monsterBest >= 0.0) {
            scored.emplace_back(monsterBest, (int)m);
        }
    }
    int k = std::min(topK, (int)scored.size());
    if (k <= 0) return @[];
    std::partial_sort(scored.begin(), scored.begin() + k, scored.end(),
                      [](const std::pair<double,int>& a,
                         const std::pair<double,int>& b) { return a.first > b.first; });
    NSMutableArray<NakamonMatchResult *> *results = [NSMutableArray arrayWithCapacity:k];
    for (int i = 0; i < k; i++) {
        [results addObject:[[NakamonMatchResult alloc] initWithScore:scored[i].first
                                                                index:scored[i].second]];
    }
    return results;
}

+ (NakamonMatchResult *)bestMonsterInRegion:(UIImage *)scene
                                    centerX:(int)centerX
                                    centerY:(int)centerY
                                      width:(int)width
                                     height:(int)height
                            templateIndices:(NSArray<NSNumber *> *)indices {
    if (gCachedMonsterTemplateGroups.empty() || indices.count == 0) {
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
    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);

    double bestScore = -1.0;
    int bestIdx = -1;
    // indices は monster index (group index) を指す。各 monster の全バリアントを試す。
    for (NSNumber *num in indices) {
        int m = num.intValue;
        if (m < 0 || m >= (int)gCachedMonsterTemplateGroups.size()) continue;
        const auto& group = gCachedMonsterTemplateGroups[m];
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > bestScore) {
                bestScore = maxVal;
                bestIdx = m;
            }
        }
    }
    return [[NakamonMatchResult alloc] initWithScore:bestScore index:bestIdx];
}

+ (NakamonMatchResult *)bestMonsterAndSaveInRegion:(UIImage *)scene
                                            centerX:(int)centerX
                                            centerY:(int)centerY
                                              width:(int)width
                                             height:(int)height
                                           savePath:(NSString *)savePath {
    if (gCachedMonsterTemplateGroups.empty()) {
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
    // monster ごとに全バリアントを試し、最高スコアの monster index を返す
    double bestScore = -1.0;
    int bestIdx = -1;
    for (size_t m = 0; m < gCachedMonsterTemplateGroups.size(); ++m) {
        const auto& group = gCachedMonsterTemplateGroups[m];
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > bestScore) {
                bestScore = maxVal;
                bestIdx = (int)m;
            }
        }
    }
    return [[NakamonMatchResult alloc] initWithScore:bestScore index:bestIdx];
}

#pragma mark - Prepared-scene fast path (Phase 2.1: per-frame Mat caching)

+ (void)prepareSceneMat:(UIImage *)scene {
    gPreparedSceneMat = [self cvMatFromUIImage:scene];
}

+ (void)clearPreparedSceneMat {
    gPreparedSceneMat.release();
}

/**
 * gPreparedSceneMat を使って ROI 内の monster matching を行う共通ロジック。
 * indices が nil の場合は全 monster を走査、そうでない場合は指定 monster indices のみ走査。
 */
+ (NakamonMatchResult *)_bestMonsterInPreparedRoiAtCenterX:(int)centerX
                                                     centerY:(int)centerY
                                                       width:(int)width
                                                      height:(int)height
                                              templateIndices:(NSArray<NSNumber *> *)indices {
    if (gCachedMonsterTemplateGroups.empty() || gPreparedSceneMat.empty()) {
        return [[NakamonMatchResult alloc] initWithScore:0.0 index:-1];
    }
    int imgW = gPreparedSceneMat.cols;
    int imgH = gPreparedSceneMat.rows;
    int w = std::min(width, imgW);
    int h = std::min(height, imgH);
    int left = std::max(0, std::min(centerX - w / 2, imgW - w));
    int top  = std::max(0, std::min(centerY - h / 2, imgH - h));
    cv::Rect roiRect(left, top, w, h);
    cv::Mat workRoi;
    gPreparedSceneMat(roiRect).copyTo(workRoi);
    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);

    double bestScore = -1.0;
    int bestIdx = -1;
    // 指定インデックス集合を作る (indices == nil の場合は全 monster)
    std::vector<int> monsterIdxToEval;
    if (indices == nil) {
        monsterIdxToEval.reserve(gCachedMonsterTemplateGroups.size());
        for (int m = 0; m < (int)gCachedMonsterTemplateGroups.size(); ++m) {
            monsterIdxToEval.push_back(m);
        }
    } else {
        monsterIdxToEval.reserve(indices.count);
        for (NSNumber *num in indices) monsterIdxToEval.push_back(num.intValue);
    }
    for (int monsterIdx : monsterIdxToEval) {
        if (monsterIdx < 0 || monsterIdx >= (int)gCachedMonsterTemplateGroups.size()) continue;
        const auto& group = gCachedMonsterTemplateGroups[monsterIdx];
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > bestScore) {
                bestScore = maxVal;
                bestIdx = monsterIdx;
            }
        }
    }
    return [[NakamonMatchResult alloc] initWithScore:bestScore index:bestIdx];
}

+ (NakamonMatchResult *)bestMonsterInPreparedSceneCenterX:(int)centerX
                                                   centerY:(int)centerY
                                                     width:(int)width
                                                    height:(int)height {
    return [self _bestMonsterInPreparedRoiAtCenterX:centerX
                                            centerY:centerY
                                              width:width
                                             height:height
                                    templateIndices:nil];
}

+ (NakamonMatchResult *)bestMonsterInPreparedSceneCenterX:(int)centerX
                                                   centerY:(int)centerY
                                                     width:(int)width
                                                    height:(int)height
                                           templateIndices:(NSArray<NSNumber *> *)indices {
    if (indices == nil || indices.count == 0) {
        return [[NakamonMatchResult alloc] initWithScore:0.0 index:-1];
    }
    return [self _bestMonsterInPreparedRoiAtCenterX:centerX
                                            centerY:centerY
                                              width:width
                                             height:height
                                    templateIndices:indices];
}

+ (NSArray<NakamonMatchResult *> *)topKMonstersInPreparedSceneCenterX:(int)centerX
                                                               centerY:(int)centerY
                                                                 width:(int)width
                                                                height:(int)height
                                                                  topK:(int)topK {
    if (gCachedMonsterTemplateGroups.empty() || gPreparedSceneMat.empty() || topK <= 0) {
        return @[];
    }
    int imgW = gPreparedSceneMat.cols;
    int imgH = gPreparedSceneMat.rows;
    int w = std::min(width, imgW);
    int h = std::min(height, imgH);
    int left = std::max(0, std::min(centerX - w / 2, imgW - w));
    int top  = std::max(0, std::min(centerY - h / 2, imgH - h));
    cv::Rect roiRect(left, top, w, h);
    cv::Mat workRoi;
    gPreparedSceneMat(roiRect).copyTo(workRoi);
    Nakamon::NakamonAnalyzerCore::normalizeImage(workRoi);

    std::vector<std::pair<double, int>> scored;
    scored.reserve(gCachedMonsterTemplateGroups.size());
    for (size_t m = 0; m < gCachedMonsterTemplateGroups.size(); ++m) {
        const auto& group = gCachedMonsterTemplateGroups[m];
        double monsterBest = -1.0;
        for (const auto& tpl : group) {
            if (tpl.cols > workRoi.cols || tpl.rows > workRoi.rows) continue;
            cv::Mat result;
            cv::matchTemplate(workRoi, tpl, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);
            if (maxVal > monsterBest) monsterBest = maxVal;
        }
        if (monsterBest >= 0.0) scored.emplace_back(monsterBest, (int)m);
    }
    int k = std::min(topK, (int)scored.size());
    if (k <= 0) return @[];
    std::partial_sort(scored.begin(), scored.begin() + k, scored.end(),
                      [](const std::pair<double,int>& a,
                         const std::pair<double,int>& b) { return a.first > b.first; });
    NSMutableArray<NakamonMatchResult *> *results = [NSMutableArray arrayWithCapacity:k];
    for (int i = 0; i < k; i++) {
        [results addObject:[[NakamonMatchResult alloc] initWithScore:scored[i].first
                                                                index:scored[i].second]];
    }
    return results;
}

@end
