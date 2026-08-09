import { config } from '../config';

/**
 * 文本分块器 (参考 Cherry Studio 的 long chain 递归文本分割器)
 * - 按段落 / 标题 / 句子层级递归分割
 * - 块大小约 300 字, 带 50 字重叠, 保留上下文
 */
export interface Chunk {
  /** 片段文本 */
  text: string;
  /** 来源文件 */
  source: string;
  /** 块序号 */
  index: number;
}

const SEPARATORS = [
  '\n## ', // 二级标题
  '\n# ', // 一级标题
  '\n\n', // 段落
  '\n', // 行
  '。', // 句号
  '！',
  '？',
  '；',
  '，',
  ' ',
  '',
];

/** 将一段 markdown 文本递归分割为 ~300 字的分块 */
export function chunkText(text: string, source: string): Chunk[] {
  const chunks: Chunk[] = [];
  const { chunkSize, chunkOverlap } = config.rag;

  const splitRecursive = (content: string, sepIdx: number) => {
    // 已足够小, 直接作为一个块
    if (content.length <= chunkSize || sepIdx >= SEPARATORS.length) {
      if (content.trim()) {
        chunks.push({ text: content.trim(), source, index: chunks.length });
      }
      return;
    }
    const sep = SEPARATORS[sepIdx];
    let parts: string[] = [];
    if (sep === '') {
      // 最后手段: 硬切
      for (let i = 0; i < content.length; i += chunkSize - chunkOverlap) {
        parts.push(content.slice(i, i + chunkSize));
      }
    } else {
      parts = content.split(sep).filter((p) => p.trim());
    }

    let buffer = '';
    for (const part of parts) {
      const candidate = buffer ? buffer + sep + part : part;
      if (candidate.length <= chunkSize) {
        buffer = candidate;
      } else {
        if (buffer) {
          // 把上一段交出去继续递归细分
          splitRecursive(buffer, sepIdx + 1);
        }
        buffer = part;
      }
    }
    if (buffer) {
      splitRecursive(buffer, sepIdx + 1);
    }
  };

  splitRecursive(text, 0);

  // 后处理: 相邻小块合并到 ~chunkSize (带重叠效果)
  const merged: Chunk[] = [];
  let cur = '';
  let baseIndex = 0;
  for (const c of chunks) {
    if ((cur + c.text).length <= chunkSize + chunkOverlap && cur) {
      cur += '\n' + c.text;
    } else {
      if (cur.trim()) {
        merged.push({ text: cur.trim(), source, index: baseIndex++ });
      }
      cur = c.text;
    }
  }
  if (cur.trim()) {
    merged.push({ text: cur.trim(), source, index: baseIndex });
  }
  return merged;
}

/** 解析 markdown 文档为纯文本(去掉代码块外的标记, 保留正文) */
export function mdToText(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, (m) => '\n[代码块]\n' + m.replace(/```/g, '').replace(/^[a-zA-Z]*\n/, '') + '\n[/代码块]\n')
    .replace(/!\[.*?\]\(.*?\)/g, '[图片]')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[#>*`~|-]{1,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
