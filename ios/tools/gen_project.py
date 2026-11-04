#!/usr/bin/env python3
"""生成 HGTV.xcodeproj（不依赖 xcodegen / tuist）。

本机没有 xcodegen，工程文件又必须能在任意一台装了 Xcode 的机器上打开，
因此这里用脚本把 `ios/HGTV` 目录树映射成 pbxproj：新增 .swift 文件后重跑一次即可，
不需要手工改工程。UUID 由路径的 md5 派生，重复运行结果稳定（不会每次改出一堆 diff）。

用法：
    python3 ios/tools/gen_project.py
"""

import hashlib
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "HGTV")
PROJ_DIR = os.path.join(ROOT, "HGTV.xcodeproj")
TARGET = "HGTV"
BUNDLE_ID = os.environ.get("HGTV_BUNDLE_ID", "com.hgtv.star")

SOURCE_EXT = {".swift"}
RESOURCE_EXT = {".xcassets", ".json", ".ttf", ".otf"}
SKIP_NAMES = {".DS_Store"}


def oid(key: str) -> str:
    return hashlib.md5(key.encode("utf-8")).hexdigest()[:24].upper()


class Gen:
    def __init__(self) -> None:
        self.objects = {}
        self.files = []       # (relative path, is_dir)
        self.sources = []     # relative paths of .swift
        self.resources = []   # relative paths of resources

    # ---------------------------------------------------------------- 扫描
    def scan(self) -> None:
        for base, dirs, names in os.walk(SRC):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_NAMES)
            names = sorted(n for n in names if n not in SKIP_NAMES)
            for name in dirs:
                rel = os.path.relpath(os.path.join(base, name), ROOT)
                self.files.append((rel, True))
            for name in names:
                rel = os.path.relpath(os.path.join(base, name), ROOT)
                ext = os.path.splitext(name)[1]
                if ext in SOURCE_EXT:
                    self.sources.append(rel)
                elif ext in RESOURCE_EXT:
                    self.resources.append(rel)
                elif name == "Info.plist":
                    pass  # 由 INFOPLIST_FILE 引用，不进 Resources 阶段
                else:
                    self.files.append((rel, False))
                    continue
                self.files.append((rel, False))

    # ---------------------------------------------------------------- 对象
    def emit(self, key: str, body: str) -> str:
        i = oid(key)
        # body 形如 `{ID = {...}; }`：pbxproj 的对象表里不能带最外层花括号。
        inner = body.strip()
        if inner.startswith("{") and inner.endswith("}"):
            inner = inner[1:-1].strip()
        self.objects[i] = inner
        return i

    def file_ref(self, rel: str) -> str:
        name = os.path.basename(rel)
        ext = os.path.splitext(name)[1]
        if os.path.isdir(os.path.join(ROOT, rel)):
            kind = "folder.assetcatalog" if ext == ".xcassets" else "folder"
        elif ext == ".swift":
            kind = "sourcecode.swift"
        elif ext == ".plist":
            kind = "text.plist.xml"
        elif ext == ".json":
            kind = "text.json"
        else:
            kind = "file"
        return self.emit("fileref:" + rel, (
            "{%s = {isa = PBXFileReference; lastKnownFileType = %s; path = %s; sourceTree = \"<group>\"; }; }"
            % (oid("fileref:" + rel), kind, os.path.basename(rel))
        ))

    def build_file(self, rel: str, phase: str) -> str:
        ref = self.file_ref(rel)
        return self.emit("buildfile:%s:%s" % (phase, rel), (
            "{%s = {isa = PBXBuildFile; fileRef = %s; }; }" % (oid("buildfile:%s:%s" % (phase, rel)), ref)
        ))

    def group_tree(self) -> str:
        """按目录生成嵌套 PBXGroup，返回根组 id。"""
        children = {}

        def ensure(path: str) -> str:
            if path in children:
                return children[path]
            gid = oid("group:" + path)
            children[path] = gid
            return gid

        root_id = ensure("HGTV")
        for rel, is_dir in self.files:
            parts = rel.split(os.sep)
            # 从 HGTV 下一层开始
            cur = "HGTV"
            for part in parts[1:-1]:
                cur = cur + "/" + part
                ensure(cur)
            leaf = rel if is_dir else rel
            parent = "HGTV" if len(parts) == 2 else "HGTV/" + "/".join(parts[1:-1])
            ensure(parent)
            if not is_dir:
                self.file_ref(rel)
            children.setdefault("__children__", {}).setdefault(parent, []).append(
                (oid("group:" + leaf) if is_dir else oid("fileref:" + leaf), os.path.basename(leaf))
            )

        # 目录本身要作为子组挂到父组
        for rel, is_dir in self.files:
            if not is_dir:
                continue
            parent = "HGTV" if rel.count(os.sep) == 1 else "HGTV/" + "/".join(rel.split(os.sep)[1:-1])
            children.setdefault("__children__", {}).setdefault(parent, []).append(
                (oid("group:" + rel), os.path.basename(rel))
            )

        for path, gid in children.items():
            if path.startswith("__"):
                continue
            kids = sorted(set(children.get("__children__", {}).get(path, [])))
            kid_str = "".join("%s /* %s */," % (k, n) for k, n in kids)
            self.objects[gid] = (
                "{%s = {isa = PBXGroup; children = (%s); path = %s; sourceTree = \"<group>\"; }; }"
                % (gid, kid_str, os.path.basename(path) if path != "HGTV" else "HGTV")
            )
        return root_id

    def generate(self) -> str:
        self.scan()
        main_group = self.group_tree()

        source_builds = "".join(self.build_file(r, "sources") + "," for r in self.sources)
        resource_builds = "".join(self.build_file(r, "resources") + "," for r in self.resources)

        sources_phase = self.emit("phase:sources", (
            "{%s = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (%s); runOnlyForDeploymentPostprocessing = 0; }; }"
            % (oid("phase:sources"), source_builds)
        ))
        resources_phase = self.emit("phase:resources", (
            "{%s = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (%s); runOnlyForDeploymentPostprocessing = 0; }; }"
            % (oid("phase:resources"), resource_builds)
        ))
        frameworks_phase = self.emit("phase:frameworks", (
            "{%s = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }; }"
            % oid("phase:frameworks")
        ))

        product_ref = self.emit("product", (
            "{%s = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = %s.app; sourceTree = BUILT_PRODUCTS_DIR; }; }"
            % (oid("product"), TARGET)
        ))
        products_group = self.emit("group:Products", (
            "{%s = {isa = PBXGroup; children = (%s); name = Products; sourceTree = \"<group>\"; }; }"
            % (oid("group:Products"), product_ref)
        ))
        root_group = self.emit("group:root", (
            "{%s = {isa = PBXGroup; children = (%s,%s); sourceTree = \"<group>\"; }; }"
            % (oid("group:root"), main_group, products_group)
        ))

        target = self.emit("target", (
            "{%s = {isa = PBXNativeTarget; buildConfigurationList = %s; buildPhases = (%s,%s,%s); "
            "buildRules = (); dependencies = (); name = %s; productName = %s; productReference = %s; "
            "productType = \"com.apple.product-type.application\"; }; }"
            % (oid("target"), oid("configlist:target"), sources_phase, frameworks_phase,
               resources_phase, TARGET, TARGET, product_ref)
        ))

        project = self.emit("project", (
            "{%s = {isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = 1; LastSwiftUpdateCheck = 1600; "
            "LastUpgradeCheck = 1600; TargetAttributes = {%s = {CreatedOnToolsVersion = 16.0; }; }; }; "
            "buildConfigurationList = %s; compatibilityVersion = \"Xcode 14.0\"; developmentRegion = \"zh-Hans\"; "
            "hasScannedForEncodings = 0; knownRegions = (\"zh-Hans\", en, Base); mainGroup = %s; "
            "productRefGroup = %s; projectDirPath = \"\"; projectRoot = \"\"; targets = (%s); }; }"
            % (oid("project"), oid("target"), oid("configlist:project"), root_group,
               products_group, target)
        ))

        proj_debug = self.emit("config:proj:debug", (
            "{%s = {isa = XCBuildConfiguration; buildSettings = {%s}; name = Debug; }; }"
            % (oid("config:proj:debug"), self.project_settings(debug=True))
        ))
        proj_release = self.emit("config:proj:release", (
            "{%s = {isa = XCBuildConfiguration; buildSettings = {%s}; name = Release; }; }"
            % (oid("config:proj:release"), self.project_settings(debug=False))
        ))
        self.objects[oid("configlist:project")] = (
            "{%s = {isa = XCConfigurationList; buildConfigurations = (%s,%s); defaultConfigurationIsVisible = 0; "
            "defaultConfigurationName = Release; }; }" % (oid("configlist:project"), proj_debug, proj_release)
        )

        tgt_debug = self.emit("config:target:debug", (
            "{%s = {isa = XCBuildConfiguration; buildSettings = {%s}; name = Debug; }; }"
            % (oid("config:target:debug"), self.target_settings(debug=True))
        ))
        tgt_release = self.emit("config:target:release", (
            "{%s = {isa = XCBuildConfiguration; buildSettings = {%s}; name = Release; }; }"
            % (oid("config:target:release"), self.target_settings(debug=False))
        ))
        self.objects[oid("configlist:target")] = (
            "{%s = {isa = XCConfigurationList; buildConfigurations = (%s,%s); defaultConfigurationIsVisible = 0; "
            "defaultConfigurationName = Release; }; }" % (oid("configlist:target"), tgt_debug, tgt_release)
        )

        body = "".join("%s /* %s */\n" % (k, "obj") for k in ())
        chunks = []
        for k in sorted(self.objects):
            body = self.objects[k].strip()
            if body.startswith("{") and body.endswith("}"):
                body = body[1:-1].strip()
            chunks.append("\t\t%s" % body)
        out = (
            "// !$*UTF8*$!\n"
            "{\n"
            "\tarchiveVersion = 1;\n"
            "\tclasses = {\n\t};\n"
            "\tobjectVersion = 56;\n"
            "\tobjects = {\n"
            + "\n".join(chunks)
            + "\n\t};\n"
            "\trootObject = %s /* Project object */;\n}\n" % project
        )
        return out

    def project_settings(self, debug: bool) -> str:
        common = [
            'ALWAYS_SEARCH_USER_PATHS = NO',
            'CLANG_ENABLE_MODULES = YES',
            'CLANG_ENABLE_OBJC_ARC = YES',
            'ENABLE_STRICT_OBJC_MSGSEND = YES',
            'GCC_C_LANGUAGE_STANDARD = gnu11',
            'IPHONEOS_DEPLOYMENT_TARGET = 17.0',
            'SDKROOT = iphoneos',
            'SWIFT_VERSION = 5.0',
            'ENABLE_USER_SCRIPT_SANDBOXING = YES',
        ]
        if debug:
            common += [
                'DEBUG_INFORMATION_FORMAT = dwarf',
                'ENABLE_TESTABILITY = YES',
                'GCC_OPTIMIZATION_LEVEL = 0',
                'ONLY_ACTIVE_ARCH = YES',
                'SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG',
                'SWIFT_OPTIMIZATION_LEVEL = "-Onone"',
            ]
        else:
            common += [
                'DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"',
                'ENABLE_NS_ASSERTIONS = NO',
                'SWIFT_COMPILATION_MODE = wholemodule',
                'SWIFT_OPTIMIZATION_LEVEL = "-O"',
                'VALIDATE_PRODUCT = YES',
            ]
        return ";".join(common) + ";"

    def target_settings(self, debug: bool) -> str:
        settings = [
            'ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon',
            'CODE_SIGN_STYLE = Automatic',
            'CURRENT_PROJECT_VERSION = 1',
            'DEVELOPMENT_TEAM = "%s"' % os.environ.get("HGTV_TEAM", ""),
            'ENABLE_PREVIEWS = YES',
            'GENERATE_INFOPLIST_FILE = NO',
            'INFOPLIST_FILE = HGTV/Resources/Info.plist',
            'INFOPLIST_KEY_UIApplicationSceneManifest_Generation = NO',
            'LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks")',
            'MARKETING_VERSION = 1.3.0',
            'PRODUCT_BUNDLE_IDENTIFIER = %s' % BUNDLE_ID,
            'PRODUCT_NAME = "$(TARGET_NAME)"',
            'SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"',
            'SUPPORTS_MACCATALYST = NO',
            'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO',
            'TARGETED_DEVICE_FAMILY = 1',
            'SWIFT_EMIT_LOC_STRINGS = YES',
        ]
        if debug:
            settings.append('SWIFT_OPTIMIZATION_LEVEL = "-Onone"')
        return ";".join(settings) + ";"


def main() -> None:
    gen = Gen()
    content = gen.generate()
    if os.path.isdir(PROJ_DIR):
        shutil.rmtree(PROJ_DIR)
    os.makedirs(PROJ_DIR)
    with open(os.path.join(PROJ_DIR, "project.pbxproj"), "w", encoding="utf-8") as f:
        f.write(content)
    print("wrote %s (%d files, %d sources, %d resources)"
          % (os.path.join(PROJ_DIR, "project.pbxproj"), len(gen.files), len(gen.sources), len(gen.resources)))


if __name__ == "__main__":
    main()
