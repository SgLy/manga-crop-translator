package me.sgly.translator.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Written from the streaming IO thread, popped and configured on the main thread,
 * so every write goes through the same lock. Readers only ever see an immutable snapshot.
 */
internal class StreamGrid(
    val capacity: Int = 16,
) {
    private val lock = Any()
    private var columns = 0
    private var isStrong = false
    private var snapshot by mutableStateOf(GridCells(rows = emptyList(), filledInLastRow = 0, firstRowIndex = 0))

    val state: GridCells
        get() = snapshot

    fun setColumns(newColumns: Int) {
        require(newColumns > 0) { "newColumns=$newColumns" }
        synchronized(lock) {
            if (newColumns == columns) return
            if (columns != 0) snapshot = snapshot.reflow(newColumns, capacity)
            columns = newColumns
        }
    }

    fun append(length: Int) {
        require(length > 0) { "length=$length" }
        synchronized(lock) {
            if (columns == 0) return
            isStrong = !isStrong
            snapshot = snapshot.append(isStrong, length, columns, capacity)
        }
    }

    fun popFirstRow() {
        synchronized(lock) {
            snapshot = snapshot.popFirst()
        }
    }

    fun scrollTarget(viewportRows: Int): Int = synchronized(lock) { maxOf(0, snapshot.firstRowIndex + snapshot.rows.size - viewportRows) }

    /** Row count and cell count read in one go, so an export never reports a half-updated grid. */
    fun fill(): GridFill =
        synchronized(lock) {
            val filled = if (snapshot.rows.isEmpty()) 0 else (snapshot.rows.size - 1) * columns + snapshot.filledInLastRow
            GridFill(rows = snapshot.rows.size, filledCells = filled)
        }

    fun clear() {
        synchronized(lock) {
            snapshot = GridCells(rows = emptyList(), filledInLastRow = 0, firstRowIndex = 0)
            isStrong = false
        }
    }
}

internal data class GridFill(
    val rows: Int,
    val filledCells: Int,
)

internal data class GridCells(
    val rows: List<BooleanArray>,
    val filledInLastRow: Int,
    val firstRowIndex: Int,
) {
    fun filledIn(rowIndex: Int): Int = if (rowIndex == rows.lastIndex) filledInLastRow else rows[rowIndex].size

    fun append(
        isStrong: Boolean,
        length: Int,
        columns: Int,
        capacity: Int,
    ): GridCells {
        val rows = rows.toMutableList()
        var filled = filledInLastRow
        var firstRowIndex = firstRowIndex
        var remaining = length
        while (remaining > 0) {
            if (rows.isEmpty() || filled == columns) {
                rows.add(BooleanArray(columns))
                filled = 0
                if (rows.size > capacity) {
                    rows.removeAt(0)
                    firstRowIndex++
                }
            }
            val take = minOf(columns - filled, remaining)
            val row = rows[rows.lastIndex].copyOf()
            row.fill(isStrong, filled, filled + take)
            rows[rows.lastIndex] = row
            filled += take
            remaining -= take
        }
        return GridCells(rows = rows, filledInLastRow = filled, firstRowIndex = firstRowIndex)
    }

    fun popFirst(): GridCells {
        check(rows.size > 1) { "rows=${rows.size}" }
        return GridCells(rows = rows.drop(1), filledInLastRow = filledInLastRow, firstRowIndex = firstRowIndex + 1)
    }

    fun reflow(
        columns: Int,
        capacity: Int,
    ): GridCells {
        val oldColumns = rows.firstOrNull()?.size ?: columns
        val cells = ArrayList<Boolean>()
        rows.forEachIndexed { index, row ->
            for (column in 0 until filledIn(index)) cells += row[column]
        }
        val kept = if (cells.size > capacity * columns) cells.subList(cells.size - capacity * columns, cells.size) else cells
        val repacked = ArrayList<BooleanArray>()
        var index = 0
        while (index < kept.size) {
            val row = BooleanArray(columns)
            val take = minOf(columns, kept.size - index)
            for (offset in 0 until take) row[offset] = kept[index + offset]
            repacked += row
            index += take
        }
        return GridCells(
            rows = repacked,
            filledInLastRow = if (kept.isEmpty()) 0 else (kept.size - 1) % columns + 1,
            firstRowIndex = firstRowIndex + (cells.size - kept.size) / oldColumns,
        )
    }
}
