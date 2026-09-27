package net.wastu.wadbd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.wastu.wadbd.data.ActiveSession
import net.wastu.wadbd.data.AdbKey
import net.wastu.wadbd.data.WadbdRepository
import net.wastu.wadbd.data.WadbdState

class MainViewModel(
    private val repository: WadbdRepository = WadbdRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(WadbdState())
    val state: StateFlow<WadbdState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _state.value = repository.loadState()
            _isRefreshing.value = false
        }
    }

    fun toggleAdb(enable: Boolean, port: Int = 5555) {
        viewModelScope.launch {
            repository.toggleAdb(enable, port)
            refresh()
        }
    }

    fun toggleBoot(enable: Boolean, port: Int = 5555) {
        viewModelScope.launch {
            repository.toggleBootPersistence(enable, port)
            refresh()
        }
    }

    fun toggleNotifications(enable: Boolean) {
        viewModelScope.launch {
            repository.toggleNotifications(enable)
            refresh()
        }
    }

    fun kickSession(session: ActiveSession) {
        viewModelScope.launch {
            repository.kickSession(session)
            refresh()
        }
    }

    fun kickAllSessions() {
        viewModelScope.launch {
            repository.kickAllSessions()
            refresh()
        }
    }

    fun revokeKey(key: AdbKey) {
        viewModelScope.launch {
            repository.revokeKey(key)
            refresh()
        }
    }

    fun importKey(rawKey: String) {
        viewModelScope.launch {
            repository.importKey(rawKey)
            refresh()
        }
    }

    fun revokeAllKeys() {
        viewModelScope.launch {
            repository.revokeAllKeys()
            refresh()
        }
    }

    fun allowPendingKey(key: AdbKey) {
        viewModelScope.launch {
            repository.allowPendingKey(key)
            refresh()
        }
    }

    fun ignorePendingKey(key: AdbKey) {
        viewModelScope.launch {
            repository.ignorePendingKey(key)
            refresh()
        }
    }

    fun bindTarget(target: String) {
        viewModelScope.launch {
            repository.bindTarget(target)
            refresh()
        }
    }

    fun unbindTarget(target: String) {
        viewModelScope.launch {
            repository.unbindTarget(target)
            refresh()
        }
    }

    fun unbindAll() {
        viewModelScope.launch {
            repository.unbindAll()
            refresh()
        }
    }
}
