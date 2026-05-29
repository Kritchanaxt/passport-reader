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

    fun normalizeLine(text: String): String {
        // 1. Remove standard whitespace and spacer layout artifacts
        var result = text.replace(" ", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")

        // 2. Normalize caret markers, double arrows, and lowercase filler misrecognitions to '<'
        result = result.replace("^", "<")
            .replace("«", "<")
            .replace("»", "<")
            .replace("c", "<") // Lowercase 'c' is never valid in standard MRZ
            .replace("k", "<") // Lowercase 'k' at edges is typical OCR noise
            .replace("{", "<")
            .replace("}", "<")
            .replace("[", "<")
            .replace("]", "<")
            .replace("(", "<")
            .replace(")", "<")

        // 3. Normalize uppercase 'C' acting as filler character noise
        result = result.replace(Regex("<C+<"), "<")
        result = result.replace(Regex("<C+"), "<")
        result = result.replace(Regex("C+<"), "<")
        result = result.replace(Regex("C+$"), "<")
        result = result.replace(Regex("^C+"), "<")

        return result.uppercase()
    }

    fun parse(results: Text): ParsedMRZ? {
        val allLines = mutableListOf<String>()
        val blocks = results.textBlocks
        
        for (i in blocks.indices) {
            val lines = blocks[i].lines
            for (j in lines.indices) {
                val cleaned = normalizeLine(lines[j].text)
                if (cleaned.isNotEmpty()) {
                    allLines.add(cleaned)
                }
            }
        }

        // Search for a line matching MRZ_SECONDLINE
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

        // Fallback: Concatenate with hyphens to support old REGEX layout if structure is slightly warped
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
}
