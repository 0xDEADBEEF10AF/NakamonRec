import ReplayKit
import VideoToolbox

class SampleHandler: RPBroadcastSampleHandler {

    // 解析の間隔調整用
    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5 // 0.5秒おきに解析を試みる

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        // 配信開始時の処理
        print("NakamonREC: Broadcast Started")
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleType: RPSampleBufferType) {
        switch sampleType {
        case .video:
            // 映像データが届いた！
            handleVideoSample(sampleBuffer)
        default:
            break
        }
    }

    private func handleVideoSample(_ sampleBuffer: CMSampleBuffer) {
        let currentTime = CACurrentMediaTime()
        guard currentTime - lastProcessTime >= processInterval else { return }
        lastProcessTime = currentTime

        // 1. CMSampleBuffer を UIImage に変換
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return }
        let uiImage = UIImage(cgImage: cgImage)

        // 2. 解析エンジン (C++ Wrapper) を呼び出す
        // ※ ここで Android と同じように VSロゴ検知 -> モンスター解析 を行う
        print("NakamonREC: Processing frame... Size: \(uiImage.size)")

        // TODO: ここに NakamonWrapper.performMatch... を実装していく
        // 例: let score = NakamonWrapper.performMatch(withScene: uiImage, ...)
    }
    
    override func broadcastPaused() {
        print("NakamonREC: Broadcast Paused")
    }
    
    override func broadcastResumed() {
        print("NakamonREC: Broadcast Resumed")
    }
    
    override func broadcastFinished() {
        print("NakamonREC: Broadcast Finished")
    }
}
