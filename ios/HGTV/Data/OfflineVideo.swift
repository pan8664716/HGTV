// SPDX-License-Identifier: GPL-3.0-only
//
// 离线解密播放：平台下发的 MP4 是 CENC 加密的（`encv` + 逐样本 IV），
// AVFoundation 不接受这种容器，因此这里整集取回、改包、解密、落到缓存目录，
// 再交给 AVPlayer 播本地文件。
//
// 缓存按「集 id + 清晰度」命名，同一集重复进入直接命中；超过 1.2GB 时按时间清理。

import Foundation

actor OfflineVideo {
    static let shared = OfflineVideo()

    private let folder: URL
    private let limit: Int64 = 1_200 * 1024 * 1024

    init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("video", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    /// 已经解好的文件（同一集同一清晰度只解一次）。
    func cachedFile(vid: String, quality: Int) -> URL? {
        let url = fileURL(vid: vid, quality: quality)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// 下载并解密。`onProgress` 用于界面上那句「正在准备播放…」。
    func prepare(vid: String, quality: Int, info: StreamInfo) async throws -> URL {
        let target = fileURL(vid: vid, quality: quality)
        if FileManager.default.fileExists(atPath: target.path) { return target }

        var request = URLRequest(url: URL(string: info.url)!)
        request.timeoutInterval = 60
        request.setValue(Vendor.userAgent(Vendor.device, video: true), forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode < 400, data.count > 1024 else {
            throw ContentError.message("视频下载失败")
        }

        var bytes = [UInt8](data)
        if !info.key.isEmpty, let key = Mp4Crypto.deriveKey(info.key) {
            let prepared = Mp4Crypto.prepare(bytes, key: key)
            guard !prepared.samples.isEmpty else { throw ContentError.message("视频索引不可用") }
            bytes = Mp4Crypto.decrypt(bytes, key: key, prepared: prepared)
        }
        try Data(bytes).write(to: target, options: .atomic)
        trimIfNeeded()
        return target
    }

    private func fileURL(vid: String, quality: Int) -> URL {
        folder.appendingPathComponent("\(vid)-\(quality).mp4")
    }

    private func trimIfNeeded() {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])) ?? []
        var total: Int64 = 0
        var entries: [(URL, Date, Int64)] = []
        for file in files {
            let values = try? file.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            let size = Int64(values?.fileSize ?? 0)
            total += size
            entries.append((file, values?.contentModificationDate ?? .distantPast, size))
        }
        guard total > limit else { return }
        for entry in entries.sorted(by: { $0.1 < $1.1 }) {
            try? FileManager.default.removeItem(at: entry.0)
            total -= entry.2
            if total <= limit { break }
        }
    }
}
