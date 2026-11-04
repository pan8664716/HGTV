// SPDX-License-Identifier: GPL-3.0-only
//
// 设置页：四大项分页（播放交互 / 外观导航 / 内容推荐 / 系统高级）。
//
// 与 Android `PhoneSettingsActivity` 的项一一对应，写的是同一份偏好：
// 默认清晰度、倍速、画面模式、画面缩放、自动连播、快进快退、播放显示四项、
// 自动消失秒数、弹幕六项、20 套主题、字体大小、列表每行、推荐页左右滑动、
// 推荐范围、受众、打开应用进入推荐、选集每页、搜索源、恢复隐藏、
// 减少动画、调试模式、备份 / 恢复、清空缓存、恢复默认。

import SwiftUI
import UniformTypeIdentifiers

enum SettingsPage: String, CaseIterable, Hashable {
    case playback, appearance, recommend, system

    var label: String {
        switch self {
        case .playback: return "播放交互"
        case .appearance: return "外观导航"
        case .recommend: return "内容推荐"
        case .system: return "系统高级"
        }
    }

    var hint: String {
        switch self {
        case .playback: return "播放与弹幕"
        case .appearance: return "主题与列表"
        case .recommend: return "推荐与来源"
        case .system: return "数据与性能"
        }
    }
}

struct SettingsView: View {
    let initialPage: SettingsPage

    @ObservedObject private var library = Library.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme
    @Environment(\.safeAreaInsets) private var insets

    @State private var page: SettingsPage
    @State private var showImporter = false
    @State private var exportFile: URL?
    @State private var confirmReset = false
    @State private var confirmDefaults = false
    @State private var confirmUnhide = false

    init(initialPage: SettingsPage) {
        self.initialPage = initialPage
        _page = State(initialValue: initialPage)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    pageSelector
                    switch page {
                    case .playback: playback
                    case .appearance: appearance
                    case .recommend: recommend
                    case .system: system
                    }
                    Text("HGTV  ·  收藏与进度保存在本机")
                        .font(.system(size: m.sp(11)))
                        .foregroundStyle(theme.textFaint)
                        .padding(.top, m.dp(22))
                        .padding(.leading, m.dp(6))
                    Spacer(minLength: m.dp(30) + insets.bottom)
                }
                .padding(.horizontal, m.dp(16))
                .padding(.top, m.dp(2))
            }
            .scrollIndicators(.hidden)
        }
        .background(theme.baseColor.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .alert("清空应用缓存", isPresented: $confirmReset) {
            Button("取消", role: .cancel) {}
            Button("清空", role: .destructive) {
                library.resetAll()
                ToastCenter.shared.show("已恢复成刚安装的样子")
            }
        } message: {
            Text("本机的收藏、观看记录、搜索历史与设置会一并清空，且无法找回。如需保留，请先导出备份。")
        }
        .alert("恢复默认设置", isPresented: $confirmDefaults) {
            Button("取消", role: .cancel) {}
            Button("恢复", role: .destructive) {
                library.restoreDefaultPrefs()
                ToastCenter.shared.show("已恢复默认设置")
            }
        } message: {
            Text("把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。")
        }
        .alert("恢复隐藏的剧集", isPresented: $confirmUnhide) {
            Button("取消", role: .cancel) {}
            Button("恢复") {
                library.unhideAll()
                ToastCenter.shared.show("已恢复")
            }
        } message: {
            Text("把长按菜单里隐藏过的 \(library.hidden().count) 部剧全部放回列表？")
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), library.importBackup(data) {
                ToastCenter.shared.show("记录已合并恢复")
            } else {
                ToastCenter.shared.show("备份格式不对")
            }
        }
        .sheet(item: Binding(get: { exportFile.map(ExportFile.init) }, set: { exportFile = $0?.url })) { file in
            ShareSheet(items: [file.url])
        }
    }

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: m.sp(16), weight: .semibold))
                    .foregroundStyle(theme.text)
                    .frame(width: m.dp(34), height: m.dp(34))
            }
            .buttonStyle(.plain)
            Text("设置")
                .font(.system(size: m.sp(19), weight: .bold))
                .foregroundStyle(theme.text)
            Spacer()
        }
        .padding(.horizontal, m.dp(10))
        .padding(.top, insets.top + m.dp(6))
        .padding(.bottom, m.dp(8))
    }

    private var pageSelector: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: m.dp(6)), GridItem(.flexible(), spacing: m.dp(6))],
                  spacing: m.dp(6)) {
            ForEach(SettingsPage.allCases, id: \.self) { option in
                Button {
                    page = option
                } label: {
                    Text(option.label)
                        .font(.system(size: m.sp(14)))
                        .foregroundStyle(page == option ? theme.accent : theme.text)
                        .frame(maxWidth: .infinity)
                        .frame(height: m.dp(42))
                        .soft(page == option ? .inset : .raised, fill: theme.surface,
                              radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(3)))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.bottom, m.dp(4))
    }

    // MARK: 一、播放交互

    private var playback: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "播放偏好")

            ChoiceRow(label: "默认清晰度", value: "\(library.prefs.maxQuality)P",
                      options: PlaybackQuality.options.map { value in
                          ("\(value) P", { library.prefs.maxQuality = value })
                      })

            ChoiceRow(label: "默认倍速", value: PlaybackSpeed.label(library.prefs.playbackSpeed),
                      options: PlaybackSpeed.options.map { value in
                          (PlaybackSpeed.label(value), { library.prefs.playbackSpeed = value })
                      })

            ChoiceRow(label: "画面模式", value: library.prefs.frameMode.label,
                      options: VideoFrameMode.allCases.map { value in
                          (value.label, { library.prefs.frameMode = value })
                      })

            // iOS 只走系统解码（AVFoundation 不暴露「换软解」这一档），这里如实说明。
            SettingRow(label: "视频解码", value: "系统解码")

            SliderRow(label: "画面缩放", value: Binding(
                get: { Double(library.prefs.videoScale) },
                set: { library.prefs.videoScale = Float($0) }
            ), range: 1...100, step: 1, text: { "\(Int($0))%" })

            SwitchRow(label: "自动连播", isOn: $library.prefs.autoNext)

            SliderRow(label: "快进快退", value: Binding(
                get: { Double(library.prefs.seekStep) },
                set: { library.prefs.seekStep = Int($0) }
            ), range: 2...20, step: 1, text: { "\(Int($0)) 秒" })

            SectionTitle(text: "播放显示")
            HintText(text: "画面左上角常驻的几项信息，默认全关。")
            SwitchRow(label: "剧集标题", isOn: $library.prefs.playerTitle)
            SwitchRow(label: "当前集数", isOn: $library.prefs.playerEpisode)
            SwitchRow(label: "当前时间", isOn: $library.prefs.playerClock)
            SwitchRow(label: "网速", isOn: $library.prefs.playerSpeed)

            SectionTitle(text: "播放界面")
            SliderRow(label: "自动消失", value: Binding(
                get: { Double(library.prefs.playerPanelTimeout) },
                set: { library.prefs.playerPanelTimeout = Int($0) }
            ), range: 2...15, step: 1, text: { "\(Int($0)) 秒" })
            HintText(text: "播放界面上的控制条与面板无操作后自动收起，点一下画面恢复。")

            SectionTitle(text: "弹幕")
            HintText(text: "显示方式与速度共用电视端设置；字号按手机阅读单独调整。")
            ChoiceRow(label: "显示方式", value: library.prefs.danmakuMode.label,
                      options: DanmakuMode.allCases.map { value in
                          (value.label, { library.prefs.danmakuMode = value })
                      })
            ChoiceRow(label: "弹幕速度", value: library.prefs.danmakuSpeed.label,
                      options: DanmakuSpeed.allCases.map { value in
                          (value.label, { library.prefs.danmakuSpeed = value })
                      })
            SwitchRow(label: "相同弹幕聚合", isOn: $library.prefs.danmakuMerge)
            SliderRow(label: "聚合条数", value: Binding(
                get: { Double(library.prefs.danmakuMergeMin) },
                set: { library.prefs.danmakuMergeMin = Int($0) }
            ), range: 2...10, step: 1, text: { "\(Int($0)) 条" })
            SliderRow(label: "弹幕字体大小", value: Binding(
                get: { Double(library.prefs.danmakuSizePercent) },
                set: { library.prefs.danmakuSizePercent = Int($0) }
            ), range: 50...150, step: 5, text: { "\(Int($0))%" })
            SliderRow(label: "弹幕透明度", value: Binding(
                get: { Double(library.prefs.danmakuAlpha) },
                set: { library.prefs.danmakuAlpha = Float($0) }
            ), range: 0.2...1, step: 0.05, text: { "\(Int($0 * 100))%" })
        }
    }

    // MARK: 二、外观导航

    private var appearance: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "主题外观")
            HintText(text: "当前：\(PhoneTheme.of(library.prefs.theme).label) · \(PhoneTheme.of(library.prefs.theme).scene)　（\(PhoneTheme.allCases.count) 套，选中即生效）")

            LazyVGrid(columns: [GridItem(.flexible(), spacing: m.dp(6)), GridItem(.flexible(), spacing: m.dp(6))],
                      spacing: m.dp(6)) {
                ForEach(PhoneTheme.allCases) { option in
                    let active = library.prefs.theme == option.storedValue
                    Button {
                        library.prefs.theme = option.storedValue
                    } label: {
                        HStack(spacing: m.dp(6)) {
                            Circle().fill(option.accent).frame(width: m.dp(7), height: m.dp(7))
                            Text(option.label)
                                .font(.system(size: m.sp(13)))
                                .foregroundStyle(active ? option.accent : theme.text)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, m.dp(10))
                        .frame(height: m.dp(38))
                        .soft(active ? .inset : .raised, fill: theme.surface,
                              radius: m.dp(PhoneDimen.radiusPill), depth: max(1, m.dp(3)))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, m.dp(4))

            ChoiceRow(label: "字体大小", value: scaleLabel, options: [
                ("超小", { library.prefs.uiScale = 1.3 }),
                ("较小", { library.prefs.uiScale = 1.45 }),
                ("标准", { library.prefs.uiScale = 1.6 }),
                ("较大", { library.prefs.uiScale = 1.75 }),
                ("超大", { library.prefs.uiScale = 1.9 })
            ])
            HintText(text: "字号、按钮高度、图标与卡片尺寸一起缩放，不会出现「字变大、按钮没变」。")

            SectionTitle(text: "列表布局")
            ChoiceRow(label: "列表每行", value: "\(library.prefs.gridColumns) 张", options: [
                ("1 张", { library.prefs.gridColumns = 1 }),
                ("2 张", { library.prefs.gridColumns = 2 }),
                ("3 张", { library.prefs.gridColumns = 3 })
            ])
            SwitchRow(label: "推荐页左右滑动", isOn: $library.prefs.recommendSwipe)
            HintText(text: "关闭后推荐页只能用顶部体裁标签换流，手指左右划不会整页翻走。")
        }
    }

    private var scaleLabel: String {
        switch library.prefs.uiScale {
        case 1.3: return "超小"
        case 1.45: return "较小"
        case 1.6: return "标准"
        case 1.75: return "较大"
        case 1.9: return "超大"
        default: return "标准"
        }
    }

    // MARK: 三、内容推荐

    private var recommend: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "推荐范围")
            HintText(text: "大类至少留一个；受众可不限。改完下次进推荐生效。")

            HStack(spacing: m.dp(6)) {
                ForEach(CategoryGenres.recommendable) { genre in
                    let id = genre.id ?? ""
                    let on = library.prefs.genreEnabled(id)
                    Button {
                        let next = !on
                        let offCount = library.prefs.recommendGenreOff.count
                        if !next && offCount + 1 >= CategoryGenres.recommendableIds.count {
                            ToastCenter.shared.show("至少保留一个推荐大类")
                            return
                        }
                        var off = Set(library.prefs.recommendGenreOff)
                        if next { off.remove(id) } else { off.insert(id) }
                        library.prefs.recommendGenreOff = Array(off)
                    } label: {
                        Text(genre.label)
                            .font(.system(size: m.sp(13)))
                            .foregroundStyle(on ? theme.accent : theme.textDim)
                            .frame(maxWidth: .infinity)
                            .frame(height: m.dp(40))
                            .soft(on ? .inset : .raised, fill: theme.surface,
                                  radius: m.dp(PhoneDimen.radiusPill), depth: max(1, m.dp(3)))
                    }
                    .buttonStyle(.plain)
                }
            }

            ChoiceRow(label: "受众", value: genderLabel, options: [
                ("不限", { library.prefs.recommendGender = "" }),
                ("男频", { library.prefs.recommendGender = "male" }),
                ("女频", { library.prefs.recommendGender = "female" })
            ])
            SwitchRow(label: "打开应用进入推荐", isOn: $library.prefs.launchRecommend)

            SectionTitle(text: "内容管理与来源")
            ChoiceRow(label: "选集每页", value: "\(library.prefs.episodePageSize) 集",
                      options: EpisodePageSize.phoneOptions.map { value in
                          ("\(value) 集", { library.prefs.episodePageSize = value })
                      })
            ChoiceRow(label: "搜索源", value: library.prefs.searchSource.label,
                      options: SearchSource.allCases.map { value in
                          ("\(value.label)　\(value.hint)", { library.prefs.searchSource = value })
                      })
            ActionRow(label: "恢复隐藏的剧集", detail: "\(library.hidden().count) 部") {
                if library.hidden().isEmpty {
                    ToastCenter.shared.show("没有被隐藏的剧集")
                } else {
                    confirmUnhide = true
                }
            }
        }
    }

    private var genderLabel: String {
        switch library.prefs.recommendGender {
        case "male": return "男频"
        case "female": return "女频"
        default: return "不限"
        }
    }

    // MARK: 四、系统高级

    private var system: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "设备与性能")
            SettingRow(label: "设备", value: "iPhone · iOS \(UIDevice.current.systemVersion)")
            SwitchRow(label: "减少动画优化性能", isOn: $library.prefs.reduceAnim)
            SwitchRow(label: "调试模式", isOn: $library.prefs.debugMode)
            HintText(text: "调试模式会在页面角落显示设备、接口与性能读数。")

            SectionTitle(text: "数据维护")
            ActionRow(label: "导出备份", detail: "存成一份 JSON 文件") {
                if let data = library.exportBackup() {
                    let url = FileManager.default.temporaryDirectory
                        .appendingPathComponent("hgtv-backup-\(Int(Date().timeIntervalSince1970)).json")
                    try? data.write(to: url, options: .atomic)
                    exportFile = url
                } else {
                    ToastCenter.shared.show("本机记录暂时无法导出")
                }
            }
            ActionRow(label: "从备份恢复", detail: "合并收藏、记录与设置") {
                showImporter = true
            }

            SectionTitle(text: "危险操作")
            ActionRow(label: "清空应用缓存", detail: "恢复成刚安装的样子", danger: true) {
                confirmReset = true
            }
            ActionRow(label: "恢复默认设置", detail: "只重置偏好，收藏与记录不动", danger: true) {
                confirmDefaults = true
            }
        }
    }
}

// MARK: - 选项行

struct ChoiceRow: View {
    let label: String
    let value: String
    let options: [(String, () -> Void)]

    @Environment(\.metrics) private var m
    @Environment(\.theme) private var theme

    var body: some View {
        Menu {
            ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                Button(option.0) { option.1() }
            }
        } label: {
            HStack(spacing: m.dp(8)) {
                Text(label)
                    .font(.system(size: m.sp(14)))
                    .foregroundStyle(theme.text)
                Spacer(minLength: m.dp(8))
                Text(value)
                    .font(.system(size: m.sp(13)))
                    .foregroundStyle(theme.textFaint)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.system(size: m.sp(11), weight: .semibold))
                    .foregroundStyle(theme.textFaint)
            }
            .padding(.horizontal, m.dp(12))
            .frame(height: m.dp(44))
            .soft(.raised, fill: theme.surface, radius: m.dp(PhoneDimen.radiusCard), depth: max(1, m.dp(2)))
        }
        .padding(.vertical, m.dp(2))
    }
}

// MARK: - 系统分享

struct ExportFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
