// ============================================================
// quick-test/test.mjs — Manga Crop Translator Prompt 快速调优脚本
// 用法: node test.mjs visual    (测试 VisualModel OCR)
//       node test.mjs translate    (测试 TranslateModel 翻译)
// 改完下面的 prompt 后直接重新运行即可看效果
// ============================================================

import OpenAI from "openai";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------- 读取 .env ----------
function loadEnv(filePath) {
  const content = fs.readFileSync(filePath, "utf-8");
  for (const line of content.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eqIdx = trimmed.indexOf("=");
    if (eqIdx === -1) continue;
    const key = trimmed.slice(0, eqIdx).trim();
    const val = trimmed.slice(eqIdx + 1).trim();
    if (!process.env[key]) process.env[key] = val;
  }
}
loadEnv(path.join(__dirname, ".env"));

// ============================================================
// 🎨 Prompt 的唯一来源是 app/src/main/java/me/sgly/translator/prompts/*.md，改完直接重新运行！
// ============================================================

const PROMPTS_DIR = path.join(
  __dirname,
  "..",
  "app",
  "src",
  "main",
  "java",
  "me",
  "sgly",
  "translator",
  "prompts",
);

const readPrompt = (name) => fs.readFileSync(path.join(PROMPTS_DIR, name), "utf-8").trimEnd();

const VISUAL_MODEL_SYSTEM_PROMPT = readPrompt("visual.md");
const TRANSLATE_MODEL_SYSTEM_PROMPT = readPrompt("translate.md");

// ============================================================
// Thinking 配置构建
// "disabled" / "enabled" → thinking: { type: "disabled" | "enabled" }
// "low" / "medium" / "high" → reasoning_effort: "low" | "medium" | "high"
// 未设置 → 不传（使用模型默认）
// ============================================================
function buildThinkingConfig(envValue) {
  if (!envValue) return {};
  switch (envValue) {
    case "disabled":
    case "enabled":
      return { thinking: { type: envValue } };
    case "low":
    case "medium":
    case "high":
      return { reasoning_effort: envValue };
    default:
      console.warn(`⚠️ 未知的 thinking 值: "${envValue}"，已忽略`);
      return {};
  }
}

// ============================================================
// 流式读取辅助 — 实时输出思考过程和内容
// ============================================================
async function streamResponse(resp) {
  const startedAt = Date.now();
  const records = [];
  let contentText = "";
  let reasoningText = "";
  let toolCallArgs = "";
  let firstReasoning = true;
  let firstContent = true;
  let chunkCount = 0;
  const usageChunks = [];

  for await (const chunk of resp) {
    chunkCount++;

    // ---- usage 诊断 ----
    // include_usage 的那个 chunk，choices 是空数组、只带 usage，所以会掉进下面的
    // `!delta → continue` 被丢掉。有些服务端则是每一块都带一份累积值。先不管 delta 直接抓。
    if (chunk.usage) {
      usageChunks.push({
        index: chunkCount,
        choices: chunk.choices?.length ?? 0,
        usage: chunk.usage,
      });
    }

    const delta = chunk.choices?.[0]?.delta;
    if (!delta) continue;

    // ---- 思考过程 (reasoning_content) ----
    const reasoning = delta.reasoning_content;
    if (reasoning) {
      records.push({ t: Date.now() - startedAt, kind: "reasoning", length: reasoning.length });
      if (firstReasoning) {
        process.stdout.write("\n💭 [思考] ");
        firstReasoning = false;
      }
      process.stdout.write(reasoning);
      reasoningText += reasoning;
    }

    // ---- 正文内容 ----
    if (delta.content) {
      records.push({ t: Date.now() - startedAt, kind: "content", length: delta.content.length });
      if (firstContent) {
        if (!firstReasoning) process.stdout.write("\n\n📝 [输出] ");
        firstContent = false;
      }
      process.stdout.write(delta.content);
      contentText += delta.content;
    }

    // ---- 工具调用 ----
    if (delta.tool_calls) {
      for (const tc of delta.tool_calls) {
        if (tc.function?.arguments) {
          records.push({ t: Date.now() - startedAt, kind: "arguments", length: tc.function.arguments.length });
          toolCallArgs += tc.function.arguments;
        }
      }
    }
  }

  // 末尾换行
  if (!firstReasoning || !firstContent) process.stdout.write("\n");

  // ---- chunk 时序记录 ----
  fs.writeFileSync(path.join(__dirname, "chunks.json"), JSON.stringify(records), "utf-8");
  console.log(`\n🧩 已写入 chunks.json（${records.length} 条）`);

  // ---- usage 汇总 ----
  console.log(`\n📊 共收到 ${chunkCount} 个 chunk，其中带 usage 的有 ${usageChunks.length} 个`);
  for (const u of usageChunks.slice(0, 3)) {
    console.log(`   #${u.index} choices=${u.choices} ${JSON.stringify(u.usage)}`);
  }
  if (usageChunks.length > 3) {
    const last = usageChunks[usageChunks.length - 1];
    console.log(
      `   … 共 ${usageChunks.length} 个，最后是 #${last.index} choices=${last.choices} ${JSON.stringify(last.usage)}`,
    );
  }

  return { contentText, reasoningText, toolCallArgs };
}

// ============================================================
// VisualModel OCR Test — 对应 OpenAIApiClient.extractTextFromImage()
// ============================================================
async function testVisualModel() {
  const apiKey = process.env.VISUAL_MODEL_API_KEY;
  const apiBase = process.env.VISUAL_MODEL_API_BASE;
  const model = process.env.VISUAL_MODEL_NAME;
  const thinkingVal = process.env.VISUAL_MODEL_THINKING || "disabled";

  if (!apiKey || apiKey === "sk-your-key-here") {
    console.error("❌ 请先在 .env 中配置 VISUAL_MODEL_API_KEY");
    process.exit(1);
  }

  const imagePath = path.join(__dirname, "crop.png");
  if (!fs.existsSync(imagePath)) {
    console.error(`❌ 找不到图片: ${imagePath}`);
    console.error("   请把 crop.png 放到 quick-test/ 目录下");
    process.exit(1);
  }

  const imageBuffer = fs.readFileSync(imagePath);
  const base64Image = imageBuffer.toString("base64");

  const client = new OpenAI({ apiKey, baseURL: apiBase });
  const thinkingConfig = buildThinkingConfig(thinkingVal);

  console.log(`\n🚀 VisualModel OCR Test`);
  console.log(`   API: ${apiBase}`);
  console.log(`   Model: ${model}`);
  console.log(`   Thinking: ${thinkingVal}`);
  console.log(`   Image: crop.png (${(imageBuffer.length / 1024).toFixed(1)} KB)`);
  console.log(`\n--- System Prompt ---`);
  console.log(VISUAL_MODEL_SYSTEM_PROMPT);
  console.log(`--- Sending request... ---`);

  const startTime = Date.now();

  try {
    const resp = await client.chat.completions.create({
      model,
      stream: true,
      ...thinkingConfig,
      messages: [
        { role: "system", content: VISUAL_MODEL_SYSTEM_PROMPT },
        {
          role: "user",
          content: [
            {
              type: "image_url",
              image_url: { url: `data:image/png;base64,${base64Image}` },
            },
          ],
        },
      ],
      tools: [
        {
          type: "function",
          function: {
            name: "submit_ocr_result",
            description: "提交提取结果",
            parameters: {
              type: "object",
              properties: {
                blocks: {
                  type: "array",
                  description: "所有识别到的文字块列表",
                  items: {
                    type: "array",
                    items: {
                      type: "string",
                      description: "列或行的文字内容",
                    },
                  },
                },
              },
              required: ["blocks"],
              additionalProperties: false,
            },
          },
        },
      ],
      tool_choice: "auto",
      stream_options: { include_usage: true },
    });

    const { contentText, reasoningText, toolCallArgs } = await streamResponse(resp);

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

    console.log(`\n✅ 完成 (${elapsed}s)`);

    // ---- 解析结果 ----
    const raw = toolCallArgs || contentText;
    console.log(`\n--- Raw Output ---`);
    console.log(raw);

    try {
      const parsed = JSON.parse(raw.trim());
      console.log(`\n--- Parsed OcrResult ---`);
      console.log(JSON.stringify(parsed, null, 2));
      console.log(`\n--- Readable ---`);
      if (parsed.blocks) {
        parsed.blocks.forEach((block, i) => {
          console.log(`[Block ${i + 1}]`);
          block.forEach((line, j) => console.log(`  Col${j + 1}: ${line}`));
        });
      }

      // 自动写入 ocr-result.json，方便直接跑 translate 测试
      const resultPath = path.join(__dirname, "ocr-result.json");
      fs.writeFileSync(resultPath, JSON.stringify(parsed, null, 2), "utf-8");
      console.log(`\n📄 已写入 ocr-result.json → 可直接 node test.mjs translate`);
    } catch {
      console.log(`\n⚠️ 无法解析为 JSON，请检查 prompt 或模型输出`);
    }
  } catch (err) {
    console.error(`\n❌ 请求失败:`, err.message);
    if (err.response) {
      console.error(`   Status: ${err.status}`);
      console.error(`   Body:`, err.response.body);
    }
  }
}

// ============================================================
// TranslateModel Translation Test — 对应 OpenAIApiClient.translateJapanese()
// ============================================================
async function testTranslateModel() {
  const apiKey = process.env.TRANSLATE_MODEL_API_KEY;
  const apiBase = process.env.TRANSLATE_MODEL_API_BASE;
  const model = process.env.TRANSLATE_MODEL_NAME;
  const thinkingVal = process.env.TRANSLATE_MODEL_THINKING || "enabled";

  if (!apiKey || apiKey === "sk-your-key-here") {
    console.error("❌ 请先在 .env 中配置 TRANSLATE_MODEL_API_KEY");
    process.exit(1);
  }

  // 读取测试用日语文本（来自命令行参数或默认）
  const inputArg = process.argv[3];
  let japaneseText;
  if (inputArg) {
    const maybePath = path.resolve(inputArg);
    if (fs.existsSync(maybePath) && fs.statSync(maybePath).isFile()) {
      japaneseText = fs.readFileSync(maybePath, "utf-8").trim();
      console.log(`📄 从文件读取: ${maybePath}`);
    } else {
      japaneseText = inputArg;
    }
  } else {
    const defaultPath = path.join(__dirname, "ocr-result.json");
    if (fs.existsSync(defaultPath)) {
      japaneseText = fs.readFileSync(defaultPath, "utf-8").trim();
      console.log(`📄 从 ocr-result.json 读取`);
    } else {
      console.error("❌ 请提供日语文本：node test.mjs translate <文本或文件路径>");
      console.error("   或者创建 quick-test/ocr-result.json 放 OcrResult JSON");
      process.exit(1);
    }
  }

  const client = new OpenAI({ apiKey, baseURL: apiBase });
  const thinkingConfig = buildThinkingConfig(thinkingVal);

  console.log(`\n🚀 TranslateModel Translation Test`);
  console.log(`   API: ${apiBase}`);
  console.log(`   Model: ${model}`);
  console.log(`   Thinking: ${thinkingVal}`);
  console.log(`\n--- Input ---`);
  console.log(japaneseText.slice(0, 500) + (japaneseText.length > 500 ? "..." : ""));
  console.log(`\n--- System Prompt ---`);
  console.log(TRANSLATE_MODEL_SYSTEM_PROMPT);
  console.log(`--- Sending request... ---`);

  const startTime = Date.now();

  try {
    const resp = await client.chat.completions.create({
      model,
      stream: true,
      ...thinkingConfig,
      messages: [
        { role: "system", content: TRANSLATE_MODEL_SYSTEM_PROMPT },
        { role: "user", content: japaneseText },
      ],
      tools: [
        {
          type: "function",
          function: {
            name: "submit_translation_result",
            description: "提交翻译结果",
            parameters: {
              type: "object",
              properties: {
                results: {
                  type: "array",
                  items: {
                    type: "object",
                    properties: {
                      columns: {
                        type: "array",
                        items: {
                          type: "array",
                          items: {
                            type: "object",
                            properties: {
                              word: { type: "string", description: "原文出现的原词" },
                              lemma: { type: "string", description: "该词去掉变体后的辞书原型" },
                              reading: { type: "string", description: "原型的读音，写成平假名，例如 天気 → てんき" },
                              definition: { type: "string", description: "简明扼要的中文释义" },
                              origin: { type: ["string", "null"], description: "外来语词源，格式「原词[原语言]」，例如 karte[德]；非外来语时为 null" },
                              difficulty: { type: ["number", "null"], description: "介于 0 到 1 之间相对难度评分" },
                            },
                            required: ["word", "lemma", "reading", "definition", "origin", "difficulty"],
                            additionalProperties: false,
                          },
                        },
                      },
                      translation: { type: "string", description: "本块文字的完整翻译结果" },
                    },
                    required: ["columns", "translation"],
                    additionalProperties: false,
                  },
                },
              },
              required: ["results"],
              additionalProperties: false,
            },
          },
        },
      ],
      tool_choice: "auto",
      stream_options: { include_usage: true },
    });

    const { contentText, reasoningText, toolCallArgs } = await streamResponse(resp);

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

    console.log(`\n✅ 完成 (${elapsed}s)`);

    // ---- 解析结果 ----
    const raw = toolCallArgs || contentText;
    console.log(`\n--- Raw Output ---`);
    console.log(raw);

    try {
      const parsed = JSON.parse(raw.trim());
      console.log(`\n--- Parsed TranslationResult ---`);
      console.log(JSON.stringify(parsed, null, 2));
      console.log(`\n--- Readable ---`);
      if (parsed.results) {
        parsed.results.forEach((item, i) => {
          console.log(`\n[Block ${i + 1}]`);
          console.log(`  翻译: ${item.translation}`);
          if (item.columns) {
            item.columns.forEach((col, j) => {
              console.log(`  Col${j + 1} 词汇:`);
              col.forEach((w) =>
                console.log(`    ${w.word} | ${w.lemma} | ${w.reading} | ${w.definition} | 词源=${w.origin ?? "-"} | 难度=${w.difficulty}`)
              );
            });
          }
        });
      }
    } catch {
      console.log(`\n⚠️ 无法解析为 JSON，请检查 prompt 或模型输出`);
    }
  } catch (err) {
    console.error(`\n❌ 请求失败:`, err.message);
    if (err.response) {
      console.error(`   Status: ${err.status}`);
      console.error(`   Body:`, err.response.body);
    }
  }
}

// ============================================================
// Main
// ============================================================
const mode = process.argv[2];

if (mode === "visual") {
  await testVisualModel();
} else if (mode === "translate") {
  await testTranslateModel();
} else {
  console.log("用法:");
  console.log("  node test.mjs visual             测试 VisualModel OCR（读取 crop.png）");
  console.log("  node test.mjs translate             测试 TranslateModel 翻译（读取 ocr-result.json）");
  console.log("  node test.mjs translate \"日本語\"   测试 TranslateModel 翻译（传入文本）");
}

