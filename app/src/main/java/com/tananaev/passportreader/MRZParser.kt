package com.tananaev.passportreader

import com.google.mlkit.vision.text.Text
import org.jmrtd.lds.icao.MRZInfo
import java.util.regex.Pattern

object MRZParser {

    private val REGEX_OLD_PASSPORT = "(?<documentNumber>[A-Z0-9<]{9})(?<checkDigitDocumentNumber>[0-9ILDSOG]{1})(?<nationality>[A-Z<]{3})(?<dateOfBirth>[0-9ILDSOG]{6})(?<checkDigitDateOfBirth>[0-9ILDSOG]{1})(?<sex>[FM<]){1}(?<expirationDate>[0-9ILDSOG]{6})(?<checkDigitExpiration>[0-9ILDSOG]{1})"
    private val REGEX_IP_PASSPORT_LINE_1 = "\\bIP[A-Z<]{3}[A-Z0-9<]{9}[0-9]{1}"
    private val REGEX_IP_PASSPORT_LINE_2 = "[0-9]{6}[0-9]{1}[FM<]{1}[0-9]{6}[0-9]{1}[A-Z<]{3}"

    private val MRZ_FIRSTLINE = Regex("([A-Z])([A-Z0-9<])([A-Z]{3})([A-Z<]{36,39})")
    private val MRZ_SECONDLINE = Regex("([A-Z0-9<]{9})([0-9ILDSOG])([A-Z<]{3})([0-9ILDSOG]{6})([0-9ILDSOG])([MF<])([0-9ILDSOG]{6})([0-9ILDSOG])([A-Z0-9<]{14})([0-9ILDSOG])([0-9ILDSOG])")

    data class ParsedMRZ(
        val documentNumber: String,
        val dateOfBirth: String,
        val expirationDate: String
    )

    // Regex that matches ALL unicode characters that OCR may misread from passport '<' filler:
    // - Guillemets: « » ‹ ›
    // - CJK angle brackets: 〈 〉 《 》 〔 〕
    // - Mathematical angle brackets: ⟨ ⟩ ⟪ ⟫
    // - Much less/greater than: ≪ ≫
    // - Triangles & arrows: ▲ △ ▶ ► ▷ ▼ ▽ ◀ ◁ ◄ ◅
    // - Chevrons: ‹ › ˂ ˃ ˄ ˅
    // - Fullwidth less/greater: ＜ ＞
    // - Carets, braces, brackets, parens
    // - Lowercase c/k (never valid in MRZ)
    private val FILLER_CHAR_REGEX = Regex(
        "[«»‹›〈〉《》〔〕⟨⟩⟪⟫≪≫" +
        "▲△▶►▷▼▽◀◁◄◅" +
        "˂˃˄˅＜＞" +
        "\\^{}\\[\\]()ck>]"
    )

    fun normalizeLine(text: String): String {
        // 1. Remove all standard and unicode whitespace characters
        var result = text.replace("\\s+".toRegex(), "")
            .replace("\u00A0", "")  // Non-breaking space
            .replace("\u2007", "")  // Figure space
            .replace("\u202F", "")  // Narrow no-break space
            .replace("\u200B", "")  // Zero-width space
            .replace("\u2060", "")  // Word joiner
            .replace("\uFEFF", "")  // BOM / Zero-width no-break space

        // 2. Normalize ALL unicode angle/chevron/guillemet/triangle variants to '<'
        result = FILLER_CHAR_REGEX.replace(result, "<")

        // 3. Collapse consecutive '<' (e.g. "<<<" stays "<<<", already correct)
        // But normalize uppercase 'C' acting as filler character noise
        result = result.replace(Regex("<C+<"), "<")
        result = result.replace(Regex("<C+"), "<")
        result = result.replace(Regex("C+<"), "<")
        result = result.replace(Regex("C+$"), "<")
        result = result.replace(Regex("^C+"), "<")

        return result.uppercase()
    }

    /**
     * Data class to hold a text fragment with its bounding box position
     */
    private data class TextFragment(
        val text: String,
        val centerY: Float,
        val left: Float
    )

    fun parse(results: Text): ParsedMRZ? {
        // ============================================================
        // STEP 1: Collect ALL text elements with bounding box positions
        // ============================================================
        val fragments = mutableListOf<TextFragment>()
        for (block in results.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox
                    if (box != null) {
                        val cleaned = normalizeLine(element.text)
                        if (cleaned.isNotEmpty()) {
                            fragments.add(TextFragment(
                                text = cleaned,
                                centerY = (box.top + box.bottom) / 2f,
                                left = box.left.toFloat()
                            ))
                        }
                    }
                }
                // Also add the whole line as a fragment in case elements are merged
                val lineBox = line.boundingBox
                if (lineBox != null) {
                    val cleaned = normalizeLine(line.text)
                    if (cleaned.isNotEmpty()) {
                        fragments.add(TextFragment(
                            text = cleaned,
                            centerY = (lineBox.top + lineBox.bottom) / 2f,
                            left = lineBox.left.toFloat()
                        ))
                    }
                }
            }
        }

        // ============================================================
        // STEP 2: Group fragments by similar Y position (same row)
        // Tolerance: fragments within 20px vertical distance = same line
        // ============================================================
        val yTolerance = 20f
        val sortedByY = fragments.sortedBy { it.centerY }
        val rowGroups = mutableListOf<MutableList<TextFragment>>()

        for (frag in sortedByY) {
            val matchedGroup = rowGroups.find { group ->
                group.any { kotlin.math.abs(it.centerY - frag.centerY) < yTolerance }
            }
            if (matchedGroup != null) {
                matchedGroup.add(frag)
            } else {
                rowGroups.add(mutableListOf(frag))
            }
        }

        // ============================================================
        // STEP 3: For each row, sort fragments left-to-right by X,
        //         concatenate them, and pad with '<' to 44 characters
        // ============================================================
        val mergedLines = mutableListOf<String>()
        for (group in rowGroups) {
            val sortedByX = group.sortedBy { it.left }
            val merged = sortedByX.joinToString("") { it.text }
            if (merged.isNotEmpty()) {
                mergedLines.add(merged)
                // Also add a padded version (fill gaps with '<' to reach 44 chars)
                if (merged.length in 20..43) {
                    val padded = merged.padEnd(44, '<')
                    mergedLines.add(padded)
                }
            }
        }

        // Also add individual lines from the original blocks (original approach)
        for (block in results.textBlocks) {
            for (line in block.lines) {
                val cleaned = normalizeLine(line.text)
                if (cleaned.isNotEmpty()) {
                    mergedLines.add(cleaned)
                }
            }
        }

        // Remove duplicates
        val allLines = mergedLines.distinct()

        // ============================================================
        // STEP 4: Try matching MRZ_SECONDLINE on each candidate line
        // ============================================================
        for (line in allLines) {
            val matchResult = MRZ_SECONDLINE.find(line)
            if (matchResult != null) {
                val docNumGroup = matchResult.groupValues[1]
                val checkDigitDocNumGroup = cleanDate(matchResult.groupValues[2]).toIntOrNull() ?: 0
                val dobGroup = cleanDate(matchResult.groupValues[4])
                val expGroup = cleanDate(matchResult.groupValues[7])

                val cleanDocNum = cleanDocumentNumber(docNumGroup, checkDigitDocNumGroup)
                if (cleanDocNum != null) {
                    return ParsedMRZ(cleanDocNum, dobGroup, expGroup)
                } else {
                    return ParsedMRZ(docNumGroup.replace("O", "0"), dobGroup, expGroup)
                }
            }
        }

        // ============================================================
        // STEP 5: Fallback - concatenate all lines with hyphens
        // ============================================================
        var fullRead = ""
        for (line in allLines) {
            fullRead += "$line-"
        }

        val patternLineOldPassportType = Pattern.compile(REGEX_OLD_PASSPORT)
        val matcherLineOldPassportType = patternLineOldPassportType.matcher(fullRead)

        if (matcherLineOldPassportType.find()) {
            val documentNumber = matcherLineOldPassportType.group(1) ?: ""
            val checkDigitDocumentNumber = cleanDate(matcherLineOldPassportType.group(2) ?: "0").toIntOrNull() ?: 0
            val dateOfBirthDay = cleanDate(matcherLineOldPassportType.group(4) ?: "")
            val expirationDate = cleanDate(matcherLineOldPassportType.group(7) ?: "")

            val cleanDocumentNumber = cleanDocumentNumber(documentNumber, checkDigitDocumentNumber)
            if (cleanDocumentNumber != null) {
                return ParsedMRZ(cleanDocumentNumber, dateOfBirthDay, expirationDate)
            } else {
                return ParsedMRZ(documentNumber.replace("O", "0"), dateOfBirthDay, expirationDate)
            }
        } else {
            val patternLineIPassportTypeLine1 = Pattern.compile(REGEX_IP_PASSPORT_LINE_1)
            val matcherLineIPassportTypeLine1 = patternLineIPassportTypeLine1.matcher(fullRead)
            val patternLineIPassportTypeLine2 = Pattern.compile(REGEX_IP_PASSPORT_LINE_2)
            val matcherLineIPassportTypeLine2 = patternLineIPassportTypeLine2.matcher(fullRead)

            if (matcherLineIPassportTypeLine1.find() && matcherLineIPassportTypeLine2.find()) {
                val line1 = matcherLineIPassportTypeLine1.group(0) ?: ""
                val line2 = matcherLineIPassportTypeLine2.group(0) ?: ""
                
                if (line1.length >= 15 && line2.length >= 14) {
                    val documentNumber = line1.substring(5, 14)
                    val checkDigitDocumentNumber = line1.substring(14, 15).toIntOrNull() ?: 0
                    val dateOfBirthDay = line2.substring(0, 6)
                    val expirationDate = line2.substring(8, 14)

                    val cleanDocumentNumber = cleanDocumentNumber(documentNumber, checkDigitDocumentNumber)
                    if (cleanDocumentNumber != null) {
                        return ParsedMRZ(cleanDocumentNumber, dateOfBirthDay, expirationDate)
                    } else {
                        return ParsedMRZ(documentNumber.replace("O", "0"), dateOfBirthDay, expirationDate)
                    }
                }
            }
        }
        return null
    }

    private fun cleanDocumentNumber(documentNumber: String, checkDigit: Int): String? {
        var tempDocumentNumber = documentNumber.replace("O", "0")
        var checkDigitCalculated = MRZInfo.checkDigit(tempDocumentNumber).toString().toIntOrNull() ?: -1

        if (checkDigit == checkDigitCalculated) {
            return tempDocumentNumber
        }

        var indexOfZero = tempDocumentNumber.indexOf("0")
        while (indexOfZero > -1) {
            tempDocumentNumber = tempDocumentNumber.replaceFirst("0", "O")
            checkDigitCalculated = MRZInfo.checkDigit(tempDocumentNumber).toString().toIntOrNull() ?: -1
            if (checkDigit == checkDigitCalculated) {
                return tempDocumentNumber
            }
            indexOfZero = tempDocumentNumber.indexOf("0")
        }
        return null
    }

    private fun cleanDate(date: String): String {
        return date.replace("I", "1")
            .replace("L", "1")
            .replace("D", "0")
            .replace("O", "0")
            .replace("S", "5")
            .replace("G", "6")
    }

    /**
     * Groups OCR text LINES (not elements) horizontally based on vertical center coordinate (Y),
     * sorts them left-to-right (X), and returns the reconstructed lines.
     *
     * Uses line-level fragments instead of element-level to avoid cross-line merging
     * that happens when MRZ lines are very close together and individual element
     * Y-coordinates drift across the tolerance boundary.
     */
    fun getMergedRawLines(results: Text): List<String> {
        // Local data class with height for adaptive tolerance calculation
        data class LineFrag(
            val text: String,
            val centerY: Float,
            val left: Float,
            val height: Float
        )

        val fragments = mutableListOf<LineFrag>()
        for (block in results.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox
                if (box != null) {
                    val cleaned = normalizeLine(line.text)
                    if (cleaned.isNotEmpty()) {
                        fragments.add(LineFrag(
                            text = cleaned,
                            centerY = (box.top + box.bottom) / 2f,
                            left = box.left.toFloat(),
                            height = (box.bottom - box.top).toFloat()
                        ))
                    }
                }
            }
        }

        if (fragments.isEmpty()) return emptyList()

        // Adaptive Y-tolerance: 40% of average line height, clamped to [8, 20]
        // This prevents merging lines that are vertically close (like MRZ rows)
        val avgHeight = fragments.map { it.height }.average().toFloat()
        val yTolerance = (avgHeight * 0.4f).coerceIn(8f, 20f)

        val sortedByY = fragments.sortedBy { it.centerY }
        val rowGroups = mutableListOf<MutableList<LineFrag>>()

        for (frag in sortedByY) {
            val matchedGroup = rowGroups.find { group ->
                group.any { kotlin.math.abs(it.centerY - frag.centerY) < yTolerance }
            }
            if (matchedGroup != null) {
                // Avoid duplicates at the exact same location
                if (matchedGroup.none { it.left == frag.left && it.text == frag.text }) {
                    matchedGroup.add(frag)
                }
            } else {
                rowGroups.add(mutableListOf(frag))
            }
        }

        val mergedLines = mutableListOf<String>()
        // Sort groups by Y position from top to bottom
        val sortedRowGroups = rowGroups.sortedBy { group -> group.map { it.centerY }.average() }
        for (group in sortedRowGroups) {
            val sortedByX = group.sortedBy { it.left }
            val merged = sortedByX.joinToString("") { it.text }
            if (merged.isNotEmpty()) {
                mergedLines.add(merged)
            }
        }
        return mergedLines
    }

    private fun normalizeDateToYYMMDD(dateStr: String): String {
        val cleaned = dateStr.replace("[^0-9]".toRegex(), "")
        if (cleaned.length == 6) {
            return cleaned
        }
        if (cleaned.length == 8) {
            if (dateStr.contains("-") && dateStr.indexOf("-") == 4) {
                return cleaned.substring(2, 8)
            }
            val dd = cleaned.substring(0, 2)
            val mm = cleaned.substring(2, 4)
            val yy = cleaned.substring(6, 8)
            return yy + mm + dd
        }
        return cleaned
    }

    /**
     * Parses document number and birth/expiration dates from general (non-MRZ) text
     * by applying flexible regex rules.
     */
    fun parseGeneralText(lines: List<String>): ParsedMRZ? {
        if (lines.isEmpty()) return null

        var docNum = ""
        var dob = ""
        var exp = ""

        val docRegex = Regex("^[A-Z]{1,2}[0-9]{6,10}$|^[0-9A-Z]{7,12}$")
        val dateRegex = Regex("\\b\\d{2}[/-]\\d{2}[/-]\\d{4}\\b|\\b\\d{4}[/-]\\d{2}[/-]\\d{2}\\b|\\b\\d{6}\\b")

        val allWords = lines.flatMap { it.split("\\s+".toRegex()) }
            .map { it.replace("[^A-Z0-9]".toRegex(), "") }
            .filter { it.isNotEmpty() }

        docNum = allWords.firstOrNull { docRegex.matches(it) } ?: ""

        val dateMatches = lines.flatMap { line ->
            dateRegex.findAll(line).map { it.value }
        }.toList()

        if (dateMatches.isNotEmpty()) {
            dob = normalizeDateToYYMMDD(dateMatches[0])
            if (dateMatches.size > 1) {
                exp = normalizeDateToYYMMDD(dateMatches[1])
            }
        }

        if (docNum.isEmpty()) {
            docNum = allWords.firstOrNull { it.length in 7..15 } ?: ""
        }

        if (docNum.isEmpty() && lines.isNotEmpty()) {
            docNum = lines[0].replace("[^A-Z0-9]".toRegex(), "").take(15)
        }

        if (docNum.isNotEmpty()) {
            return ParsedMRZ(
                documentNumber = docNum,
                dateOfBirth = dob,
                expirationDate = exp
            )
        }
        return null
    }
}
