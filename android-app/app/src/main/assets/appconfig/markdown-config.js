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

  // ── 表格（M5 升级） ──────────────────────────────
  table: {
    cornerRadiusDp: 12,               // 表格圆角 (dp)
    headerBackground: themed("#E8E8ED", "#2A2A3A"), // 表头背景色
    rowAltBackground: null,           // null = 无斑马纹，{ light: "#F5F5F5", dark: "#252535" }
    borderColor: themed("#D1D1D6", "#383850"),
    borderWidthDp: 1,
    cellPaddingDp: { left: 10, top: 6, right: 10, bottom: 6 },
    copyButtonColor: null,
  },

  // ── Mermaid 图（M5 升级） ─────────────────────────
  mermaid: {
    theme: "",                        // '' = 自动跟随系统深色，'dark'/'default'/'neutral'/'forest'/'base'
    cornerRadiusDp: 12,               // 图块背景圆角 (dp)，0 = 直角
    blockBackground: null,            // null = 主题默认（surfaceVariant 半透明），可给 #RRGGBB 自定义
  },

  // ── 图片（M5.5 升级：md 渲染 + 聊天图片） ──────────
  image: {
    cornerRadiusDp: 12,               // 图片圆角 (dp)，0 = 直角
    borderWidthDp: 1,                 // 线框宽 (dp)，0 = 无边框
    borderColor: themed("#D1D1D6", "#383850"), // 线框颜色，null = 跟随主题
    maxHeightDp: 320,                 // 聊天气泡内图片最大高度 (dp)
    loadingBackground: null,          // null = 主题默认（surfaceVariant 半透明）
    errorText: "图片加载失败",          // 加载失败时的提示文案
  },
};
