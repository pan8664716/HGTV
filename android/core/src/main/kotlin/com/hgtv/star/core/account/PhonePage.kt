// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 手机端一次性页面的**共用外壳**：新拟态（neumorphism）+ 竖屏。
 *
 * 四个扫码页面（手机输入 / 手机号登录 / 导入账号参数 / 备份恢复）原先各写一份 `<style>`，
 * 字号 17–18px、输入框直角圆角 8–10px、按钮一整条通栏、没有安全区留白——
 * 在手机竖屏上就是「傻大粗」。这里统一成一套令牌：
 *
 * * 材质：一整块 `--bg` 底，卡片与按钮靠**双向阴影**凸起，输入框与文件槽靠内阴影凹陷，
 *   不画任何描边；`--out` / `--out-sm` / `--in` / `--in-sm` 四档阴影是全页唯一的分层手段。
 * * 尺寸：卡片圆角 22、输入框 15、按钮与文件槽一律**胶囊**（999）高 44；
 *   正文 13px / 标题 20.5px / 小标题 10.5px 大写字距。
 * * 强调色只用在主按钮与胶囊标签上，其余全是同一色相的明暗差。
 * * 安全区：`viewport-fit=cover` + `env(safe-area-inset-*)`，避开刘海与手势条。
 *
 * 页面本身**不带任何外部资源**（字体走系统栈），因此仍可用 `default-src 'none'` 的 CSP。
 */
object PhonePage {
    /** 页面共用的 CSS。四个页面共用一份，改一处全部生效。 */
    val css: String = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
:root{
  --bg:#242a35;
  --well:#1f242e;
  --text:#eef1f7;
  --sub:#a4aec0;
  --muted:#78829a;
  --faint:#5b6478;
  --accent:#ff7a5c;
  --sh-d:#171b22;
  --sh-l:#313949;
  --out:8px 8px 20px var(--sh-d), -8px -8px 20px var(--sh-l);
  --out-sm:5px 5px 13px var(--sh-d), -5px -5px 13px var(--sh-l);
  --in:inset 4px 4px 10px var(--sh-d), inset -4px -4px 10px var(--sh-l);
  --in-sm:inset 3px 3px 7px var(--sh-d), inset -3px -3px 7px var(--sh-l);
}
html,body{margin:0}
body{
  min-height:100vh;
  padding:calc(env(safe-area-inset-top,0px) + 34px) 16px calc(env(safe-area-inset-bottom,0px) + 28px);
  font:15px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Hiragino Sans GB","Noto Sans SC","Microsoft YaHei",sans-serif;
  color:var(--text);
  background:var(--bg);
  max-width:520px;margin:0 auto;
  display:flex;flex-direction:column;gap:15px;
  -webkit-font-smoothing:antialiased;
  -webkit-text-size-adjust:100%;
}
body>*{flex:0 0 auto}

.pill{
  align-self:flex-start;
  font-size:10.5px;font-weight:600;letter-spacing:1.1px;
  color:var(--accent);
  padding:6px 13px;border-radius:99px;
  background:var(--bg);
  box-shadow:var(--out-sm);
}

h1{font-size:20.5px;line-height:1.36;font-weight:700;letter-spacing:-.2px;margin:0}
h2{font-size:10.5px;font-weight:600;letter-spacing:1.2px;color:var(--muted);margin:0}
.lead{margin:0;font-size:13px;line-height:1.72;color:var(--sub)}
.hint{margin:0;font-size:11.5px;line-height:1.6;color:#68728a}
.foot{margin:0;font-size:11px;line-height:1.66;color:var(--faint)}

.card{
  background:var(--bg);
  border-radius:22px;
  padding:17px 16px;
  display:flex;flex-direction:column;gap:13px;
  box-shadow:var(--out);
}
form{display:flex;flex-direction:column;gap:12px;margin:0}

input,textarea{
  width:100%;
  font:inherit;font-size:16px;color:var(--text);
  background:var(--well);
  border:0;border-radius:15px;
  padding:14px 16px;
  box-shadow:var(--in-sm);
  transition:box-shadow .18s ease;
  -webkit-appearance:none;
}
input::placeholder,textarea::placeholder{color:#525c70}
input:focus,textarea:focus{
  outline:none;
  box-shadow:var(--in-sm), 0 0 0 2px rgba(255,122,92,.45);
}
textarea{min-height:100px;resize:none;font-size:14.5px;line-height:1.6}

button,a.btn{
  display:flex;align-items:center;justify-content:center;
  width:100%;height:44px;padding:0 22px;
  font:inherit;font-size:14px;font-weight:600;letter-spacing:.3px;
  border:0;border-radius:999px;cursor:pointer;text-decoration:none;
  transition:box-shadow .16s ease,transform .16s ease,background .16s ease;
}
.btn-primary{
  color:#2b0f07;
  background:linear-gradient(145deg,#ff8f74,#ff6a4b);
  box-shadow:
    5px 5px 13px var(--sh-d),
    -5px -5px 13px var(--sh-l),
    inset 0 1px 0 rgba(255,255,255,.34),
    inset 0 -2px 4px rgba(150,45,25,.18);
}
.btn-primary:active{
  transform:translateY(1px);
  background:linear-gradient(145deg,#f77f64,#f25e3f);
  box-shadow:
    inset 4px 4px 9px rgba(150,45,25,.5),
    inset -3px -3px 8px rgba(255,255,255,.18);
}
.btn-ghost{
  color:var(--sub);background:var(--bg);
  box-shadow:5px 5px 13px var(--sh-d), -5px -5px 13px var(--sh-l), inset 0 1px 0 rgba(255,255,255,.045);
}
.btn-ghost:active{transform:translateY(1px);color:var(--text);box-shadow:var(--in-sm)}
button:disabled{color:var(--faint);background:var(--bg);box-shadow:var(--in-sm);cursor:default}

.picker{
  position:relative;
  display:flex;align-items:center;gap:10px;
  height:44px;padding:0 5px;
  background:var(--well);border-radius:999px;
  box-shadow:var(--in-sm);cursor:pointer;
}
.picker input[type=file]{
  position:absolute;inset:0;width:100%;height:100%;
  opacity:0;cursor:pointer;
  padding:0;border:0;box-shadow:none;background:transparent;
}
.picker-tag{
  flex:none;height:34px;display:flex;align-items:center;
  padding:0 16px;
  font-size:12.5px;font-weight:600;color:var(--text);
  background:var(--bg);border-radius:999px;
  box-shadow:3px 3px 8px var(--sh-d), -3px -3px 8px var(--sh-l);
}
.picker-name{
  flex:1;min-width:0;
  font-size:12px;color:var(--muted);
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
}

code{
  font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
  font-size:.88em;color:#ffb3a2;
  background:rgba(255,122,92,.10);
  padding:1.5px 5px;border-radius:5px;
  word-break:break-all;
}
code.block{display:block;font-size:14px;line-height:1.5;padding:13px 15px;border-radius:14px;
  background:var(--well);box-shadow:var(--in-sm);color:#ffcbbd}

.err{margin:0;font-size:12.5px;font-weight:600;color:#ff9a86}
.done{display:flex;flex-direction:column;align-items:center;gap:10px;padding:8px 0 2px;text-align:center}
.done .mark{
  width:56px;height:56px;border-radius:50%;
  display:flex;align-items:center;justify-content:center;
  font-size:24px;color:var(--accent);
  background:var(--bg);box-shadow:var(--out);
}
""".trimIndent()

    /**
     * 一整页。
     *
     * [body] 是已经拼好的 HTML 片段；[script] 给需要 JS 的页面（备份恢复），
     * 由调用方带上 nonce——CSP 里 `script-src 'nonce-…'` 只放行这一段。
     */
    fun shell(title: String, body: String, footer: String = DEFAULT_FOOTER, script: String = ""): String = buildString {
        append("""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">""")
        append("""<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">""")
        append("""<meta name="theme-color" content="#242a35"><meta name="format-detection" content="telephone=no">""")
        append("<title>HGTV · ").append(title).append("</title>")
        append("<style>").append(css).append("</style></head><body>")
        append(body)
        if (footer.isNotBlank()) append("<p class=\"foot\">").append(footer).append("</p>")
        if (script.isNotBlank()) append(script)
        append("</body></html>")
    }

    /** 默认页脚：四个页面都要交代「只在这台电视上、只在这次窗口里」。 */
    const val DEFAULT_FOOTER = "仅本次端口打开期间有效；内容只保存在电视本机。"

    /** 「已完成」页：一个大圆勾 + 一句指回电视的话。 */
    fun done(title: String, message: String = "请回到电视继续。"): String = shell(title,
        """<span class="pill">完成</span>""" +
        """<div class="card"><div class="done"><div class="mark">✓</div>""" +
        """<h1>$title</h1><p class="lead">$message</p></div></div>""")

    /** 「出错了」页：错误说明 + 一句怎么办。 */
    fun failed(title: String, message: String, hint: String = ""): String = shell(title,
        """<span class="pill">提示</span>""" +
        """<h1>$title</h1>""" +
        """<div class="card"><p class="err">$message</p>""" +
        (if (hint.isBlank()) "" else """<p class="hint">$hint</p>""") + "</div>")
}
