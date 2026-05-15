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
                    // 画像表示（後ほどアセットを追加した際に反映されます）
                    Image(systemName: "pawprint.circle.fill")
                        .foregroundColor(.blue)

                    V {
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
