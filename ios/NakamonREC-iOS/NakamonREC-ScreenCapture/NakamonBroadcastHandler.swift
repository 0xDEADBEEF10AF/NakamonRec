//
//  NakamonBroadcastHandler.swift
//  NakamonREC-ScreenCapture
//
//  Broadcast Upload Extension (iOS 26 以前の経路) の入口。ReplayKit のライフサイクルを
//  受け取り、解析本体 NakamonCaptureEngine (ホストアプリと共有) に転送するだけの薄い殻。
//  iOS 27 以降はホストアプリ内の ScreenCaptureKit 経路が同じエンジンを駆動する。
//  Info.plist の NSExtensionPrincipalClass はこのクラスを指す。
//

import ReplayKit
import NakamonREC_Shared

class NakamonBroadcastHandler: RPBroadcastSampleHandler {

    private let engine = NakamonCaptureEngine(source: .broadcastExtension)

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        engine.start()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleType: RPSampleBufferType) {
        guard sampleType == .video else { return }
        engine.process(sampleBuffer)
    }

    override func broadcastPaused() {
        engine.pause()
    }

    override func broadcastResumed() {
        engine.resume()
    }

    override func broadcastFinished() {
        engine.finish()
    }
}
