/**
 * Spike 1: DashScope 自定义 provider (openai-completions) + qwen3-max
 * + pi extension 注册的自定义工具 —— 验证 tool calling 全链路。
 *
 * 运行：DASHSCOPE_API_KEY=sk-xxx node spike/01-dashscope-toolcall.mjs
 */
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
	createAgentSession,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const here = path.dirname(fileURLToPath(import.meta.url));
const agentDir = path.resolve(here, "../pi");
process.env.PI_CODING_AGENT_DIR = agentDir;

if (!process.env.DASHSCOPE_API_KEY) {
	console.error("missing DASHSCOPE_API_KEY");
	process.exit(1);
}

// 能力 = pi extension 注册的自定义工具（va-brain 的全部业务能力都走这个机制）
const resourceLoader = new DefaultResourceLoader({
	cwd: process.cwd(),
	agentDir,
	extensionFactories: [
		(pi) => {
			pi.registerTool({
				name: "get_ip_library",
				label: "IP 标准库查询",
				description: "查询指定项目(bizno)的 IP 角色标准库，返回角色名列表。",
				parameters: Type.Object({
					bizno: Type.String({ description: "项目编号" }),
				}),
				execute: async (_toolCallId, params, _signal, _onUpdate, _ctx) => ({
					content: [
						{
							type: "text",
							text: JSON.stringify({
								bizno: params.bizno,
								characters: ["库洛米", "美乐蒂", "帕恰狗"],
							}),
						},
					],
					details: {},
				}),
			});
		},
	],
});
await resourceLoader.reload();

const modelRuntime = await ModelRuntime.create();
const model = modelRuntime.getModel("dashscope", "qwen3.8-max");
if (!model) {
	console.error("model dashscope/qwen3.8-max not found in registry (models.json 未生效?)");
	process.exit(1);
}

const { session } = await createAgentSession({
	model,
	modelRuntime,
	resourceLoader,
	sessionManager: SessionManager.inMemory(),
	noTools: true,
});

try {
	session.subscribe((event) => {
		console.log("[event]", event.type, event.type === "message_end" ? JSON.stringify(event.message).slice(0, 600) : "");
	});

	await session.prompt("项目 demo-001 的标准库里有哪些角色？必须调用工具查询后回答。");
	console.log("\n=== final answer ===");
	console.log(session.getLastAssistantText());
	console.log("=== messages ===");
	for (const m of session.messages) {
		console.log(m.role, JSON.stringify(m).slice(0, 500));
	}
} catch (err) {
	console.error("[prompt error]", err);
} finally {
	session.dispose();
}
