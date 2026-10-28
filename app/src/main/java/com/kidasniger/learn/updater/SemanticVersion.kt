package com.kidasniger.learn.updater

import java.math.BigInteger

/** Strict SemVer 2.0 comparison. Build metadata does not affect precedence. */
object SemanticVersion {
    private val pattern = Regex(
        """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"""
    )

    data class Parsed(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
        val prerelease: List<String>
    )

    fun parse(value: String): Parsed? {
        val match = pattern.matchEntire(value.trim()) ?: return null
        val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        if (prerelease.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
        return try {
            Parsed(BigInteger(match.groupValues[1]), BigInteger(match.groupValues[2]),
                BigInteger(match.groupValues[3]), prerelease)
        } catch (_: NumberFormatException) { null }
    }

    fun compare(first: String, second: String): Int {
        val left = parse(first) ?: throw IllegalArgumentException("Version invalide : $first")
        val right = parse(second) ?: throw IllegalArgumentException("Version invalide : $second")
        return compare(left, right)
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private fun compare(left: Parsed, right: Parsed): Int {
        left.major.compareTo(right.major).takeIf { it != 0 }?.let { return it }
        left.minor.compareTo(right.minor).takeIf { it != 0 }?.let { return it }
        left.patch.compareTo(right.patch).takeIf { it != 0 }?.let { return it }
        if (left.prerelease.isEmpty() && right.prerelease.isEmpty()) return 0
        if (left.prerelease.isEmpty()) return 1
        if (right.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(left.prerelease.size, right.prerelease.size)) {
            val a = left.prerelease[index]
            val b = right.prerelease[index]
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            val result = when {
                aNumeric && bNumeric -> BigInteger(a).compareTo(BigInteger(b))
                aNumeric && !bNumeric -> -1
                !aNumeric && bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return left.prerelease.size.compareTo(right.prerelease.size)
    }
}
