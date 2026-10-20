package me.sgly.translator.api

import kotlinx.schema.Description
import kotlinx.serialization.Serializable

@Serializable
data class OcrResult(
    @Description("识别到的所有文字块，每个文字块是块内按行或列切分出的字符串组成的数组")
    val blocks: List<List<String>>,
)

@Serializable
data class TranslationWord(
    @Description("原文出现的原词")
    val word: String,
    @Description("该词去掉变体后的辞书原型")
    val lemma: String,
    @Description("原型的读音，写成平假名，例如 天気 → てんき")
    val reading: String,
    @Description("简明扼要的中文释义")
    val definition: String,
    @Description("外来语词源，格式「原词[原语言]」，例如 karte[德]；非外来语时为 null")
    val origin: String?,
    @Description("介于 0 到 1 之间相对难度评分")
    val difficulty: Double?,
)

@Serializable
data class TranslationResultItem(
    @Description("重点词汇解析，按列划分的二维词汇数组")
    val columns: List<List<TranslationWord>>,
    @Description("本块文字的完整翻译结果")
    val translation: String,
)

@Serializable
data class TranslationResult(
    @Description("文本块翻译结果")
    val results: List<TranslationResultItem>,
)
