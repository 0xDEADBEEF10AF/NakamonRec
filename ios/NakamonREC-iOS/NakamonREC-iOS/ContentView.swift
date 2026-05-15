import SwiftUI

struct MonsterData: Codable, Identifiable {
    var id: String { name }
    let name: String
    let fileName: String
}

struct ContentView: View {
    @State private var monsters: [MonsterData] = []

    var body: some View {
        NavigationView {
            List(monsters) { monster in
                HStack {
                    // モンスター画像の表示
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
            .onAppear {
                loadMonsters()
            }
        }
    }

    func loadImage(named fileName: String) -> UIImage? {
        // 青色フォルダ (Folder Reference) の場合、Bundle内でのパス指定が必要です
        // 1. ファイル名から拡張子を分離
        let name = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension

        // 2. "templates" フォルダの中にあるファイルを検索
        if let path = Bundle.main.path(forResource: name, ofType: ext, inDirectory: "templates") {
            return UIImage(contentsOfFile: path)
        }

        // 念のため、拡張子がない場合のフォールバック
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
