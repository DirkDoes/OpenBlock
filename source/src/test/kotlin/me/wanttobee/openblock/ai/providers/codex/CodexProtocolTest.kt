package me.wanttobee.openblock.ai.providers.codex

import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexProtocolTest {
	@Test
	fun modelsOptionalStrictToolParametersAsNullable() {
		val tool = object : AiTool {
			override val name = "sample"
			override val description = "Sample tool"
			override val enabledByDefault = true
			override val parameters = listOf(
				AiToolParameter("required_value", "Required"),
				AiToolParameter("optional_value", "Optional", required = false),
			)

			override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
				return Result.success(AiToolExecution(emptyMap()))
			}
		}
		val schema = CodexProtocol.responseTool(tool).getAsJsonObject("parameters")
		val required = schema
			.getAsJsonArray("required")
			.map { value -> value.asString }
		assertEquals(listOf("required_value", "optional_value"), required)
		assertEquals(
			listOf("string", "null"),
			schema.getAsJsonObject("properties")
				.getAsJsonObject("optional_value")
				.getAsJsonArray("type")
				.map { value -> value.asString },
		)
	}
}
