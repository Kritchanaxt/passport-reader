package com.tananaev.passportreader.features.mrz

import java.util.regex.Pattern

object MRZPatterns {
    const val REGEX_OLD_PASSPORT_STR = "(?<documentNumber>[A-Z0-9<]{9})(?<checkDigitDocumentNumber>[0-9ILDSOG]{1})(?<nationality>[A-Z<]{3})(?<dateOfBirth>[0-9ILDSOG]{6})(?<checkDigitDateOfBirth>[0-9ILDSOG]{1})(?<sex>[FM<]){1}(?<expirationDate>[0-9ILDSOG]{6})(?<checkDigitExpiration>[0-9ILDSOG]{1})"
    const val REGEX_IP_PASSPORT_LINE_1_STR = "\\bIP[A-Z<]{3}[A-Z0-9<]{9}[0-9]{1}"
    const val REGEX_IP_PASSPORT_LINE_2_STR = "[0-9]{6}[0-9]{1}[FM<]{1}[0-9]{6}[0-9]{1}[A-Z<]{3}"

    val REGEX_OLD_PASSPORT: Pattern = Pattern.compile(REGEX_OLD_PASSPORT_STR)
    val REGEX_IP_PASSPORT_LINE_1: Pattern = Pattern.compile(REGEX_IP_PASSPORT_LINE_1_STR)
    val REGEX_IP_PASSPORT_LINE_2: Pattern = Pattern.compile(REGEX_IP_PASSPORT_LINE_2_STR)

    val MRZ_FIRSTLINE = Regex("([A-Z])([A-Z0-9<])([A-Z]{3})([A-Z<]{36,39})")
    val MRZ_SECONDLINE = Regex("([A-Z0-9<]{9})([0-9ILDSOG])([A-Z<]{3})([0-9ILDSOG]{6})([0-9ILDSOG])([MF<])([0-9ILDSOG]{6})([0-9ILDSOG])([A-Z0-9<]{14})([0-9ILDSOG])([0-9ILDSOG])")

    val GENERAL_DOC_REGEX = Regex("^[A-Z]{1,2}[0-9]{6,10}$|^[0-9A-Z]{7,12}$")
    val GENERAL_DATE_REGEX = Regex("\\b\\d{2}[/-]\\d{2}[/-]\\d{4}\\b|\\b\\d{4}[/-]\\d{2}[/-]\\d{2}\\b|\\b\\d{6}\\b")
}
