/**
 * Spike 2: 视觉原语路径 —— 工具内部直调 DashScope VLM（不经 pi-ai）。
 * 验证 look/describe 工具的实现方式：OpenAI 兼容 chat.completions + image_url(data URL)。
 *
 * 运行：DASHSCOPE_API_KEY=sk-xxx node spike/02-vlm-direct.mjs <image.png> [vlm-model]
 */
import { readFile } from "node:fs/promises";

const image = process.argv[2] ?? "../data/e2e/package.png";
const vlmModel = process.argv[3] ?? "qwen3-vl-plus";
if (!process.env.DASHSCOPE_API_KEY) {
	console.error("missing DASHSCOPE_API_KEY");
	process.exit(1);
}

const b64 = (await readFile(image)).toString("base64");
console.log(`[spike2] image=${image} model=${vlmModel} bytes=${b64.length}`);

const t0 = Date.now();
const resp = await fetch("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", {
	method: "POST",
	headers: {
		Authorization: `Bearer ${process.env.DASHSCOPE_API_KEY}`,
		"Content-Type": "application/json",
	},
	body: JSON.stringify({
		model: vlmModel,
		messages: [
			{
				role: "user",
				content: [
					{ type: "image_url", image_url: { url: `data:image/png;base64,${b64}` } },
					{ type: "text", text: "这是商品包装图。列出图中出现的卡通角色形象和主要文字（各最多10项）。JSON 格式：{\"characters\":[],\"texts\":[]}" },
				],
			},
		],
	}),
});
const data = await resp.json();
if (!resp.ok) {
	console.error("[spike2] HTTP", resp.status, JSON.stringify(data).slice(0, 500));
	process.exit(1);
}
const msg = data.choices?.[0]?.message;
console.log(`[spike2] ${Date.now() - t0}ms, usage=${JSON.stringify(data.usage)}`);
console.log("[spike2] answer:", (msg?.content ?? "").slice(0, 600));
