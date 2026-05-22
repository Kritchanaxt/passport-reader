package com.tananaev.passportreader

import com.google.mlkit.vision.text.Text
import org.jmrtd.lds.icao.MRZInfo
import java.util.regex.Pattern

object MRZParser {

    private val REGEX_OLD_PASSPORT = "(?<documentNumber>[A-Z0-9<]{9})(?<checkDigitDocumentNumber>[0-9ILDSOG]{1})(?<nationality>[A-Z<]{3})(?<dateOfBirth>[0-9ILDSOG]{6})(?<checkDigitDateOfBirth>[0-9ILDSOG]{1})(?<sex>[FM<]){1}(?<expirationDate>[0-9ILDSOG]{6})(?<checkDigitExpiration>[0-9ILDSOG]{1})"
    private val REGEX_IP_PASSPORT_LINE_1 = "\\bIP[A-Z<]{3}[A-Z0-9<]{9}[0-9]{1}"
    private val REGEX_IP_PASSPORT_LINE_2 = "[0-9]{6}[0-9]{1}[FM<]{1}[0-9]{6}[0-9]{1}[A-Z<]{3}"

    data class ParsedMRZ(
        val documentNumber: String,
        val dateOfBirth: String,
        val expirationDate: String
    )

    fun parse(results: Text): ParsedMRZ? {
        var fullRead = ""
        val blocks = results.textBlocks
        for (i in blocks.indices) {
            var temp = ""
            val lines = blocks[i].lines
            for (j in lines.indices) {
                temp += lines[j].text + "-"
            }
            temp = temp.replace("\r".toRegex(), "").replace("\n".toRegex(), "").replace("\t".toRegex(), "").replace(" ", "")
            fullRead += "$temp-"
        }
        fullRead = fullRead.uppercase()

        val patternLineOldPassportType = Pattern.compile(REGEX_OLD_PASSPORT)
        val matcherLineOldPassportType = patternLineOldPassportType.matcher(fullRead)

        if (matcherLineOldPassportType.find()) {
            var documentNumber = matcherLineOldPassportType.group(1)
            val checkDigitDocumentNumber = cleanDate(matcherLineOldPassportType.group(2) ?: "0").toIntOrNull() ?: 0
            val dateOfBirthDay = cleanDate(matcherLineOldPassportType.group(4) ?: "")
            val expirationDate = cleanDate(matcherLineOldPassportType.group(7) ?: "")

            val cleanDocumentNumber = cleanDocumentNumber(documentNumber ?: "", checkDigitDocumentNumber)
            if (cleanDocumentNumber != null) {
                return ParsedMRZ(cleanDocumentNumber, dateOfBirthDay, expirationDate)
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
                    var documentNumber = line1.substring(5, 14)
                    val checkDigitDocumentNumber = line1.substring(14, 15).toIntOrNull() ?: 0
                    val dateOfBirthDay = line2.substring(0, 6)
                    val expirationDate = line2.substring(8, 14)

                    val cleanDocumentNumber = cleanDocumentNumber(documentNumber, checkDigitDocumentNumber)
                    if (cleanDocumentNumber != null) {
                        return ParsedMRZ(cleanDocumentNumber, dateOfBirthDay, expirationDate)
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
