package dev.atlas.nym.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Only the 28 vectors used by this app; no thousands-of-icons runtime bundle. */
object NymIcons {
    private fun vector(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Home = vector("Home") { moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f); moveTo(5f, 9f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 9f) }
    val AlternateEmail = vector("Usernames") { circle(12f, 12f, 4f); moveTo(16f, 8f); lineTo(16f, 14f); curveTo(16f, 18f, 21f, 16f, 21f, 12f); curveTo(21f, 0f, 3f, 0f, 3f, 12f); curveTo(3f, 20f, 12f, 23f, 17f, 19f) }
    val History = vector("History") { moveTo(3f, 6f); lineTo(3f, 11f); lineTo(8f, 11f); moveTo(3f, 11f); curveTo(4f, 1f, 21f, 1f, 21f, 12f); curveTo(21f, 22f, 8f, 24f, 4f, 17f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f) }
    val Tune = vector("Settings") { moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(21f, 18f); moveTo(8f, 3f); lineTo(8f, 9f); moveTo(16f, 9f); lineTo(16f, 15f); moveTo(10f, 15f); lineTo(10f, 21f) }
    val Wifi = vector("Network") { moveTo(3f, 8f); curveTo(8f, 3f, 16f, 3f, 21f, 8f); moveTo(6f, 12f); curveTo(9f, 9f, 15f, 9f, 18f, 12f); moveTo(9f, 16f); curveTo(11f, 14f, 13f, 14f, 15f, 16f); circle(12f, 20f, 0.5f) }
    val VpnLock = vector("VPN") { moveTo(5f, 8f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 8f); close(); moveTo(8f, 8f); lineTo(8f, 6f); curveTo(8f, 1f, 16f, 1f, 16f, 6f); lineTo(16f, 8f); moveTo(12f, 12f); lineTo(12f, 15f) }
    val Pause = vector("Pause") { rectangle(7f, 5f, 10f, 19f); rectangle(14f, 5f, 17f, 19f) }
    val Stop = vector("Stop") { rectangle(6f, 6f, 18f, 18f) }
    val PlayArrow = vector("Start") { moveTo(8f, 4f); lineTo(20f, 12f); lineTo(8f, 20f); close() }
    val ChevronRight = vector("Open") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val FileDownload = vector("Export") { moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f) }
    val Search = vector("Search") { circle(10f, 10f, 6f); moveTo(14.5f, 14.5f); lineTo(21f, 21f) }
    val Sort = vector("Sort") { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(15f, 12f); moveTo(4f, 18f); lineTo(10f, 18f) }
    val ContentCopy = vector("Copy") { rectangle(8f, 7f, 20f, 21f); moveTo(16f, 7f); lineTo(16f, 3f); lineTo(4f, 3f); lineTo(4f, 17f); lineTo(8f, 17f) }
    val Shuffle = vector("Generator") { moveTo(3f, 6f); lineTo(7f, 6f); lineTo(17f, 18f); lineTo(21f, 18f); moveTo(18f, 15f); lineTo(21f, 18f); lineTo(18f, 21f); moveTo(3f, 18f); lineTo(7f, 18f); lineTo(17f, 6f); lineTo(21f, 6f); moveTo(18f, 3f); lineTo(21f, 6f); lineTo(18f, 9f) }
    val UploadFile = vector("Import") { moveTo(14f, 3f); lineTo(5f, 3f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 8f); lineTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f); moveTo(12f, 18f); lineTo(12f, 11f); moveTo(9f, 14f); lineTo(12f, 11f); lineTo(15f, 14f) }
    val Router = vector("Proxy") { rectangle(3f, 13f, 21f, 20f); moveTo(17f, 13f); lineTo(17f, 7f); moveTo(13f, 6f); curveTo(15f, 3f, 19f, 3f, 21f, 6f); circle(7f, 16.5f, 0.6f); circle(11f, 16.5f, 0.6f) }
    val Visibility = vector("Show") { moveTo(2f, 12f); curveTo(8f, 3f, 16f, 3f, 22f, 12f); curveTo(16f, 21f, 8f, 21f, 2f, 12f); close(); circle(12f, 12f, 3f) }
    val VisibilityOff = vector("Hide") { moveTo(3f, 3f); lineTo(21f, 21f); moveTo(3f, 11f); curveTo(6f, 5f, 15f, 3f, 22f, 12f); moveTo(20f, 15f); curveTo(14f, 21f, 7f, 20f, 2f, 12f) }
    val Add = vector("Add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val DeleteOutline = vector("Delete") { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f); moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f); moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f) }
    val Speed = vector("Performance") { moveTo(4f, 19f); curveTo(-3f, 2f, 27f, 2f, 20f, 19f); close(); moveTo(12f, 14f); lineTo(17f, 8f); circle(12f, 14f, 1f) }
    val Storage = vector("Storage") { rectangle(4f, 3f, 20f, 8f); rectangle(4f, 10f, 20f, 15f); rectangle(4f, 17f, 20f, 22f); moveTo(7f, 5.5f); lineTo(9f, 5.5f); moveTo(7f, 12.5f); lineTo(9f, 12.5f); moveTo(7f, 19.5f); lineTo(9f, 19.5f) }
    val DarkMode = vector("Appearance") { moveTo(15f, 3f); curveTo(0f, 0f, 0f, 23f, 15f, 21f); curveTo(20f, 20f, 22f, 17f, 22f, 14f); curveTo(10f, 17f, 9f, 8f, 15f, 3f); close() }
    val Info = vector("Information") { circle(12f, 12f, 9f); moveTo(12f, 11f); lineTo(12f, 17f); circle(12f, 7.5f, 0.4f) }
    val NetworkCheck = vector("Diagnostics") { moveTo(3f, 17f); lineTo(3f, 21f); lineTo(21f, 21f); lineTo(21f, 17f); moveTo(4f, 11f); curveTo(7f, 5f, 16f, 3f, 20f, 8f); moveTo(8f, 11f); curveTo(11f, 8f, 15f, 8f, 17f, 11f); moveTo(8f, 15f); lineTo(12f, 18f); lineTo(19f, 11f) }
    val ExpandLess = vector("Collapse") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ExpandMore = vector("Expand") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
}
private fun PathBuilder.rectangle(left: Float, top: Float, right: Float, bottom: Float) { moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom); close() }
private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
    val c = r * 0.5522848f
    moveTo(x + r, y); curveTo(x + r, y + c, x + c, y + r, x, y + r)
    curveTo(x - c, y + r, x - r, y + c, x - r, y); curveTo(x - r, y - c, x - c, y - r, x, y - r)
    curveTo(x + c, y - r, x + r, y - c, x + r, y); close()
}
