import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// CSV エクスポートで一時ファイルを生成し、UIActivityViewController で共有する SwiftUI ラッパー
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// .csv ファイルを開くドキュメントピッカー (CSV インポート用)
struct DocumentImportPicker: UIViewControllerRepresentable {
    var allowedContentTypes: [UTType] = [.commaSeparatedText, .plainText]
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: allowedContentTypes,
                                                   asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {}
    }
}

/// CSV ファイルを一時ディレクトリに書き出して URL を返す。
/// ファイル名は `<baseName>_yyyyMMdd.csv` 形式 (Android 互換)
@discardableResult
func writeCSVToTempFile(content: String, baseName: String) -> URL? {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone.current
    formatter.dateFormat = "yyyyMMdd"
    let fileName = "\(baseName)_\(formatter.string(from: Date())).csv"
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
    // UTF-8 with BOM (Excel で文字化けしないように)
    var data = Data([0xEF, 0xBB, 0xBF])
    data.append(content.data(using: .utf8) ?? Data())
    do {
        try data.write(to: url, options: .atomic)
        return url
    } catch {
        NSLog("writeCSVToTempFile failed: \(error)")
        return nil
    }
}
