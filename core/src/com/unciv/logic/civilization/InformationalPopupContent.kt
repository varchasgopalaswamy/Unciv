package com.unciv.logic.civilization

/** Text shown by an informational popup, detached from mutable game objects.
 *
 * Text retains the desktop's translation source strings and formatting. Descriptions
 * produced by the technology and building formatters use the current UI language,
 * as they do on the desktop. [paragraphs] preserves the displayed paragraph order;
 * [quote] is separate because the desktop gives quotations their own layout.
 */
data class InformationalPopupContent(
    val title: String,
    val paragraphs: List<String>,
    val acknowledgement: String,
    val quote: String? = null,
)
