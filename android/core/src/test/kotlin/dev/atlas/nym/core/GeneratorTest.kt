package dev.atlas.nym.core

import java.math.BigInteger
import kotlin.test.*
import org.junit.Test

class GeneratorTest {
    @Test fun sequentialMatchesDesktopOrder() {
        val generator = CandidateGenerator(ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 2, charset = "ab"))
        assertEquals(listOf("aa", "ab", "ba", "bb"), (0..3).map { generator.at(it.toString())!!.username })
        assertNull(generator.at("4"))
    }
    @Test fun seededPermutationCoversSpaceWithoutDuplicates() {
        for (seed in 0L..40L) {
            val config = ScanConfig(mode = GenerationMode.RANDOM, length = 3, charset = "aabb1", seed = seed)
            val generator = CandidateGenerator(config)
            assertEquals(BigInteger.valueOf(27), generator.size)
            val values = (0..26).map { generator.at(it.toString())!!.username }
            assertEquals(27, values.distinct().size)
            assertEquals(values, (0..26).map { CandidateGenerator(config).at(it.toString())!!.username })
        }
    }
    @Test fun resumeIsIndependentOfEarlierIteration() {
        val config = ScanConfig(length = 8, seed = Long.MIN_VALUE)
        val original = CandidateGenerator(config)
        val resumed = CandidateGenerator(config)
        val cursor = "978621432"
        assertEquals(original.at(cursor), resumed.at(cursor))
        assertNotEquals(original.at(cursor), resumed.at((cursor.toBigInteger() + BigInteger.ONE).toString()))
    }
    @Test fun length32DoesNotOverflowLong() {
        val generator = CandidateGenerator(ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 32, charset = "ab"))
        assertEquals("b".repeat(32), generator.at((generator.size - BigInteger.ONE).toString())!!.username)
        assertEquals("a".repeat(32), generator.at("0")!!.username)
    }
    @Test fun fullDiscordSpaceUsesBigInteger() {
        val generator = CandidateGenerator(ScanConfig(length = 32))
        assertTrue(generator.size > BigInteger.valueOf(Long.MAX_VALUE))
        assertEquals(32, generator.at((generator.size - BigInteger.ONE).toString())!!.username.length)
    }
    @Test fun patternTokensAndLiterals() {
        val generator = CandidateGenerator(ScanConfig(mode = GenerationMode.PATTERN, pattern = "a#*", charset = "xy"))
        assertEquals(listOf("a0x", "a0y", "a1x"), (0..2).map { generator.at(it.toString())!!.username })
        assertEquals("a9y", generator.at("19")!!.username)
        assertNull(generator.at("20"))
    }
    @Test fun patternLettersAndResume() {
        val generator = CandidateGenerator(ScanConfig(mode = GenerationMode.PATTERN, pattern = "@#"))
        assertEquals("a0", generator.at("0")!!.username)
        assertEquals("z9", generator.at("259")!!.username)
    }
    @Test fun dictionarySnapshotsNormalizeDeduplicateAndValidate() {
        val config = ScanConfig(mode = GenerationMode.DICTIONARY, dictionary = "  ALPHA  \nalpha\nx\nBeta\ninvalid!\n..\nvalid.name")
        config.validate()
        val generator = CandidateGenerator(config)
        assertEquals(listOf("alpha", "beta", "valid.name"), (0..2).map { generator.at(it.toString())!!.username })
        assertNull(generator.at("3"))
    }
    @Test fun validationRejectsUnsafeAndBrokenSettings() {
        listOf(ScanConfig(length = 1), ScanConfig(length = 33), ScanConfig(charset = ""), ScanConfig(charset = "A"),
            ScanConfig(workers = 5), ScanConfig(intervalMs = 0), ScanConfig(jitterMs = -1), ScanConfig(retries = 4), ScanConfig(timeoutSeconds = 0),
            ScanConfig(mode = GenerationMode.PATTERN, pattern = "!@"), ScanConfig(mode = GenerationMode.DICTIONARY), ScanConfig(proxyEnabled = true))
            .forEach { assertFailsWith<IllegalArgumentException> { it.validate() } }
    }
    @Test fun randomExhaustsSingletonSpace() {
        val generator = CandidateGenerator(ScanConfig(length = 2, charset = "a", seed = -100))
        assertEquals("aa", generator.at("0")!!.username)
        assertNull(generator.at("1"))
    }
}
