package dev.atlas.nym

import android.content.Context
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.Locale

/** JUnit XML for direct adb runs, without Gradle uninstalling evidence and data. */
class AndroidRunReport : RunListener() {
    private data class Case(
        val description: Description,
        val started: Long = System.nanoTime(),
        var elapsed: Long = 0,
        var failure: Failure? = null,
        var skipped: String? = null,
    )
    private val cases = linkedMapOf<String, Case>()
    private fun item(description: Description) = cases.getOrPut(description.displayName) { Case(description) }
    override fun testStarted(description: Description) { item(description) }
    override fun testFinished(description: Description) {
        item(description).let { it.elapsed = System.nanoTime() - it.started }
    }
    override fun testFailure(failure: Failure) { item(failure.description).failure = failure }
    override fun testAssumptionFailure(failure: Failure) { item(failure.description).skipped = failure.message.orEmpty() }
    override fun testIgnored(description: Description) { item(description).skipped = "Ignored" }
    override fun testRunFinished(result: Result) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "screenshots/junit").apply { mkdirs() }
        val name = InstrumentationRegistry.getArguments().getString("reportName") ?: "normal"
        require(name.matches(Regex("[a-z0-9-]+")))
        File(directory, "TEST-" + name + ".xml").bufferedWriter(Charsets.UTF_8).use { writer ->
            val xml = Xml.newSerializer()
            xml.setOutput(writer)
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "testsuite")
            xml.attribute(null, "name", "Nym Android " + name)
            xml.attribute(null, "tests", cases.size.toString())
            xml.attribute(null, "failures", cases.values.count { it.failure != null }.toString())
            xml.attribute(null, "errors", "0")
            xml.attribute(null, "skipped", cases.values.count { it.skipped != null }.toString())
            xml.attribute(null, "time", String.format(Locale.ROOT, "%.3f", result.runTime / 1000.0))
            for (case in cases.values) {
                xml.startTag(null, "testcase")
                xml.attribute(null, "classname", case.description.className.orEmpty())
                xml.attribute(null, "name", case.description.methodName ?: case.description.displayName)
                xml.attribute(null, "time", String.format(Locale.ROOT, "%.3f", case.elapsed / 1_000_000_000.0))
                case.failure?.let { failure ->
                    xml.startTag(null, "failure")
                    xml.attribute(null, "message", failure.message.orEmpty())
                    xml.text(failure.trace)
                    xml.endTag(null, "failure")
                }
                case.skipped?.let { reason ->
                    xml.startTag(null, "skipped")
                    xml.attribute(null, "message", reason)
                    xml.endTag(null, "skipped")
                }
                xml.endTag(null, "testcase")
            }
            xml.endTag(null, "testsuite")
            xml.endDocument()
        }
    }
}
