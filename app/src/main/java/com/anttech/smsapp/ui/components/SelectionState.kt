package com.anttech.smsapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Single helper for multi-selection state across conversation lists.
 *
 * Toggling an item adds it if absent and removes it if present; an empty set
 * drops the screen back to normal navigation mode.
 */
fun <T> toggleSelection(selected: Set<T>, id: T): Set<T> =
    if (id in selected) selected - id else selected + id

/**
 * Encapsulates multi-selection state for list screens.
 *
 * Tracks the selected IDs as Compose state and provides toggle, contains, and
 * clear operations so list screens don't duplicate selection set boilerplate.
 */
@Stable
class SelectionState<T>(initialSelected: Set<T> = emptySet()) {
    var selectedIds by mutableStateOf(initialSelected)

    val isSelecting: Boolean get() = selectedIds.isNotEmpty()

    val size: Int get() = selectedIds.size

    fun isSelected(id: T): Boolean = id in selectedIds

    fun toggle(id: T) {
        selectedIds = toggleSelection(selectedIds, id)
    }

    fun clear() {
        selectedIds = emptySet()
    }
}

@Composable
fun <T> rememberSelectionState(initialSelected: Set<T> = emptySet()): SelectionState<T> =
    remember { SelectionState(initialSelected) }
