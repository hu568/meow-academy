package com.meow.academy.ui.chat

import com.meow.academy.data.chat.ChatDao
import com.meow.academy.data.model.PersonaCatalogRepository
import com.meow.academy.data.model.PersonaEntry
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 角色设定状态控制器——角色目录/默认角色/两开关/空白会话同步（plan-memory-execution §3.1–§3.4）。
 *
 * 状态所有权：personaCatalog / defaultPersonaId / personaEnabled / memoryEnabled。
 * 全部转发 DataStore 或角色目录缓存；切换只写设置（+ 空白会话行同步）。
 */
class ChatPersonaController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val personaCatalogRepo: PersonaCatalogRepository,
    private val runtimeManager: RuntimeManager,
    private val dao: ChatDao,
    private val filesDirPath: String,
    /** 当前打开会话的 Room id（空白会话同步用；由 ChatViewModel 注入 sessionController 的值） */
    private val currentSessionId: () -> Long?,
    private val toast: (String) -> Unit,
) {
    /** 角色库目录（personas/list 缓存；DSH 未就绪时先渲染缓存，refreshPersonas 覆盖） */
    val personaCatalog: StateFlow<List<PersonaEntry>> = personaCatalogRepo.personas
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 新会话默认角色 id（默认 "default"；空串 = 角色开关 OFF 时不绑定） */
    val defaultPersonaId: StateFlow<String> = settingsRepository.defaultPersonaId
        .stateIn(scope, SharingStarted.Eagerly, "default")

    /** 新会话默认角色开关（默认 ON） */
    val personaEnabled: StateFlow<Boolean> = settingsRepository.personaEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** 新会话默认记忆开关（默认 ON） */
    val memoryEnabled: StateFlow<Boolean> = settingsRepository.memoryEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * 拉取 personas/list 并覆盖本地缓存。
     * 触发时机：进角色设定页 + DSH 转 Running；DSH 未就绪 / 失败 → 静默保留缓存。
     */
    fun refreshPersonas() {
        scope.launch { personaCatalogRepo.refresh(runtimeManager.rpcClient) }
    }

    /**
     * 设为默认角色 id（DataStore；只对新会话生效）。
     * 若当前会话为空（无消息），同步更新其 Room 行（首条前可自由切换）。
     */
    fun selectDefaultPersona(id: String) {
        scope.launch {
            settingsRepository.setDefaultPersonaId(id)
            maybeSyncBlankSession(id, personaEnabled.first(), memoryEnabled.first())
        }
    }

    /** 设置角色开关（DataStore + 空白会话同步） */
    fun setPersonaEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setPersonaEnabled(enabled)
            maybeSyncBlankSession(defaultPersonaId.first(), enabled, memoryEnabled.first())
        }
    }

    /** 设置记忆开关（DataStore + 空白会话同步） */
    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setMemoryEnabled(enabled)
            maybeSyncBlankSession(defaultPersonaId.first(), personaEnabled.first(), enabled)
        }
    }

    /**
     * 持久化角色拖拽排序（调用 personas/reorder RPC；失败 toast 提示）。
     */
    fun reorderPersonas(order: List<String>) {
        scope.launch {
            val rpc = runtimeManager.rpcClient
            if (rpc != null) {
                val result = rpc.personasReorder(order)
                if (result != null) {
                    refreshPersonas()
                    toast("角色排序已更新喵~")
                } else {
                    toast("角色排序失败喵（DSH 未就绪）")
                }
            } else {
                toast("角色排序失败喵（DSH 未就绪）")
            }
        }
    }

    /**
     * 创建空白角色（三件套模板：persona.yml + SOUL.md + USER.md），
     * 写入 `.agents/personas/<id>/` 后刷新 list。
     * @param id 角色 id（文件夹名，小写+中横线风格）
     * @param name 展示名
     * @param description 一句话简介
     */
    fun createPersona(id: String, name: String, description: String) {
        scope.launch {
            val dir = File(filesDirPath, "${RuntimeExtractor.AGENTS_PERSONAS_DIR}/$id")
            if (dir.exists()) {
                toast("角色「$id」已存在喵~")
                return@launch
            }
            dir.mkdirs()
            runCatching {
                File(dir, "persona.yml").writeText("name: $name\ndescription: $description\n")
                File(dir, "SOUL.md").writeText("""<!--
SOUL.md — 角色人格设定

角色开关 ON 时，此文件内容会注入到系统提示词的 <soul> 段落。
内容为空时跳过注入，不污染提示词。

典型章节：Identity, Personality, Tone, Principles, Communication Style, Expertise, Boundaries
如需创建角色，请阅读 personas/skills/soul-md-generator/SKILL.md 按流程操作。
-->
""")
                File(dir, "USER.md").writeText("""<!--
USER.md — 该角色专属的用户档案

角色开关 ON 时，此文件内容会注入到系统提示词的 <user> 段落。
内容为空时跳过注入，不污染提示词。

典型栏目：基本信息（称呼、语言偏好、沟通风格）、偏好（工作时段、格式偏好）
-->
""")
                toast("已创建角色「$name」喵~")
            }.onFailure {
                dir.deleteRecursively()
                toast("创建角色失败: ${it.message} 喵")
            }
            refreshPersonas()
        }
    }

    /**
     * 删除角色目录（递归删除整个 <id>/）。
     * 若当前默认角色正是被删的 id → 回退 "default"。
     */
    fun deletePersona(id: String) {
        scope.launch {
            val dir = File(filesDirPath, "${RuntimeExtractor.AGENTS_PERSONAS_DIR}/$id")
            if (!dir.exists()) {
                toast("角色「$id」不存在喵~")
                return@launch
            }
            runCatching {
                dir.deleteRecursively()
                toast("已删除角色「$id」喵~")
            }.onFailure {
                toast("删除角色失败: ${it.message} 喵")
            }
            refreshPersonas()
            if (defaultPersonaId.first() == id) {
                settingsRepository.setDefaultPersonaId("default")
            }
        }
    }

    /**
     * 若当前会话仍为空（零消息），把新配置同步写回它的 Room 行（plan-memory-execution §3.2：
     * 空会话首条消息前可自由切换；已有消息的会话角色锁定，改了也不生效——DSH 侧首条定死）。
     */
    private suspend fun maybeSyncBlankSession(personaId: String, personaOn: Boolean, memoryOn: Boolean) {
        val sessionId = currentSessionId() ?: return
        if (dao.countMessages(sessionId) > 0) return
        dao.updateSessionPersona(
            sessionId,
            // 角色开关 OFF → personaId 不绑定（design-memory-system §2.3）
            personaId = personaId.takeIf { it.isNotBlank() && personaOn },
            personaEnabled = personaOn,
            memoryEnabled = memoryOn,
        )
    }
}