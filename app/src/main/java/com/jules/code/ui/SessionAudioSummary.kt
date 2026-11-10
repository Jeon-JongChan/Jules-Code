package com.jules.code.ui

import android.content.Context
import com.jules.code.R
import com.jules.code.data.model.JulesActivity
import com.jules.code.data.model.JulesSession
import com.jules.code.ui.health.sessionStateLabel

/** 현재 앱 언어의 Context를 사용하여 실제 세션 내용은 보존한 TTS 브리핑을 구성합니다. */
fun buildSessionAudioSummary(context: Context, session: JulesSession?, activities: List<JulesActivity>): String {
    if (session == null) return context.getString(R.string.ui_audio_no_session)
    val planSteps = activities.mapNotNull { it.planGenerated?.plan?.steps }.flatten()
    val artifacts = activities.flatMap { it.artifacts }
    val patches = artifacts.mapNotNull { it.changeSet?.gitPatch }
    val commands = artifacts.mapNotNull { it.bashOutput }
    val state = sessionStateLabel(context, session.state)
    return buildList {
        add(context.getString(R.string.ui_audio_intro))
        add(context.getString(R.string.ui_audio_task, session.displayTitle, state))
        add(context.getString(R.string.ui_audio_repository, session.repoDisplay, session.branchDisplay))
        if (planSteps.isNotEmpty()) add(context.getString(R.string.ui_audio_plan, planSteps.size, planSteps.first().displayTitle))
        if (commands.isNotEmpty()) add(context.getString(R.string.ui_audio_commands, commands.size, commands.count { it.exitCode == 0 }))
        if (patches.isNotEmpty()) add(context.getString(R.string.ui_audio_commit,
            patches.last().suggestedCommitMessage.ifBlank { context.getString(R.string.ui_patch_title) }))
        session.outputs.firstOrNull()?.pullRequest?.takeIf { it.url.isNotBlank() }?.let {
            add(context.getString(R.string.ui_audio_pull_request, it.title))
        }
    }.joinToString(" ")
}
