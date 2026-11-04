// SPDX-License-Identifier: GPL-3.0-only
//
// 解密链路验证：取一集 → 下载 → 改包解密 → 用 AVFoundation 读轨道与时长。
// 轨道能读出来、时长接近分集时长，就说明「改包 + 逐样本 AES-CTR」是对的。

import AVFoundation
import Foundation

@main
struct DecryptCheck {
    static func main() async {
        await ContentRepository.shared.bootstrap()
        do {
            let page = try await ContentRepository.shared.rank(page: 1, board: .comic)
            guard let first = page.items.first else { print("没有榜单数据"); return }
            let detail = try await ContentRepository.shared.detail(id: first.id)
            guard let vid = detail.episodes.first else { print("没有分集"); return }
            print("剧集 \(detail.series.title) · \(detail.episodes.count) 集 · vid=\(vid)")
            let info = try await ContentRepository.shared.stream(id: vid, maxQuality: 720)
            print("地址 \(info.quality)P，密钥 \(info.key.isEmpty ? "无" : "有")")
            let file = try await OfflineVideo.shared.prepare(vid: vid, quality: info.quality, info: info)
            let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
            print("解密文件 \(file.path) size=\(size)")

            let asset = AVURLAsset(url: file)
            let duration = try await asset.load(.duration)
            let videoTracks = try await asset.loadTracks(withMediaType: .video)
            let audioTracks = try await asset.loadTracks(withMediaType: .audio)
            print("AVFoundation: duration=\(String(format: "%.1f", duration.seconds))s video=\(videoTracks.count) audio=\(audioTracks.count)")
            if let track = videoTracks.first {
                let size = try await track.load(.naturalSize)
                print("视频轨 \(Int(size.width))x\(Int(size.height))")
            }
            print(videoTracks.isEmpty ? "❌ 没有视频轨：解密/改包仍有问题" : "✅ 可播放容器")
        } catch {
            print("失败：\(error.localizedDescription)")
        }
    }
}
