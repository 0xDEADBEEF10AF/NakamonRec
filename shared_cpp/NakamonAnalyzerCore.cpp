#include "NakamonAnalyzerCore.h"
#include <algorithm>

namespace Nakamon {

double NakamonAnalyzerCore::performColorMatch(
    const cv::Mat& scene,
    const cv::Mat& templateMat,
    int centerX, int centerY,
    int verticalMargin, int horizontalMargin
) {
    if (scene.empty() || templateMat.empty()) return 0.0;

    int imgW = scene.cols;
    int imgH = scene.rows;

    // テンプレートが画像より大きい場合は失敗
    if (templateMat.cols > imgW || templateMat.rows > imgH) return 0.0;

    // 探索範囲の設定 (テンプレートサイズ + マージン)
    int roiW = std::min(templateMat.cols + horizontalMargin * 2, imgW);
    int roiH = std::min(templateMat.rows + verticalMargin * 2, imgH);

    int left = std::max(0, std::min(centerX - roiW / 2, imgW - roiW));
    int top = std::max(0, std::min(centerY - roiH / 2, imgH - roiH));

    // サブマトリクスの切り出し
    cv::Rect roiRect(left, top, roiW, roiH);
    // 念のための境界チェック
    if ((roiRect.x + roiRect.width) > imgW) roiRect.width = imgW - roiRect.x;
    if ((roiRect.y + roiRect.height) > imgH) roiRect.height = imgH - roiRect.y;

    if (roiRect.width < templateMat.cols || roiRect.height < templateMat.rows) return 0.0;

    cv::Mat roi = scene(roiRect);
    cv::Mat result;

    // テンプレートマッチング実行
    cv::matchTemplate(roi, templateMat, result, cv::TM_CCOEFF_NORMED);

    double maxVal;
    cv::minMaxLoc(result, nullptr, &maxVal);

    return maxVal;
}

MatchResult NakamonAnalyzerCore::findBestMatchWithScales(
    const cv::Mat& roi,
    const std::vector<cv::Mat>& scaledTemplates
) {
    double bestScore = -1.0;
    int bestIdx = -1;

    for (size_t i = 0; i < scaledTemplates.size(); ++i) {
        const auto& tpl = scaledTemplates[i];

        // テンプレートがROIに収まる場合のみマッチング
        if (tpl.cols <= roi.cols && tpl.rows <= roi.rows) {
            cv::Mat result;
            cv::matchTemplate(roi, tpl, result, cv::TM_CCOEFF_NORMED);

            double maxVal;
            cv::minMaxLoc(result, nullptr, &maxVal);

            if (maxVal > bestScore) {
                bestScore = maxVal;
                bestIdx = static_cast<int>(i);
            }
        }
    }

    return MatchResult(bestScore, bestIdx);
}

void NakamonAnalyzerCore::normalizeImage(cv::Mat& ioMat) {
    if (ioMat.empty()) return;
    // Android版の Core.normalize(roi, roi, 0.0, 255.0, Core.NORM_MINMAX) と同一の処理
    cv::normalize(ioMat, ioMat, 0.0, 255.0, cv::NORM_MINMAX);
}

} // namespace Nakamon
