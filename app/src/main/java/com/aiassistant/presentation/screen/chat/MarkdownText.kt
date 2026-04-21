package com.aiassistant.presentation.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
    fontWeight: FontWeight = MaterialTheme.typography.bodyMedium.fontWeight ?: FontWeight.Normal,
) {
    val parser = Parser.Builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
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
            is TableBlock -> {
                val tableData = extractTableData(child, color, fontSize, fontWeight)
                if (tableData.isNotEmpty()) {
                    paragraphs.add(TableParagraph(tableData))
                }
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
                is TableParagraph -> {
                    RenderTable(
                        tableData = paragraph.tableData,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderTable(
    tableData: List<List<AnnotatedString>>,
    modifier: Modifier = Modifier
) {
    if (tableData.isEmpty()) return

    val numCols = tableData.maxOfOrNull { it.size } ?: 0
    val transposed = if (numCols > 0) {
        (0 until numCols).map { colIdx ->
            tableData.map { row ->
                if (colIdx < row.size) row[colIdx] else AnnotatedString("")
            }
        }
    } else {
        emptyList()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        modifier = modifier
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            transposed.forEachIndexed { colIdx, colData ->
                item(key = "col-$colIdx") {
                    Column(
                        modifier = Modifier
                            .wrapContentHeight()
                            .defaultMinSize(minWidth = 80.dp)
                    ) {
                        colData.forEachIndexed { rowIndex, cellContent ->
                            val isHeader = rowIndex == 0
                            val bgColor = if (isHeader) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                if (rowIndex % 2 == 0) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cellContent,
                                    style = TextStyle(
                                        color = if (isHeader) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontSize = if (isHeader) {
                                            MaterialTheme.typography.bodyMedium.fontSize
                                        } else {
                                            MaterialTheme.typography.bodySmall.fontSize
                                        },
                                        fontWeight = if (isHeader) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                )
                            }

                            if (rowIndex < tableData.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 1.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extractTableData(
    tableNode: TableBlock,
    baseColor: Color,
    baseFontSize: TextUnit,
    baseFontWeight: FontWeight
): List<List<AnnotatedString>> {
    val rows = mutableListOf<List<AnnotatedString>>()
    var rowNode = tableNode.firstChild

    while (rowNode != null) {
        if (rowNode is TableRow) {
            val cells = mutableListOf<AnnotatedString>()
            var cellNode = rowNode.firstChild

            while (cellNode != null) {
                if (cellNode is TableCell) {
                    val cellText = buildAnnotatedString(
                        node = cellNode.firstChild,
                        baseColor = baseColor,
                        baseFontSize = baseFontSize,
                        baseFontWeight = baseFontWeight
                    )
                    cells.add(cellText)
                }
                cellNode = cellNode.next
            }

            rows.add(cells)
        }
        rowNode = rowNode.next
    }

    return rows
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
    val color: Color?
    val fontSize: TextUnit?
    val fontWeight: FontWeight?
    val fontFamily: FontFamily?
}

private data class RegularParagraph(
    val annotatedString: AnnotatedString,
    override val color: Color? = null,
    override val fontSize: TextUnit? = null,
    override val fontWeight: FontWeight? = null,
    override val fontFamily: FontFamily? = null
) : AnnotatedParagraph

private data class CodeParagraph(
    val annotatedString: AnnotatedString,
) : AnnotatedParagraph {
    override val color: Color? = null
    override val fontSize: TextUnit? = null
    override val fontWeight: FontWeight? = null
    override val fontFamily: FontFamily? = null
}

private object AnnotatedHorizontalRule : AnnotatedParagraph {
    override val color: Color? = null
    override val fontSize: TextUnit? = null
    override val fontWeight: FontWeight? = null
    override val fontFamily: FontFamily? = null
}

private data class TableParagraph(
    val tableData: List<List<AnnotatedString>>,
) : AnnotatedParagraph {
    override val color: Color? = null
    override val fontSize: TextUnit? = null
    override val fontWeight: FontWeight? = null
    override val fontFamily: FontFamily? = null
}
