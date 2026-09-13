package me.sgly.translator

import kotlinx.serialization.Serializable
import kotlinx.schema.Description

@Serializable
data class OcrResult(
    @Description("所有识别到的主分类文字块列表，块内的文字按行或列进行分割的数组")
    val blocks: List<List<String>>
)

@Serializable
data class TranslationWord(
    @Description("原文出现的原词")
    val word: String,
    @Description("该词去掉变体后的辞书原型")
    val lemma: String,
    @Description("简明扼要的中文释义")
    val definition: String,
    @Description("外来语词源，格式「原词[原语言]」，例如 karte[德]；非外来语时为 null")
    val origin: String?,
    @Description("介于 0 到 1 之间相对难度评分")
    val difficulty: Double?
)

@Serializable
data class TranslationResultItem(
    @Description("重点词汇解析，按列划分的二维词汇数组")
    val columns: List<List<TranslationWord>>,
    @Description("本块文字的完整翻译结果")
    val translation: String
)

@Serializable
data class TranslationResult(
    @Description("文本块翻译结果")
    val results: List<TranslationResultItem>
)
