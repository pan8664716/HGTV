// SPDX-License-Identifier: GPL-3.0-only
//
// 路由：页面清单与跳转参数只写一份（对应 `shared/nav/Route.kt`）。
// Android 手机端每个页面是一个 Activity，iOS 用 NavigationStack + 值路由，
// 但「进详情要什么、进播放页要什么」完全一致：
//   detail(seriesId, title)
//   player(seriesId, title, episode?, fromRecommend)

import SwiftUI

enum AppRoute: Hashable {
    case detail(seriesId: String, title: String)
    case player(seriesId: String, title: String, episode: Int?, fromRecommend: Bool)
    case search
    case settings(SettingsPage)
    case account
}

@MainActor
final class Router: ObservableObject {
    @Published var path: [AppRoute] = []

    func push(_ route: AppRoute) { path.append(route) }

    func openDetail(_ series: Series) {
        push(.detail(seriesId: series.id, title: series.title))
    }

    func openPlayer(seriesId: String, title: String, episode: Int? = nil, fromRecommend: Bool = false) {
        push(.player(seriesId: seriesId, title: title, episode: episode, fromRecommend: fromRecommend))
    }

    func popToRoot() { path.removeAll() }
}

// MARK: - 全局提示

@MainActor
final class ToastCenter: ObservableObject {
    static let shared = ToastCenter()
    @Published private(set) var text: String?
    private var task: Task<Void, Never>?

    func show(_ message: String, seconds: Double = 1.6) {
        text = message
        task?.cancel()
        task = Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            text = nil
        }
    }
}

// MARK: - 根视图

struct RootView: View {
    @StateObject private var router = Router()
    @StateObject private var library = Library.shared
    @StateObject private var session = SessionStore.shared
    @StateObject private var toast = ToastCenter.shared

    @State private var tab = 0
    /// 三个体裁流各自的滚动/播放状态由页面自己保存；切 tab 时只是暂停。
    @State private var feedActive = true

    var body: some View {
        GeometryReader { geo in
            let metrics = PhoneMetrics(size: geo.size, uiScale: CGFloat(library.prefs.uiScale))
            let theme = PhoneTheme.of(library.prefs.theme)

            NavigationStack(path: $router.path) {
                ZStack(alignment: .bottom) {
                    theme.baseColor.ignoresSafeArea()

                    Group {
                        switch tab {
                        case 0:
                            RecommendPage(isActive: feedActive && router.path.isEmpty) { route in
                                router.push(route)
                            }
                        case 1:
                            CategoryPage { router.push($0) }
                        case 2:
                            RankPage { router.push($0) }
                        default:
                            MinePage { router.push($0) }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                    if router.path.isEmpty {
                        BottomTabBar(selected: $tab)
                    }
                }
                .navigationBarHidden(true)
                .navigationDestination(for: AppRoute.self) { route in
                    destination(route)
                }
            }
            .environment(\.metrics, metrics)
            .environment(\.theme, theme)
            .environment(\.safeAreaInsets, geo.safeAreaInsets)
            .environmentObject(router)
            .environmentObject(library)
            .environmentObject(session)
            .statusBarHidden(tab == 0 && router.path.isEmpty)
            .overlay(alignment: .topTrailing) {
                if library.prefs.debugMode {
                    DebugOverlay()
                        .padding(.top, geo.safeAreaInsets.top + metrics.dp(48))
                        .padding(.trailing, metrics.dp(8))
                }
            }
            .overlay(alignment: .center) {
                if let text = toast.text {
                    ToastView(text: text).padding(.bottom, metrics.dp(80))
                }
            }
            .animation(.easeOut(duration: 0.18), value: toast.text)
            .onChange(of: tab) { _, value in
                feedActive = value == 0
            }
            .onAppear {
                // 「打开应用进入推荐」关掉时落到分类页（与 Android 的启动落点一致）。
                if !library.prefs.launchRecommend { tab = 1 }
            }
            .onChange(of: router.path) { _, value in
                feedActive = tab == 0 && value.isEmpty
            }
        }
    }

    @ViewBuilder
    private func destination(_ route: AppRoute) -> some View {
        switch route {
        case let .detail(seriesId, title):
            DetailView(seriesId: seriesId, title: title)
        case let .player(seriesId, title, episode, fromRecommend):
            PlayerView(seriesId: seriesId, title: title, episode: episode, fromRecommend: fromRecommend)
        case .search:
            SearchView()
        case let .settings(page):
            SettingsView(initialPage: page)
        case .account:
            AccountView()
        }
    }
}
