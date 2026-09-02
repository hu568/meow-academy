package com.meow.academy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 存量人格迁移单测（plan-memory-execution §5.1）。
 *
 * 覆盖纯文件版 [RuntimeExtractor.migrateLegacySoul] 与 [RuntimeExtractor.hasSubstantiveContent]
 * ——迁移失败会吃掉用户自定义人格，属于高风险路径，必须钉死语义（不依赖 Context，故走普通 JVM 测试）。
 */
class SoulMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 空白模板（assets 里 default/SOUL.md 的形状：整份都是 HTML 注释） */
    private val blankTemplate = """
        <!--
        SOUL.md — 角色人格设定

        角色开关 ON 时，此文件内容会注入到系统提示词的 <soul> 段落。
        -->
    """.trimIndent()

    private val realSoul = """
        你是一位温柔耐心的学习助教「喵喵老师」。

        ## 风格
        - 回答要清晰有条理。
    """.trimIndent()

    // ── hasSubstantiveContent ─────────────────────────────────

    @Test
    fun `空串与纯注释都算无实质内容`() {
        assertFalse(RuntimeExtractor.hasSubstantiveContent(""))
        assertFalse(RuntimeExtractor.hasSubstantiveContent("   \n\t \n"))
        assertFalse(RuntimeExtractor.hasSubstantiveContent(blankTemplate))
        assertFalse(RuntimeExtractor.hasSubstantiveContent("# 只有标题行"))
    }

    @Test
    fun `混排注释与正文算有实质内容`() {
        assertTrue(RuntimeExtractor.hasSubstantiveContent("<!-- 注释 -->\n我是喵喵老师"))
        assertTrue(RuntimeExtractor.hasSubstantiveContent(realSoul))
    }

    // ── migrateLegacySoul ─────────────────────────────────────

    /** 造 filesDir 形状：.agents/memory/SOUL.md + .agents/personas/default/SOUL.md */
    private fun layout(legacyContent: String?, defaultContent: String?): Triple<File, File, File> {
        val memoryDir = tmp.newFolder(".agents", "memory")
        val defaultDir = tmp.newFolder(".agents", "personas", "default")
        val legacy = File(memoryDir, "SOUL.md")
        val target = File(defaultDir, "SOUL.md")
        val backup = File(memoryDir, "SOUL.md.bak")
        legacyContent?.let { legacy.writeText(it) }
        defaultContent?.let { target.writeText(it) }
        return Triple(legacy, target, backup)
    }

    @Test
    fun `新装用户（旧位置无文件）→ 什么都不动`() {
        val (legacy, target, backup) = layout(legacyContent = null, defaultContent = blankTemplate)
        assertEquals(RuntimeExtractor.SoulMigration.NO_LEGACY, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        assertFalse(target.readText() == realSoul)
        assertFalse(backup.exists())
    }

    @Test
    fun `旧文件有实质内容 + default 是空白模板 → 迁入并备份`() {
        val (legacy, target, backup) = layout(legacyContent = realSoul, defaultContent = blankTemplate)
        assertEquals(RuntimeExtractor.SoulMigration.MIGRATED, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        // 内容完整搬过去，一字不差
        assertEquals(realSoul, target.readText())
        // 旧位置清空、原件进 .bak（用户可自救）
        assertFalse(legacy.exists())
        assertEquals(realSoul, backup.readText())
    }

    @Test
    fun `default 已被用户填写 → 保留目标内容只备份`() {
        val editedDefault = "我自己写的默认角色人格"
        val (legacy, target, backup) = layout(legacyContent = realSoul, defaultContent = editedDefault)
        assertEquals(RuntimeExtractor.SoulMigration.TARGET_OCCUPIED, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        assertEquals(editedDefault, target.readText())
        assertFalse(legacy.exists())
        assertEquals(realSoul, backup.readText())
    }

    @Test
    fun `旧文件只是空白模板（播种却没编辑）→ 不迁移只备份`() {
        val (legacy, target, backup) = layout(legacyContent = blankTemplate, defaultContent = blankTemplate)
        assertEquals(RuntimeExtractor.SoulMigration.BACKED_UP_ONLY, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        // default 仍是模板原文（没被注释内容覆盖成"实质人格"）
        assertEquals(blankTemplate, target.readText())
        assertFalse(legacy.exists())
        assertTrue(backup.exists())
    }

    @Test
    fun `default 目录尚未播种 → 迁移时自动建目录`() {
        val memoryDir = tmp.newFolder(".agents2", "memory")
        val legacy = File(memoryDir, "SOUL.md").apply { writeText(realSoul) }
        val target = File(tmp.root, ".agents2/personas/default/SOUL.md")
        val backup = File(memoryDir, "SOUL.md.bak")
        assertEquals(RuntimeExtractor.SoulMigration.MIGRATED, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        assertEquals(realSoul, target.readText())
    }

    @Test
    fun `重复调用幂等（第二次走 NO_LEGACY，不覆盖已迁入内容）`() {
        val (legacy, target, backup) = layout(legacyContent = realSoul, defaultContent = blankTemplate)
        RuntimeExtractor.migrateLegacySoul(legacy, target, backup)
        // 第二次：旧位置已无文件
        assertEquals(RuntimeExtractor.SoulMigration.NO_LEGACY, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        assertEquals(realSoul, target.readText())
    }

    @Test
    fun `旧文件迁移时备份文件已存在则覆盖不报错`() {
        val (legacy, target, backup) = layout(legacyContent = realSoul, defaultContent = blankTemplate)
        backup.writeText("上一次的旧备份")
        assertEquals(RuntimeExtractor.SoulMigration.MIGRATED, RuntimeExtractor.migrateLegacySoul(legacy, target, backup))
        assertEquals(realSoul, backup.readText())
    }
}
