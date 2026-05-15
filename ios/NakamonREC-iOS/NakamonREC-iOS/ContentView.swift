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
        // 画像は "templates/XXX" という名前で読み込む
        // iOSではファイル名から拡張子を除いた名前を指定するのが一般的です
        let nameWithoutExt = (fileName as NSString).deletingPathExtension
        return UIImage(named: "templates/\(nameWithoutExt)")
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
