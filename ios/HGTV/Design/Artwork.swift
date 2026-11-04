// SPDX-License-Identifier: GPL-3.0-only
//
// 封面加载：内存 LRU + 磁盘缓存（与 Android 的 `ArtworkLoader` 同一套策略：
// 先读内存，再读磁盘，最后走网络；失败不缓存，占位保持井底色）。

import SwiftUI
import UIKit

actor ArtworkLoader {
    static let shared = ArtworkLoader()

    private let memory = NSCache<NSString, UIImage>()
    private let folder: URL

    init() {
        memory.countLimit = 160
        memory.totalCostLimit = 96 * 1024 * 1024
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("artwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    func image(url: String) async -> UIImage? {
        guard !url.isEmpty, let link = URL(string: url) else { return nil }
        let key = url as NSString
        if let hit = memory.object(forKey: key) { return hit }

        let file = folder.appendingPathComponent(Self.fileName(url))
        if let data = try? Data(contentsOf: file), let image = UIImage(data: data) {
            memory.setObject(image, forKey: key, cost: data.count)
            return image
        }

        var request = URLRequest(url: link)
        request.setValue("https://www.fanqienovel.com/", forHTTPHeaderField: "Referer")
        request.timeoutInterval = 15
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode ?? 0 < 400,
              let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key, cost: data.count)
        try? data.write(to: file, options: .atomic)
        return image
    }

    private static func fileName(_ url: String) -> String {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in url.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return String(hash, radix: 16)
    }
}

struct ArtworkView: View {
    let url: String
    var contentMode: ContentMode = .fill

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.clear
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            }
        }
        .task(id: url) {
            image = await ArtworkLoader.shared.image(url: url)
        }
        .animation(.easeOut(duration: 0.18), value: image != nil)
    }
}
