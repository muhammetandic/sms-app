package com.oaktech.smsapp.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oaktech.smsapp.R
import com.oaktech.smsapp.ui.conversations.Avatar

@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    onOpenConversation: (SearchResult) -> Unit,
    onBack: () -> Unit,
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    // Pushed into the view model on every change; the model debounces so a
    // keystroke does not query the store. The effect also seeds a restored
    // query when the process was recreated mid-search.
    LaunchedEffect(query) { viewModel.onQueryChange(query) }

    SearchScreen(
        query = query,
        onQueryChange = { query = it },
        results = results,
        onOpenConversation = onOpenConversation,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    onOpenConversation: (SearchResult) -> Unit,
    onBack: () -> Unit,
) {
    val backLabel = stringResource(R.string.thread_back)
    val hint = stringResource(R.string.search_hint)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(hint) },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                query.isBlank() -> EmptySearch(R.string.search_prompt)
                results.isEmpty() -> EmptySearch(R.string.search_empty)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(results, key = { result ->
                        // Body hits carry a unique message id; a conversation that
                        // matched only by its contact name is keyed on itself. Keys
                        // stay unique when one conversation matches several rows.
                        result.messageId?.let { "m:$it" } ?: "c:${result.conversationId}"
                    }) { result ->
                        SearchRow(result = result, onClick = { onOpenConversation(result) })
                    }
                }
            }
        }
    }
}

/**
 * A search hit row: the sender (resolved name or address), a snippet where the
 * text matched, and the time. Reads as a single accessibility node, mirroring
 * the conversation list.
 */
@Composable
private fun SearchRow(result: SearchResult, onClick: () -> Unit) {
    val description = stringResource(
        R.string.search_row_description,
        result.title,
        result.snippet,
        result.timestampLabel,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(title = result.title)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = result.timestampLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySearch(message: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
