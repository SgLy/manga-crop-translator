// ============================================================
// quick-test/test.mjs — Manga Translator Prompt 快速调优脚本
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
// 🎨 下面两个 prompt 就是你要调优的对象，改完直接重新运行！
// ============================================================

const VISUAL_MODEL_SYSTEM_PROMPT = [
  "你是一个专门用于读取日文漫画中的文本块的 OCR 专家系统。下面输入的图片是一页漫画中的一小部分，请严格按照以下的要求，结构化地提取文本：",
  "- 按照两级结构来输出结果，第一级为一个气泡（一个文字块），第二级为气泡内的一列或一行（取决于竖排或横排）；",
  "- 如果图片中能看到气泡的边框或轮廓，就根据气泡的边框来判断气泡。如果看不到边框，就把紧贴在一起的几列或者几行视为一个文字块。有时候，两个气泡可能重叠，此时也应按照文字块来划分；",
  "- 日本漫画阅读顺序为先从右到左，再从上到下。如果出现多块文字，综合文字块之间的位置关系和语义关系来确定它们的顺序；",
  "- 忽略注音假名（Ruby），只保留主文本。忽略拟声词之类的画面中的特效文本；",
  "确保最终输出的内容的顺序和内容都和原始图片完全一致。",
  "调用工具 `submit_ocr_result` 来提交你的读取结果，无需返回任何内容作为回复。",
].join("\n");

const TRANSLATE_MODEL_SYSTEM_PROMPT = [
  "你是一个专业的日语语言学专家，同时非常了解 ACG 文化，阅读过大量日本漫画。接下来，你需要分析结构化的日语对话文本，对其进行词级解析和整句翻译。依次完成以下两个任务，并将结果放入工具调用中：",
  "",
  "一. 重点词汇解析：",
  "   1. 对于输入的每块中的每列，把它们切分为单独的词；",
  "   2. 跳过其中的常见词。常见词包括助词、介词、感叹词、语气词、数词等等没有太确切含义的词，以及意义非常简单的词，例如人称代词，简单的动词等，以及人名地名，这些要跳过；",
  "   3. 选中其中带有实际含义的词汇，特别是名词、动词、形容词、副词、专有名词、外来语等；",
  "   4. 对于没有被跳过的，也就是被选中的每个词，输出：",
  "      i. 原型：去除时态、变体的，在词典中出现的原型；",
  "      ii. 含义：词语的中文对应含义，尽量只用一个词，最好是在本句中的含义，不要列出超过一个近义词，也不要枚举所有可能的释义。另外，如果它是外来语（片假名），给出外来语来源及其原始语言，例如 病历,karte[德]。",
  "      iii. 难度评分：当前词语在所有块所有列的所有词语中的相对难度评分，在 0.0~1.0 之间。难度不需要是词典或语言级别的绝对难度，只要能用来比较当前输入的词语之间的难度即可。",
  "   5. 输出时，依然保持原有的顺序输出。",
  "二. 整句翻译：",
  "   - 对于输入的每块，把它所有的列连接在一起，给出一个完整的、信达雅、符合原文场景和语气的翻译。不需要再按照每列隔开或者单独翻译，而是要给出能够完整表达原文意思的翻译。",
  "",
  "必须调用工具 `submit_translation_result` 来提交你的处理结果，无需返回任何内容作为回复。",
].join("\n");

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
  let contentText = "";
  let reasoningText = "";
  let toolCallArgs = "";
  let firstReasoning = true;
  let firstContent = true;

  for await (const chunk of resp) {
    const delta = chunk.choices?.[0]?.delta;
    if (!delta) continue;

    // ---- 思考过程 (reasoning_content) ----
    const reasoning = delta.reasoning_content;
    if (reasoning) {
      if (firstReasoning) {
        process.stdout.write("\n💭 [思考] ");
        firstReasoning = false;
      }
      process.stdout.write(reasoning);
      reasoningText += reasoning;
    }

    // ---- 正文内容 ----
    if (delta.content) {
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
          toolCallArgs += tc.function.arguments;
        }
      }
    }
  }

  // 末尾换行
  if (!firstReasoning || !firstContent) process.stdout.write("\n");

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
          strict: true,
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
                              definition: { type: "string", description: "简明扼要的中文释义" },
                              difficulty: { type: "number", description: "介于 0 到 1 之间相对难度评分" },
                            },
                            required: ["word", "lemma", "definition", "difficulty"],
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
                console.log(`    ${w.word} | ${w.lemma} | ${w.definition} | 难度=${w.difficulty}`)
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

