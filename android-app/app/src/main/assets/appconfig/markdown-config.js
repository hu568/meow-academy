// 🐾 喵仓 Markdown 渲染配置 · 装修方案书
//
// 这个文件由 Kotlin（施工队）读取，控制 Markdown 渲染的视觉细节。
// 修改后即时生效（FileObserver 热更，无需重启 App）；
// DSH/AI 也可以直接 write 本文件来改外观（属于「体验类」配置，不碰密钥）。
//
// 📌 契约：
//   1. 最后一行必须把配置对象赋给全局变量 `markdownConfig`；
//   2. 每个值可以是：
//      - null                → 跟随主题默认
//      - 普通值              → 浅色/深色都用它
//      - { light: X, dark: Y } → 浅色用 X，深色用 Y（主题感知）
//   3. 长度单位统一用 dp（数字），颜色用 #RRGGBB / #AARRGGBB 字符串。

function themed(light, dark) { return { light: light, dark: dark }; }

var markdownConfig = {
  version: 1,

  // ── 公式块（$$…$$） ──────────────────────────────
  formula: {
    blockCornerRadiusDp: 12,          // 背景圆角 (dp)，0 = 直角
    blockBackground: themed("#F2F2F7", "#1E1E2E"), // 浅色浅灰 / 深色深灰，null = 无背景
    blockPaddingDp: { left: 16, top: 8, right: 16, bottom: 8 },
    blockFitCanvas: true,             // 是否撑满容器宽度
    blockAlign: 1,                    // 0=左 1=中 2=右
    blockTextColor: null,             // null = 跟随主题文字色
    inlineTextColor: null,            // null = 跟随主题文字色
  },

  // ── 无序列表「-」渲染的 · 大小 ──────────────────────
  list: {
    bulletWidthDp: 6,                 // · 的直径 (dp)
    bulletStrokeWidthDp: 1,           // 描边宽 (dp)
    itemColor: null,                  // null = 跟随主题
  },

  // ── 代码（``` 围栏块 + 行内 `code`） ───────────────
  code: {
    blockCornerRadiusDp: 10,          // 代码块背景圆角 (dp)，0 = 直角
    blockBackground: null,            // null = 主题默认
    blockMarginDp: 8,                 // 代码块外边距 (dp)
    blockTextSizeRatio: 0.85,         // 代码块内文字相对正文比例
    textSizeRatio: 0.85,             // 行内代码相对正文比例
    blockTextColor: null,             // null = 主题默认
    textColor: null,                  // null = 主题默认
    inlineCornerRadiusDp: 6,          // 行内代码背景圆角 (dp)，0 = 恢复 Markwon 直角
    inlineBackground: null,           // null = 主题默认（当前文字色 10% 透明度）
    inlinePaddingDp: { left: 5, top: 2, right: 5, bottom: 2 }, // 行内代码内边距 (dp)
  },

  // ── 引用块 ────────────────────────────────────────
  quote: {
    color: null,                      // null = 主题默认
    widthDp: 4,                       // 左侧竖线宽 (dp)
  },

  // ── 链接 ──────────────────────────────────────────
  link: {
    color: null,                      // null = 主题默认
    underlined: true,
  },

  // ── 标题（H1..H6） ────────────────────────────────
  heading: {
    sizeMultipliers: [1.6, 1.4, 1.25, 1.15, 1.1, 1.0],
  },

  // ── 水平分割线 ─────────────────────────────────────
  thematicBreak: {
    color: null,                      // null = 主题默认
    heightDp: 2,
  },
};
