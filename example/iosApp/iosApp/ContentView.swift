import SwiftUI
import Shared

/// Native SwiftUI screen listing every custom SF Symbol SymbolCraft generated from the icon
/// declaration in `shared/build.gradle.kts`. `GeneratedSymbol` and `Image(symbol:)` come from the
/// generated `Symbols.swift`; the glyphs come from the generated `SymbolCraft.xcassets`.
struct ContentView: View {
    var body: some View {
        NavigationStack {
            List(GeneratedSymbol.allCases, id: \.self) { symbol in
                HStack(spacing: 16) {
                    Image(symbol: symbol)
                        .font(.title)
                        .foregroundStyle(.tint)
                        .frame(width: 44)
                    VStack(alignment: .leading) {
                        Text(symbol.rawValue)
                        Text("Image(symbol: .\(String(describing: symbol)))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    // Fixed 24x24 pt box, independent of the surrounding font size.
                    symbol.image(boxSize: 24)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(Greeting().greet())
        }
    }
}

#Preview {
    ContentView()
}
