import SwiftUI
import ReplayKit
import OSLog
import NakamonREC_Shared

private let logger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "Host")

struct MonsterData: Codable, Identifiable {
    var id: String { name }
    let name: String
    let fileName: String
}

struct ContentView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                Image(systemName: "record.circle.fill")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .foregroundColor(.red)

                Text("NakamonREC")
                    .font(.largeTitle)
                    .bold()

                Text("RECボタンをタップして画面収録を開始します")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                BroadcastButton()
                    .frame(width: 220, height: 64)

                Spacer()

                NavigationLink {
                    BattleLogViewerView()
                } label: {
                    Label("直近の解析ログ", systemImage: "doc.text.magnifyingglass")
                        .font(.footnote)
                }

                NavigationLink {
                    MonsterListView()
                } label: {
                    Label("なかまモンスター一覧 (テスト)", systemImage: "list.bullet")
                        .font(.footnote)
                }
                .padding(.bottom)
            }
            .padding()
        }
    }
}

/// 自前の REC ボタン UI + 非表示の RPSystemBroadcastPickerView で構成。
/// SwiftUI ボタンのタップで PickerView 内部の UIButton を sendActions で発火させる。
struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear

        // 非表示の RPSystemBroadcastPickerView (実際のピッカー起動を担当)
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = "com.android.NakamonREC-iOS.NakamonREC-ScreenCapture"
        picker.showsMicrophoneButton = false
        picker.translatesAutoresizingMaskIntoConstraints = false
        picker.isHidden = true
        container.addSubview(picker)

        // 見た目用の REC ボタン (前面に表示しタップを受ける)
        let recButton = UIButton(type: .system)
        recButton.translatesAutoresizingMaskIntoConstraints = false
        recButton.setTitle("● REC", for: .normal)
        recButton.titleLabel?.font = .boldSystemFont(ofSize: 24)
        recButton.setTitleColor(.white, for: .normal)
        recButton.backgroundColor = .systemRed
        recButton.layer.cornerRadius = 12
        container.addSubview(recButton)

        NSLayoutConstraint.activate([
            recButton.topAnchor.constraint(equalTo: container.topAnchor),
            recButton.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            recButton.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            recButton.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        ])

        context.coordinator.picker = picker
        recButton.addTarget(context.coordinator,
                            action: #selector(Coordinator.tap),
                            for: .touchUpInside)

        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject {
        weak var picker: RPSystemBroadcastPickerView?

        @objc func tap() {
            logger.log("REC button tapped")
            guard let picker else { return }
            // PickerView の内部 UIButton を再帰的に探して発火
            if let innerButton = findButton(in: picker) {
                innerButton.sendActions(for: .touchUpInside)
            } else {
                logger.error("Broadcast picker inner button not found")
            }
        }

        private func findButton(in view: UIView) -> UIButton? {
            if let b = view as? UIButton { return b }
            for sub in view.subviews {
                if let b = findButton(in: sub) { return b }
            }
            return nil
        }
    }
}

/// テスト用のなかまモンスター一覧 (元の ContentView の内容)
struct MonsterListView: View {
    @State private var monsters: [MonsterData] = []

    var body: some View {
        List(monsters) { monster in
            HStack {
                if let uiImage = loadImage(named: monster.fileName) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 40, height: 40)
                        .cornerRadius(4)
                } else {
                    Image(systemName: "pawprint.circle.fill")
                        .resizable()
                        .frame(width: 40, height: 40)
                        .foregroundColor(.blue)
                }

                VStack(alignment: .leading) {
                    Text(monster.name)
                        .font(.headline)
                    Text(monster.fileName)
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
        }
        .navigationTitle("なかまモンスター一覧")
        .onAppear { loadMonsters() }
    }

    func loadImage(named fileName: String) -> UIImage? {
        let name = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension

        if let path = Bundle.main.path(forResource: name, ofType: ext, inDirectory: "templates") {
            return UIImage(contentsOfFile: path)
        }
        if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") {
            return UIImage(contentsOfFile: path)
        }
        print("Image not found: templates/\(fileName)")
        return nil
    }

    func loadMonsters() {
        guard let url = Bundle.main.url(forResource: "monsters", withExtension: "json") else {
            print("JSON file not found")
            return
        }
        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            self.monsters = try decoder.decode([MonsterData].self, from: data)
        } catch {
            print("Failed to decode JSON: \(error)")
        }
    }
}

#Preview {
    ContentView()
}
