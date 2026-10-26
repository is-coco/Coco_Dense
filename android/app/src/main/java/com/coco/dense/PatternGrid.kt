package com.coco.dense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal fun appendPatternPoint(current: List<Int>, next: Int): List<Int> {
    if (next !in 0..8 || next in current) return current
    if (current.isEmpty()) return listOf(next)
    val last = current.last()
    val rowSum = last / 3 + next / 3
    val colSum = last % 3 + next % 3
    val crossed = abs(last / 3 - next / 3) == 2 || abs(last % 3 - next % 3) == 2
    val middle = if (crossed && rowSum % 2 == 0 && colSum % 2 == 0) (rowSum / 2) * 3 + colSum / 2 else -1
    return if (middle in 0..8 && middle !in current) current + middle + next else current + next
}

@Composable
fun PatternGrid(modifier: Modifier = Modifier, onComplete: (List<Int>) -> Unit) {
    var selected by remember { mutableStateOf(emptyList<Int>()) }
    val primary = Color(0xFF27345B)
    val idle = Color(0xFFC5CFDE)
    val hitRadius = with(LocalDensity.current) { 30.dp.toPx() }
    Canvas(modifier.size(252.dp).semantics { contentDescription = "九宫格图案" }.pointerInput(onComplete) {
        fun hit(position: Offset): Int {
            val step = size.width.coerceAtMost(size.height) / 3f
            for (index in 0..8) {
                val center = Offset((index % 3 + .5f) * step, (index / 3 + .5f) * step)
                if ((position - center).getDistance() <= hitRadius) return index
            }
            return -1
        }
        awaitEachGesture {
            selected = emptyList()
            val down = awaitFirstDown(requireUnconsumed = false)
            selected = appendPatternPoint(selected, hit(down.position))
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull()
                if (change != null && change.pressed) selected = appendPatternPoint(selected, hit(change.position))
            } while (event.changes.any { it.pressed })
            if (selected.isNotEmpty()) onComplete(selected)
            selected = emptyList()
        }
    }) {
        val step = size.minDimension / 3f
        fun center(index: Int) = Offset((index % 3 + .5f) * step, (index / 3 + .5f) * step)
        selected.zipWithNext().forEach { (from, to) ->
            drawLine(primary, center(from), center(to), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }
        for (index in 0..8) {
            val active = index in selected
            drawCircle(if (active) Color(0xFFE2F2E3) else Color.White, radius = 20.dp.toPx(), center = center(index))
            drawCircle(if (active) primary else idle, radius = 20.dp.toPx(), center = center(index), style = Stroke(2.dp.toPx()))
            drawCircle(if (active) primary else idle, radius = 5.dp.toPx(), center = center(index))
        }
    }
}
