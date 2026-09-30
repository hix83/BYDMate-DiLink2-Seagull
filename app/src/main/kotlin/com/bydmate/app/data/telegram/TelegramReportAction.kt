package com.bydmate.app.data.telegram

import com.bydmate.app.data.local.entity.ActionDef
import org.json.JSONObject

const val TELEGRAM_REPORT_KIND = "telegram_report"
const val TELEGRAM_REPORT_RULE_KEY = "rule"

private fun reportPayload(payload: String?): JSONObject =
    runCatching { JSONObject(payload ?: "{}") }.getOrElse { JSONObject() }

fun ActionDef.withReportRuleName(ruleName: String): ActionDef {
    if (kind != TELEGRAM_REPORT_KIND) return this
    return copy(payload = reportPayload(payload).put(TELEGRAM_REPORT_RULE_KEY, ruleName).toString())
}

fun ActionDef.reportFields(): Set<ReportField> {
    val json = reportPayload(payload)
    return if (json.has("fields")) ReportField.fromJson(json.optJSONArray("fields")) else ReportField.DEFAULT
}

fun ActionDef.reportText(): String = reportPayload(payload).optString("text")

fun ActionDef.reportRuleName(): String? = reportPayload(payload).optString(TELEGRAM_REPORT_RULE_KEY).ifBlank { null }

fun ActionDef.withTelegramReport(fields: Set<ReportField>, text: String): ActionDef = copy(
    payload = JSONObject().put("fields", ReportField.toJson(fields)).put("text", text).toString(),
)

