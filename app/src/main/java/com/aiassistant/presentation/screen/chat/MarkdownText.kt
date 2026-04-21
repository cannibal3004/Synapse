package com.aiassistant.presentation.screen.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.node.Code
import org.commonmark.node.Link
import org.commonmark.node.Heading
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.OrderedList
import org.commonmark.node.ListItem
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.parser.Parser

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
    fontWeight: FontWeight = MaterialTheme.typography.bodyMedium.fontWeight ?: FontWeight.Normal,
) {
    val parser = Parser.Builder().build()
    val document = parser.parse(markdown)

    val paragraphs = mutableListOf<AnnotatedParagraph>()

    var child = document.firstChild
    while (child != null) {
        when (child) {
            is Heading -> {
                val level = (child as Heading).level
                val style = when (level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    4 -> MaterialTheme.typography.titleSmall
                    5 -> MaterialTheme.typography.bodyLarge
                    else -> MaterialTheme.typography.bodyMedium
                }
                paragraphs.add(
                    RegularParagraph(
                        annotatedString = buildAnnotatedString(
                            node = child.firstChild,
                            baseColor = style.color,
                            baseFontSize = style.fontSize,
                            baseFontWeight = style.fontWeight ?: FontWeight.Normal
                        )
                    )
                )
            }
            is org.commonmark.node.Paragraph -> {
                val annotatedString = buildAnnotatedString(
                    node = child.firstChild,
                    baseColor = color,
                    baseFontSize = fontSize,
                    baseFontWeight = fontWeight
                )
                if (annotatedString.text.isNotBlank()) {
                    paragraphs.add(RegularParagraph(annotatedString))
                }
            }
            is Code, is FencedCodeBlock -> {
                val code = when (child) {
                    is Code -> child.literal
                    is FencedCodeBlock -> child.literal
                    else -> ""
                }
                val textNode = Text().also { it.literal = code.trim() }
                paragraphs.add(
                    CodeParagraph(
                        annotatedString = buildAnnotatedString(
                            node = textNode,
                            baseColor = color,
                            baseFontSize = 12.sp,
                            baseFontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                )
            }
            is BlockQuote -> {
                val sb = StringBuilder()
                var blockChild = child.firstChild
                while (blockChild != null) {
                    if (blockChild is org.commonmark.node.Paragraph) {
                        sb.append(
                            buildAnnotatedString(
                                node = blockChild.firstChild,
                                baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                baseFontSize = fontSize,
                                baseFontWeight = fontWeight
                            ).text
                        )
                    }
                    blockChild = blockChild.next
                }
                if (sb.isNotBlank()) {
                    paragraphs.add(
                        RegularParagraph(
                            annotatedString = AnnotatedString(sb.toString()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            is BulletList -> {
                var listItem = child.firstChild
                while (listItem != null) {
                    if (listItem is ListItem) {
                        val listContent = buildAnnotatedString(
                            node = listItem.firstChild,
                            baseColor = color,
                            baseFontSize = fontSize,
                            baseFontWeight = fontWeight
                        )
                        paragraphs.add(
                            RegularParagraph(
                                annotatedString = AnnotatedString("• ${listContent.text}")
                            )
                        )
                    }
                    listItem = listItem.next
                }
            }
            is OrderedList -> {
                var listItem = child.firstChild
                var idx = 0
                while (listItem != null) {
                    idx++
                    if (listItem is ListItem) {
                        val listContent = buildAnnotatedString(
                            node = listItem.firstChild,
                            baseColor = color,
                            baseFontSize = fontSize,
                            baseFontWeight = fontWeight
                        )
                        paragraphs.add(
                            RegularParagraph(
                                annotatedString = AnnotatedString("$idx. ${listContent.text}")
                            )
                        )
                    }
                    listItem = listItem.next
                }
            }
            is ThematicBreak -> {
                paragraphs.add(AnnotatedHorizontalRule)
            }
        }
        child = child.next
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        paragraphs.forEach { paragraph ->
            when (paragraph) {
                is RegularParagraph -> {
                    AnnotatedStringText(
                        annotatedString = paragraph.annotatedString,
                        color = paragraph.color,
                        fontSize = paragraph.fontSize,
                        fontWeight = paragraph.fontWeight,
                        fontFamily = paragraph.fontFamily
                    )
                }
                is CodeParagraph -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnnotatedStringText(
                            annotatedString = paragraph.annotatedString,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                is AnnotatedHorizontalRule -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun AnnotatedStringText(
    annotatedString: AnnotatedString,
    color: Color? = null,
    fontSize: TextUnit? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    modifier: Modifier = Modifier
) {
    val style = TextStyle(
        color = color ?: MaterialTheme.colorScheme.onSurface,
        fontSize = fontSize ?: MaterialTheme.typography.bodyMedium.fontSize,
        fontWeight = fontWeight ?: MaterialTheme.typography.bodyMedium.fontWeight,
        fontFamily = fontFamily ?: FontFamily.SansSerif
    )
    Text(
        text = annotatedString,
        style = style,
        modifier = modifier
    )
}

private fun buildAnnotatedString(
    node: Node?,
    baseColor: Color,
    baseFontSize: TextUnit,
    baseFontWeight: FontWeight,
    fontFamily: FontFamily? = null
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var current = node
    while (current != null) {
        when (current) {
            is Text -> {
                builder.append(current.literal)
            }
            is StrongEmphasis -> {
                val inner = buildAnnotatedString(
                    node = current.firstChild,
                    baseColor = baseColor,
                    baseFontSize = baseFontSize,
                    baseFontWeight = baseFontWeight,
                    fontFamily = fontFamily
                )
                builder.pushStyle(
                    TextStyle(
                        color = baseColor,
                        fontSize = baseFontSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = fontFamily
                    ).toSpanStyle()
                )
                builder.append(inner)
                builder.pop()
            }
            is Emphasis -> {
                val inner = buildAnnotatedString(
                    node = current.firstChild,
                    baseColor = baseColor,
                    baseFontSize = baseFontSize,
                    baseFontWeight = baseFontWeight,
                    fontFamily = fontFamily
                )
                builder.pushStyle(
                    TextStyle(
                        color = baseColor,
                        fontSize = baseFontSize,
                        fontStyle = FontStyle.Italic,
                        fontFamily = fontFamily
                    ).toSpanStyle()
                )
                builder.append(inner)
                builder.pop()
            }
            is Code -> {
                builder.pushStyle(
                    TextStyle(
                        color = baseColor,
                        fontSize = baseFontSize,
                        fontFamily = FontFamily.Monospace
                    ).toSpanStyle()
                )
                builder.append("`${current.literal}`")
                builder.pop()
            }
            is Link -> {
                val inner = buildAnnotatedString(
                    node = current.firstChild,
                    baseColor = baseColor,
                    baseFontSize = baseFontSize,
                    baseFontWeight = baseFontWeight,
                    fontFamily = fontFamily
                )
                builder.append(inner)
            }
            is org.commonmark.node.Paragraph -> {
                builder.append(
                    buildAnnotatedString(
                        node = current.firstChild,
                        baseColor = baseColor,
                        baseFontSize = baseFontSize,
                        baseFontWeight = baseFontWeight,
                        fontFamily = fontFamily
                    )
                )
            }
        }
        current = current.next
    }
    return builder.toAnnotatedString()
}

private sealed interface AnnotatedParagraph {
    val annotatedString: AnnotatedString
    val color: Color?
    val fontSize: TextUnit?
    val fontWeight: FontWeight?
    val fontFamily: FontFamily?
}

private data class RegularParagraph(
    override val annotatedString: AnnotatedString,
    override val color: Color? = null,
    override val fontSize: TextUnit? = null,
    override val fontWeight: FontWeight? = null,
    override val fontFamily: FontFamily? = null
) : AnnotatedParagraph

private data class CodeParagraph(
    override val annotatedString: AnnotatedString,
) : AnnotatedParagraph {
    override val color: Color? = null
    override val fontSize: TextUnit? = null
    override val fontWeight: FontWeight? = null
    override val fontFamily: FontFamily? = null
}

private object AnnotatedHorizontalRule : AnnotatedParagraph {
    override val annotatedString: AnnotatedString
        get() = throw UnsupportedOperationException()
    override val color: Color? = null
    override val fontSize: TextUnit? = null
    override val fontWeight: FontWeight? = null
    override val fontFamily: FontFamily? = null
}
