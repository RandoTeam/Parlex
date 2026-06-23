package com.translive.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewerViewModel @Inject constructor() : ViewModel() {

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val content = AppLog.getFileLogContent()
            val lines = if (content.isBlank()) emptyList() else content.lines()
            _logLines.value = lines
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getFilteredLines(): List<String> {
        val query = _searchQuery.value
        val lines = _logLines.value
        return if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) }
    }

    fun clearLog() {
        AppLog.clearFileLog()
        _logLines.value = emptyList()
    }
}
