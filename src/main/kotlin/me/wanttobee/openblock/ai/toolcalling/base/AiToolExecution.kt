package me.wanttobee.openblock.ai.toolcalling.base

data class AiToolExecution(
	val payload: Map<String, Any?>,
	val isError: Boolean = false,
) {
	fun asResponseMap(): Map<String, Any?> {
		return if (isError) {
			mapOf("error" to payload)
		} else {
			mapOf("output" to payload)
		}
	}
}
