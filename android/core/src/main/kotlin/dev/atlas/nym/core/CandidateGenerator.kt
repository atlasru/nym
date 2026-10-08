package dev.atlas.nym.core

import java.math.BigInteger
import java.util.Locale
import java.util.Random

/** Random uses a seeded permutation, so a finite space never loops on duplicates. */
class CandidateGenerator(private val config: ScanConfig) {
    private val dictionary = if (config.mode == GenerationMode.DICTIONARY) dictionaryWords(config.dictionary) else emptyList()
    private val alphabets = if (config.mode == GenerationMode.PATTERN) {
        config.pattern.map { when (it) {
            '@' -> "abcdefghijklmnopqrstuvwxyz"
            '#' -> "0123456789"
            '*' -> config.charset.toSet().joinToString("")
            else -> it.toString()
        } }
    } else List(config.length) { config.charset.toSet().joinToString("") }
    val size: BigInteger = if (config.mode == GenerationMode.DICTIONARY) BigInteger.valueOf(dictionary.size.toLong())
        else alphabets.fold(BigInteger.ONE) { total, chars -> total * BigInteger.valueOf(chars.length.toLong()) }
    private val rng = Random(config.seed)
    private val multiplier: BigInteger = if (size > BigInteger.ONE) {
        var candidate = BigInteger(size.bitLength(), rng).mod(size).max(BigInteger.ONE)
        while (candidate.gcd(size) != BigInteger.ONE) candidate = (candidate + BigInteger.ONE).mod(size).max(BigInteger.ONE)
        candidate
    } else BigInteger.ONE
    private val offset = if (size > BigInteger.ZERO) BigInteger(size.bitLength(), rng).mod(size) else BigInteger.ZERO

    fun at(cursor: String): Candidate? {
        val ordinal = cursor.toBigInteger()
        if (ordinal < BigInteger.ZERO || ordinal >= size) return null
        if (config.mode == GenerationMode.DICTIONARY) return Candidate(dictionary[ordinal.toInt()], cursor)
        var index = if (config.mode == GenerationMode.RANDOM) (multiplier * ordinal + offset).mod(size) else ordinal
        val result = CharArray(alphabets.size)
        for (position in alphabets.indices.reversed()) {
            val base = BigInteger.valueOf(alphabets[position].length.toLong())
            val (quotient, remainder) = index.divideAndRemainder(base)
            result[position] = alphabets[position][remainder.toInt()]
            index = quotient
        }
        return Candidate(String(result), cursor)
    }

    companion object {
        fun validUsername(value: String) = value.length in 2..32 && value.all { it in DEFAULT_CHARSET } && ".." !in value
        fun dictionaryWords(value: String): List<String> = value.lineSequence()
            .map { it.trim().lowercase(Locale.ROOT) }.filter { validUsername(it) }.distinct().toList()
    }
}
