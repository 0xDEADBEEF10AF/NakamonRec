import SwiftUI
import ReplayKit

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
                    .frame(width: 200, height: 60)

                Spacer()

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

/// RPSystemBroadcastPickerView をラップして、自前 Extension のみを候補に出す REC ボタン
struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = "com.android.NakamonREC-iOS.NakamonREC-ScreenCapture"
        picker.showsMicrophoneButton = false

        // デフォルトのシステムボタン見た目を REC 風にカスタマイズ
        if let button = picker.subviews.compactMap({ $0 as? UIButton }).first {
            button.imageView?.tintColor = .white
            button.backgroundColor = UIColor.systemRed
            button.layer.cornerRadius = 12
            button.setTitle("  REC  ", for: .normal)
            button.titleLabel?.font = .boldSystemFont(ofSize: 22)
            button.setTitleColor(.white, for: .normal)
        }
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
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
