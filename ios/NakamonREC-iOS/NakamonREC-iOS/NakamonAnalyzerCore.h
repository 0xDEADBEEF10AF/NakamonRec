#ifndef NAKAMON_ANALYZER_CORE_H
#define NAKAMON_ANALYZER_CORE_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>
#include <string>

namespace Nakamon {

/**
 * 検索結果を保持する構造体
 */
struct MatchResult {
    double score;
    int bestScaleIndex;

    MatchResult() : score(-1.0), bestScaleIndex(-1) {}
    MatchResult(double s, int idx) : score(s), bestScaleIndex(idx) {}
};

class NakamonAnalyzerCore {
public:
    /**
     * 指定されたROI領域でテンプレートマッチングを行い、最高スコアを返します。
     * Android版の performColorMatchCached に相当。
     */
    static double performColorMatch(
        const cv::Mat& scene,
        const cv::Mat& templateMat,
        int centerX, int centerY,
        int verticalMargin, int horizontalMargin
    );

    /**
     * 1つのモンスターに対してマルチスケール（0.9〜1.1）でマッチングを行い、
     * 最も良かった結果を返します。
     */
    static MatchResult findBestMatchWithScales(
        const cv::Mat& roi,
        const std::vector<cv::Mat>& scaledTemplates
    );

    /**
     * 画像のコントラストを正規化します（GALAXY対策）。
     * Android版の Core.normalize に相当。
     */
    static void normalizeImage(cv::Mat& ioMat);
};

} // namespace Nakamon

#endif // NAKAMON_ANALYZER_CORE_H
